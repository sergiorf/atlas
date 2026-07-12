package atlas.history

import atlas.common.DatasetPaths
import atlas.config.AtlasConfig
import atlas.receita.{SilverEstablishmentJob, SilverQualityReport}
import atlas.release.ReleasePaths
import atlas.status.{RunStatus, RunStatusRegistry}
import java.nio.file.Paths
import java.time.{Duration, Instant}
import org.apache.hadoop.fs.Path
import org.apache.spark.sql.{Column, DataFrame, SparkSession}
import org.apache.spark.sql.functions._

final case class HistoryResult(
    release: String,
    currentRowCount: Long,
    insertedCount: Long,
    updatedCount: Long,
    removedCount: Long,
    eventRowCount: Long,
    outputPath: String
)

object EstablishmentHistoryJob {
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

  def refresh(spark: SparkSession, config: AtlasConfig): HistoryResult = {
    val startedAt = Instant.now()
    val releasePaths = ReleasePaths(config)
    val silverPaths = DatasetPaths.silverEstablishments(config)
    val candidatePaths = silverPaths.copy(output = releasePaths.silverCandidate.toString)
    var report: Option[SilverQualityReport] = None
    var result: Option[HistoryResult] = None
    try {
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
      ).cache()
      try {
        val priorExists = parquetExists(spark, releasePaths.silverCurrent.toString)
        val computed =
          if (priorExists) compareWithPrior(spark, config, spark.read.parquet(releasePaths.silverCurrent.toString), candidate, releasePaths)
          else HistoryResult(config.receita.snapshot, candidate.count(), 0L, 0L, 0L, 0L, releasePaths.historyRelease.toString)

        publishCurrent(spark, candidate, releasePaths.silverCurrent.toString)
        result = Some(computed)
        RunStatusRegistry.write(
          Paths.get(config.statusDir),
          status(config, releasePaths, startedAt, "success", report, Some(computed))
        )
        computed
      } finally candidate.unpersist()
    } catch {
      case error: Throwable =>
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

    val materialized = events.cache()
    try {
      val counts = materialized.groupBy("change_type").count().collect().map(row => row.getString(0) -> row.getLong(1)).toMap
      val total = counts.values.sum
      if (total > 0) materialized.write.mode("overwrite").parquet(paths.historyRelease.toString)
      else deletePath(spark, paths.historyRelease.toString)
      HistoryResult(
        config.receita.snapshot,
        candidate.count(),
        counts.getOrElse("inserted", 0L),
        counts.getOrElse("updated", 0L),
        counts.getOrElse("removed", 0L),
        total,
        paths.historyRelease.toString
      )
    } finally materialized.unpersist()
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
      report.map(_.validRowCount), result.map(_.currentRowCount), report.map(_.malformedRowCount)
    )
  }
}
