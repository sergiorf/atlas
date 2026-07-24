package atlas.history

import atlas.config.AtlasConfig
import atlas.receita.{CompanyDataPaths, CompanySilverMetrics}
import java.nio.file.{Files, Path, Paths}
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.storage.StorageLevel

final case class CompanyHistoryResult(
    release: String,
    previousRows: Option[Long],
    currentRows: Long,
    inserted: Long,
    updated: Long,
    removed: Long,
    eventCount: Long
)

object CompanyHistoryJob {
  val TrackedFields: Seq[String] = Seq(
    "legal_name", "legal_nature_code", "responsible_qualification_code", "share_capital",
    "company_size_code", "responsible_federative_entity"
  )

  def eventRoot(config: AtlasConfig): Path = Paths.get(config.receita.silverDir).resolve("company_change_events")
  def eventRelease(config: AtlasConfig): Path = eventRoot(config).resolve(s"to_release=${config.receita.snapshot}")
  def summaryRoot(config: AtlasConfig): Path = Paths.get(config.receita.silverDir).resolve("company_release_summaries")
  def summaryRelease(config: AtlasConfig): Path = summaryRoot(config).resolve(s"to_release=${config.receita.snapshot}")

  def refresh(
      spark: SparkSession,
      config: AtlasConfig,
      bundleId: String,
      quality: CompanySilverMetrics = CompanySilverMetrics(0L, 0L, 0L, 0L, 0L, 0L)
  ): CompanyHistoryResult = {
    val candidate = spark.read.parquet(CompanyDataPaths.silverCompanyCandidate(config).toString).persist(StorageLevel.DISK_ONLY)
    try {
      val currentPath = CompanyDataPaths.silverCompanies(config)
      val result = if (parquetExists(spark, currentPath))
        compare(spark, config, spark.read.parquet(currentPath.toString), candidate, bundleId, quality)
      else seed(spark, config, candidate, bundleId, quality)
      candidate.write.mode("overwrite").parquet(currentPath.toString)
      result
    } finally candidate.unpersist()
  }

  private def seed(
      spark: SparkSession,
      config: AtlasConfig,
      candidate: DataFrame,
      bundleId: String,
      quality: CompanySilverMetrics
  ): CompanyHistoryResult = {
    val count = candidate.count()
    writeSummary(spark, config, bundleId, None, None, count, 0L, 0L, 0L, 0L, quality)
    CompanyHistoryResult(config.receita.snapshot, None, count, 0L, 0L, 0L, 0L)
  }

  private def compare(
      spark: SparkSession,
      config: AtlasConfig,
      prior: DataFrame,
      candidate: DataFrame,
      bundleId: String,
      quality: CompanySilverMetrics
  ): CompanyHistoryResult = {
    val old = prior.select((Seq(col("cnpj_root"), col("release"), col("record_hash")) ++ TrackedFields.map(col)): _*).as("old")
    val fresh = candidate.select((Seq(col("cnpj_root"), col("release"), col("record_hash")) ++ TrackedFields.map(col)): _*).as("new")
    val changes = array(TrackedFields.map { field =>
      when(!(col(s"old.$field") <=> col(s"new.$field")), struct(
        lit(field).as("field_name"), col(s"old.$field").cast("string").as("old_value"), col(s"new.$field").cast("string").as("new_value")
      ))
    }: _*)
    val duplicateRoots =
      if (quality.duplicateKeyCount > 0)
        spark.read.parquet(CompanyDataPaths.qualityRoot(config).resolve("duplicate_companies").toString)
          .select("cnpj_root").distinct().withColumn("_quality_quarantined", lit(true))
      else spark.createDataFrame(
        spark.sparkContext.emptyRDD[org.apache.spark.sql.Row],
        org.apache.spark.sql.types.StructType(Seq(
          org.apache.spark.sql.types.StructField("cnpj_root", org.apache.spark.sql.types.StringType, nullable = false),
          org.apache.spark.sql.types.StructField("_quality_quarantined", org.apache.spark.sql.types.BooleanType, nullable = false)
        ))
      )
    val events = old.join(fresh, Seq("cnpj_root"), "full_outer")
      .join(duplicateRoots, Seq("cnpj_root"), "left")
      .withColumn("change_type", when(col("old.record_hash").isNull, "inserted")
        .when(col("new.record_hash").isNull, "removed")
        .when(col("old.record_hash") =!= col("new.record_hash"), "updated"))
      .filter(col("change_type").isNotNull)
      .withColumn("_changes", changes)
      .withColumn("changed_fields", expr("filter(_changes, x -> x is not null)"))
      .select(
        sha2(concat_ws("|", lit("receita"), lit("companies"), col("cnpj_root"), lit(config.receita.snapshot), col("change_type")), 256).as("event_id"),
        lit("receita").as("source"), lit("companies").as("dataset"), col("cnpj_root"),
        col("old.release").as("from_release"), lit(config.receita.snapshot).as("to_release"), col("change_type"),
        when(col("change_type") === "removed" && col("_quality_quarantined"), lit("quality_quarantine"))
          .when(col("change_type") === "removed", lit("source_absent"))
          .otherwise(lit(null).cast("string")).as("change_reason"),
        col("changed_fields"), current_timestamp().as("detected_at")
      ).persist(StorageLevel.DISK_ONLY)
    try {
      val counts = events.groupBy("change_type").count().collect().map(row => row.getString(0) -> row.getLong(1)).toMap
      val previous = prior.count()
      val fromRelease = prior.select("release").distinct().collect().map(_.getString(0)).toSeq match {
        case Seq(value) => Some(value)
        case values => throw new IllegalStateException(s"Prior company state must contain one release, found: ${values.mkString(", ")}")
      }
      val current = candidate.count()
      val inserted = counts.getOrElse("inserted", 0L)
      val updated = counts.getOrElse("updated", 0L)
      val removed = counts.getOrElse("removed", 0L)
      if (current - previous != inserted - removed)
        throw new IllegalStateException(s"Company history invariant failed: $current - $previous != $inserted - $removed")
      val total = inserted + updated + removed
      if (total > 0) events.write.mode("overwrite").parquet(eventRelease(config).toString)
      writeSummary(spark, config, bundleId, fromRelease, Some(previous), current,
        inserted, updated, removed, total, quality)
      CompanyHistoryResult(config.receita.snapshot, Some(previous), current, inserted, updated, removed, total)
    } finally events.unpersist()
  }

  private def writeSummary(
      spark: SparkSession,
      config: AtlasConfig,
      bundleId: String,
      fromRelease: Option[String],
      previous: Option[Long],
      current: Long,
      inserted: Long,
      updated: Long,
      removed: Long,
      events: Long,
      quality: CompanySilverMetrics
  ): Unit = {
    import spark.implicits._
    Seq((
      s"receita|companies|${config.receita.snapshot}", "receita", "companies", fromRelease.orNull,
      config.receita.snapshot, previous.map(Long.box).orNull, current, inserted, updated, removed, events,
      quality.malformedRowCount, quality.duplicateRowCount, quality.duplicateKeyCount,
      quality.missingReferenceCount, bundleId, "success",
      new java.sql.Timestamp(System.currentTimeMillis())
    )).toDF(
      "summary_id", "source", "dataset", "from_release", "to_release", "previous_record_count",
      "current_record_count", "inserted_count", "updated_count", "removed_count", "event_count",
      "malformed_count", "duplicate_count", "duplicate_key_count", "reference_miss_count",
      "bundle_id", "outcome", "calculated_at"
    ).write.mode("overwrite").parquet(summaryRelease(config).toString)
  }

  private def parquetExists(spark: SparkSession, path: Path): Boolean = {
    val hadoopPath = new org.apache.hadoop.fs.Path(path.toString)
    val fs = hadoopPath.getFileSystem(spark.sparkContext.hadoopConfiguration)
    fs.exists(hadoopPath) && fs.listStatus(hadoopPath).exists(_.getPath.getName.endsWith(".parquet"))
  }
}
