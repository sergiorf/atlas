package atlas.history

import atlas.common.DatasetPaths
import atlas.config.AtlasConfig
import atlas.receita.{SilverEstablishmentJob, SilverQualityReport}
import atlas.release.ReleasePaths
import atlas.release.ReleaseId
import atlas.status.{RunStatus, RunStatusRegistry}
import java.nio.file.Paths
import java.time.{Duration, Instant}
import org.apache.hadoop.fs.Path
import org.apache.spark.sql.{Column, DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.storage.StorageLevel

final case class HistoryResult(
    release: String,
    currentRowCount: Long,
    previousRowCount: Option[Long],
    insertedCount: Long,
    updatedCount: Long,
    removedCount: Long,
    eventRowCount: Long,
    outputPath: String
)

final case class StateReleaseCount(
    state: Option[String],
    previous_count: Option[Long],
    current_count: Option[Long],
    delta: Long
)
final case class ChangedFieldCount(field_name: String, count: Long)
final case class EstablishmentReleaseSummary(
    summary_id: String,
    source: String,
    dataset: String,
    from_release: Option[String],
    to_release: String,
    calculated_at: java.sql.Timestamp,
    schema_version: String,
    previous_record_count: Option[Long],
    current_record_count: Long,
    net_record_delta: Option[Long],
    inserted_count: Long,
    updated_count: Long,
    removed_count: Long,
    event_count: Long,
    state_counts: Seq[StateReleaseCount],
    changed_field_counts: Seq[ChangedFieldCount]
)

object EstablishmentHistoryJob {
  sealed trait CurrentRelease
  case object NoCurrent extends CurrentRelease
  case object LegacyCurrent extends CurrentRelease
  final case class KnownCurrent(release: ReleaseId) extends CurrentRelease
  val TrackedFields: Seq[String] = Seq(
    "is_headquarters",
    "trade_name",
    "registration_status_code",
    "is_active",
    "registration_status_date",
    "registration_status_reason",
    "opening_date",
    "main_cnae",
    "secondary_cnaes",
    "street_type",
    "street_name",
    "street_number",
    "address_extra",
    "neighborhood",
    "postal_code",
    "state",
    "municipality_code",
    "country_code",
    "foreign_city_name",
    "phone_1_area_code",
    "phone_1_number",
    "phone_2_area_code",
    "phone_2_number",
    "fax_area_code",
    "fax_number",
    "email",
    "special_status",
    "special_status_date"
  )

  def validateAdvance(
      spark: SparkSession,
      config: AtlasConfig,
      allowLegacyCurrent: Boolean = false
  ): CurrentRelease = {
    val paths = ReleasePaths(config)
    if (!parquetExists(spark, paths.silverCurrent.toString)) NoCurrent
    else {
      val current = spark.read.parquet(paths.silverCurrent.toString)
      if (!current.columns.contains("release")) {
        if (!allowLegacyCurrent)
          throw new IllegalStateException(
            "Current establishments table has no release metadata; rerun with --allow-legacy-current " +
              "only after verifying that the candidate is newer"
          )
        LegacyCurrent
      } else {
        val values = current.select("release").distinct().limit(2).collect().map(_.getAs[String](0)).toSeq
        if (values.size != 1 || values.head == null)
          throw new IllegalStateException("Current establishments table must contain exactly one non-null release")
        val currentRelease = ReleaseId.parse(values.head).fold(message => throw new IllegalStateException(message), identity)
        val candidate = ReleaseId.unsafe(config.receita.snapshot)
        if (candidate <= currentRelease)
          throw new IllegalStateException(
            s"Refusing non-monotonic establishment refresh: candidate $candidate must be newer than current $currentRelease"
          )
        KnownCurrent(currentRelease)
      }
    }
  }

  def recordRejected(config: AtlasConfig, error: Throwable): Unit = {
    val startedAt = Instant.now()
    RunStatusRegistry.write(
      Paths.get(config.statusDir),
      status(config, ReleasePaths(config), startedAt, "failed", None, None, Some(error))
    )
  }

  def refresh(
      spark: SparkSession,
      config: AtlasConfig,
      allowLegacyCurrent: Boolean = false
  ): HistoryResult = {
    val startedAt = Instant.now()
    val releasePaths = ReleasePaths(config)
    val silverPaths = DatasetPaths.silverEstablishments(config)
    val candidatePaths = silverPaths.copy(output = releasePaths.silverCandidate.toString)
    var report: Option[SilverQualityReport] = None
    var result: Option[HistoryResult] = None
    var currentPublished = false
    try {
      validateAdvance(spark, config, allowLegacyCurrent)
      val prepared = SilverEstablishmentJob.prepare(spark.read.parquet(candidatePaths.input))
      val quality = SilverEstablishmentJob.validateAndPublish(prepared, candidatePaths) { data =>
        data.withColumn("release", lit(config.receita.snapshot))
          .withColumn("record_hash", recordHash)
          .write.mode("overwrite").partitionBy("state").parquet(candidatePaths.output)
      }
      report = Some(quality)

      val candidate = withComparisonMetadata(
        spark.read.parquet(candidatePaths.output),
        Some(config.receita.snapshot)
      ).persist(StorageLevel.DISK_ONLY)
      try {
        val priorExists = parquetExists(spark, releasePaths.silverCurrent.toString)
        val computed =
          if (priorExists) compareWithPrior(spark, config, spark.read.parquet(releasePaths.silverCurrent.toString), candidate, releasePaths)
          else seedSummary(spark, config, candidate, releasePaths)

        publishCurrent(spark, candidate, releasePaths.silverCurrent.toString)
        currentPublished = true
        result = Some(computed)
        RunStatusRegistry.write(
          Paths.get(config.statusDir),
          silverStatus(config, releasePaths, silverPaths, startedAt, quality, computed)
        )
        RunStatusRegistry.write(
          Paths.get(config.statusDir),
          status(config, releasePaths, startedAt, "success", report, Some(computed))
        )
        computed
      } finally candidate.unpersist()
    } catch {
      case error: Throwable =>
        if (!currentPublished) {
          try {
            deletePath(spark, releasePaths.historyRelease.toString)
            deletePath(spark, releasePaths.summaryRelease.toString)
          } catch { case cleanup: Throwable => error.addSuppressed(cleanup) }
        }
        try RunStatusRegistry.write(
          Paths.get(config.statusDir),
          status(config, releasePaths, startedAt, "failed", report, result, Some(error))
        )
        catch { case statusError: Throwable => error.addSuppressed(statusError) }
        throw error
    }
  }

  private[history] def compareWithPrior(
      spark: SparkSession,
      config: AtlasConfig,
      prior: DataFrame,
      candidate: DataFrame,
      paths: ReleasePaths
  ): HistoryResult = {
    val priorAlias = comparisonColumns(prior, None).as("old")
    val newAlias = comparisonColumns(candidate, Some(config.receita.snapshot)).as("new")
    val joined = priorAlias.join(newAlias, Seq("cnpj_full"), "full_outer")
    val changedFields = array(TrackedFields.map(fieldDelta): _*)
    val events = joined
      .withColumn(
        "change_type",
        when(col("old.record_hash").isNull, lit("inserted"))
          .when(col("new.record_hash").isNull, lit("removed"))
          .when(col("old.record_hash") =!= col("new.record_hash"), lit("updated"))
      )
      .filter(col("change_type").isNotNull)
      .withColumn("changed_fields_raw", changedFields)
      .withColumn("changed_fields", expr("filter(changed_fields_raw, x -> x.old_value is not null or x.new_value is not null)"))
      .select(
        sha2(concat_ws("|", lit("receita"), lit("estabelecimentos"), col("cnpj_full"), lit(config.receita.snapshot), col("change_type")), 256).as("event_id"),
        lit("receita").as("source"),
        lit("estabelecimentos").as("dataset"),
        col("cnpj_full"),
        col("old.release").as("from_release"),
        lit(config.receita.snapshot).as("to_release"),
        col("change_type"),
        col("changed_fields"),
        current_timestamp().as("detected_at")
      )

    val materialized = events.persist(StorageLevel.DISK_ONLY)
    try {
      val counts = materialized.groupBy("change_type").count().collect().map(row => row.getString(0) -> row.getLong(1)).toMap
      val total = counts.values.sum
      val previousCount = prior.count()
      val currentCount = candidate.count()
      val inserted = counts.getOrElse("inserted", 0L)
      val removed = counts.getOrElse("removed", 0L)
      if (currentCount - previousCount != inserted - removed)
        throw new IllegalStateException(
          s"Establishment summary invariant failed: $currentCount - $previousCount != $inserted - $removed"
        )
      if (total > 0) materialized.write.mode("overwrite").parquet(paths.historyRelease.toString)
      else deletePath(spark, paths.historyRelease.toString)
      writeSummary(
        spark, config, Some(prior), candidate, materialized,
        Some(previousCount), currentCount, inserted, counts.getOrElse("updated", 0L), removed, total, paths
      )
      HistoryResult(
        config.receita.snapshot,
        currentCount,
        Some(previousCount),
        inserted,
        counts.getOrElse("updated", 0L),
        counts.getOrElse("removed", 0L),
        total,
        paths.historyRelease.toString
      )
    } finally materialized.unpersist()
  }

  private def seedSummary(
      spark: SparkSession,
      config: AtlasConfig,
      candidate: DataFrame,
      paths: ReleasePaths
  ): HistoryResult = {
    val currentCount = candidate.count()
    val emptyEvents = spark.createDataFrame(
      spark.sparkContext.emptyRDD[org.apache.spark.sql.Row],
      org.apache.spark.sql.types.StructType(Seq(
        org.apache.spark.sql.types.StructField("change_type", org.apache.spark.sql.types.StringType),
        org.apache.spark.sql.types.StructField("changed_fields", org.apache.spark.sql.types.ArrayType(
          org.apache.spark.sql.types.StructType(Seq(
            org.apache.spark.sql.types.StructField("field_name", org.apache.spark.sql.types.StringType),
            org.apache.spark.sql.types.StructField("old_value", org.apache.spark.sql.types.StringType),
            org.apache.spark.sql.types.StructField("new_value", org.apache.spark.sql.types.StringType)
          ))
        ))
      ))
    )
    writeSummary(spark, config, None, candidate, emptyEvents, None, currentCount, 0L, 0L, 0L, 0L, paths)
    HistoryResult(config.receita.snapshot, currentCount, None, 0L, 0L, 0L, 0L, paths.historyRelease.toString)
  }

  private def writeSummary(
      spark: SparkSession,
      config: AtlasConfig,
      prior: Option[DataFrame],
      candidate: DataFrame,
      events: DataFrame,
      previousCount: Option[Long],
      currentCount: Long,
      inserted: Long,
      updated: Long,
      removed: Long,
      eventCount: Long,
      paths: ReleasePaths
  ): Unit = {
    import spark.implicits._
    val previousStates = prior.map(_.groupBy("state").count().withColumnRenamed("count", "previous_count"))
      .getOrElse(Seq.empty[(String, Long)].toDF("state", "previous_count"))
      .withColumn("state_key", coalesce(col("state"), lit("\u0000"))).drop("state")
    val currentStates = candidate.groupBy("state").count().withColumnRenamed("count", "current_count")
      .withColumn("state_key", coalesce(col("state"), lit("\u0000"))).drop("state")
    val stateCounts = previousStates.join(currentStates, Seq("state_key"), "full_outer")
      .select(
        when(col("state_key") === "\u0000", lit(null).cast("string")).otherwise(col("state_key")).as("state"),
        col("previous_count"), col("current_count"),
        (coalesce(col("current_count"), lit(0L)) - coalesce(col("previous_count"), lit(0L))).as("delta")
      )
      .orderBy(col("state").asc_nulls_first)
      .collect().toSeq.map { row =>
        val state = Option(row.getAs[String]("state"))
        val oldCount = Option(row.getAs[java.lang.Long]("previous_count")).map(_.longValue)
        val newCount = Option(row.getAs[java.lang.Long]("current_count")).map(_.longValue)
        StateReleaseCount(state, oldCount, newCount, row.getAs[Long]("delta"))
      }
    val changedFields =
      if (eventCount == 0L) Seq.empty[ChangedFieldCount]
      else events.filter(col("change_type") === "updated")
        .select(explode(col("changed_fields")).as("change"))
        .groupBy(col("change.field_name").as("field_name")).count()
        .orderBy("field_name").collect().toSeq.map(row => ChangedFieldCount(row.getString(0), row.getLong(1)))
    val fromRelease = prior.flatMap { data =>
      if (!data.columns.contains("release")) None
      else data.select("release").filter(col("release").isNotNull).distinct().limit(1).collect().headOption.map(_.getString(0))
    }
    Seq(EstablishmentReleaseSummary(
      summaryId(fromRelease, config.receita.snapshot), "receita", "estabelecimentos", fromRelease,
      config.receita.snapshot, java.sql.Timestamp.from(Instant.now()), "1", previousCount,
      currentCount, previousCount.map(currentCount - _),
      inserted, updated, removed, eventCount, stateCounts, changedFields
    )).toDF().write.mode("overwrite").parquet(paths.summaryRelease.toString)
  }

  private def summaryId(fromRelease: Option[String], toRelease: String): String = {
    val value = Seq("receita", "estabelecimentos", fromRelease.getOrElse("seed"), toRelease).mkString("|")
    java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .map("%02x".format(_)).mkString
  }

  private def fieldDelta(name: String): Column =
    when(to_json(struct(col(s"old.$name").as("value"))).eqNullSafe(to_json(struct(col(s"new.$name").as("value")))), lit(null).cast("struct<field_name:string,old_value:string,new_value:string>"))
      .otherwise(
        struct(
          lit(name).as("field_name"),
          to_json(struct(col(s"old.$name").as("value"))).as("old_value"),
          to_json(struct(col(s"new.$name").as("value"))).as("new_value")
        )
      )

  private def recordHash: Column =
    sha2(to_json(struct(TrackedFields.map(name => col(name).as(name)): _*)), 256)

  private def comparisonColumns(data: DataFrame, fallbackRelease: Option[String]): DataFrame = {
    val withMetadata = withComparisonMetadata(data, fallbackRelease)
    withMetadata.select((Seq(col("cnpj_full"), col("release"), col("record_hash")) ++ TrackedFields.map(col)): _*)
  }

  private def withComparisonMetadata(data: DataFrame, fallbackRelease: Option[String]): DataFrame = {
    val withRelease =
      if (data.columns.contains("release")) data
      else data.withColumn("release", fallbackRelease.map(lit).getOrElse(lit(null).cast("string")))
    if (withRelease.columns.contains("record_hash")) withRelease
    else withRelease.withColumn("record_hash", recordHash)
  }

  private def publishCurrent(spark: SparkSession, data: DataFrame, output: String): Unit = {
    val suffix = java.util.UUID.randomUUID().toString
    val target = new Path(output)
    val staging = new Path(s"$output.staging-$suffix")
    val backup = new Path(s"$output.backup-$suffix")
    val fs = target.getFileSystem(spark.sparkContext.hadoopConfiguration)
    data.write.mode("errorifexists").partitionBy("state").parquet(staging.toString)
    val hadTarget = fs.exists(target)
    try {
      if (hadTarget && !fs.rename(target, backup)) throw new IllegalStateException(s"Could not preserve existing current table at $output")
      if (!fs.rename(staging, target)) throw new IllegalStateException(s"Could not publish current table at $output")
      if (hadTarget) fs.delete(backup, true)
    } catch {
      case error: Throwable =>
        fs.delete(staging, true)
        if (hadTarget && fs.exists(backup) && !fs.exists(target)) fs.rename(backup, target)
        throw error
    }
  }

  private def parquetExists(spark: SparkSession, path: String): Boolean = {
    val fsPath = new Path(path)
    fsPath.getFileSystem(spark.sparkContext.hadoopConfiguration).exists(fsPath)
  }

  private def deletePath(spark: SparkSession, path: String): Unit = {
    val fsPath = new Path(path)
    fsPath.getFileSystem(spark.sparkContext.hadoopConfiguration).delete(fsPath, true)
  }

  private def status(
      config: AtlasConfig,
      paths: ReleasePaths,
      startedAt: Instant,
      runStatus: String,
      report: Option[SilverQualityReport],
      result: Option[HistoryResult],
      error: Option[Throwable] = None
  ): RunStatus = {
    val finishedAt = Instant.now()
    RunStatus(
      "receita", "estabelecimentos_history", config.receita.snapshot, "history", runStatus,
      startedAt, finishedAt, Duration.between(startedAt, finishedAt).toNanos / 1000000000.0,
      result.map(_.eventRowCount), Seq(paths.silverCandidate.toString), Some(paths.historyRelease.toString),
      Seq.empty, Some("1"), Some(config.spark.appName), Some("refresh-receita-estabelecimentos"),
      error.map(_.getClass.getName), error.flatMap(value => Option(value.getMessage)),
      report.map(_.validRowCount), result.map(_.currentRowCount), report.map(_.malformedRowCount),
      previousRowCount = result.flatMap(_.previousRowCount),
      netRowDelta = result.flatMap(r => r.previousRowCount.map(r.currentRowCount - _)),
      insertedRowCount = result.map(_.insertedCount), updatedRowCount = result.map(_.updatedCount),
      removedRowCount = result.map(_.removedCount)
    )
  }

  private def silverStatus(
      config: AtlasConfig,
      paths: ReleasePaths,
      silverPaths: DatasetPaths,
      startedAt: Instant,
      report: SilverQualityReport,
      result: HistoryResult
  ): RunStatus = {
    val finishedAt = Instant.now()
    val warnings = SilverEstablishmentJob.qualityWarnings(report, silverPaths)
    RunStatus(
      "receita", "establishments", config.receita.snapshot, "silver",
      if (warnings.isEmpty) "success" else "success_with_warnings",
      startedAt, finishedAt, Duration.between(startedAt, finishedAt).toNanos / 1000000000.0,
      Some(result.currentRowCount), Seq(paths.bronzeRelease.toString), Some(paths.silverCurrent.toString),
      Seq("state"), Some("1"), Some(config.spark.appName), Some("refresh-receita-estabelecimentos"),
      inputRowCount = Some(report.rowCount), outputRowCount = Some(result.currentRowCount),
      quarantinedRowCount = Some(report.malformedRowCount), qualityWarnings = warnings,
      previousRowCount = result.previousRowCount,
      netRowDelta = result.previousRowCount.map(result.currentRowCount - _),
      insertedRowCount = Some(result.insertedCount), updatedRowCount = Some(result.updatedCount),
      removedRowCount = Some(result.removedCount)
    )
  }
}
