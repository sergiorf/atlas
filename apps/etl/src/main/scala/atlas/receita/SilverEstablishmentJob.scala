package atlas.receita

import atlas.common.DatasetPaths
import atlas.config.AtlasConfig
import atlas.status.{QualityWarning, RunStatus, RunStatusRegistry}
import java.nio.file.Paths
import java.time.{Duration, Instant}
import org.apache.spark.sql.{Column, DataFrame, SparkSession}
import org.apache.spark.sql.functions.{col, concat_ws, current_timestamp, expr, length, lit, lower, lpad, regexp_replace, trim, upper, when}
import org.apache.spark.sql.types.{ArrayType, StringType}
import org.apache.spark.storage.StorageLevel

object SilverEstablishmentJob {
  private val diagnosticColumns = Seq(
    "_invalid_main_cnae",
    "_malformed_secondary_cnae_count",
    "_invalid_state",
    "_malformed_reason"
  )

  private val validStates = Seq(
    "AC", "AL", "AP", "AM", "BA", "CE", "DF", "ES", "GO", "MA", "MT", "MS", "MG",
    "PA", "PB", "PR", "PE", "PI", "RJ", "RN", "RS", "RO", "RR", "SC", "SP", "SE", "TO"
  )
  private val validRegistrationStatuses = Seq("01", "02", "03", "04", "08")

  def run(spark: SparkSession, config: AtlasConfig): SilverQualityReport = {
    val startedAt = Instant.now()
    val paths = DatasetPaths.silverEstablishments(config)
    var observedReport: Option[SilverQualityReport] = None
    var cached: Option[DataFrame] = None
    try {
      val prepared = prepare(spark.read.parquet(paths.input)).persist(StorageLevel.DISK_ONLY)
      cached = Some(prepared)
      val report = validateAndPublish(prepared, paths, value => observedReport = Some(value)) { data =>
        publishAtomically(data, paths.output)
      }
      val warnings = qualityWarnings(report, paths)
      RunStatusRegistry.write(
        Paths.get(config.statusDir),
        status(config, paths, startedAt, if (warnings.isEmpty) "success" else "success_with_warnings", Some(report), warnings)
      )
      report
    } catch {
      case error: Throwable =>
        try RunStatusRegistry.write(
          Paths.get(config.statusDir),
          status(config, paths, startedAt, "failed", observedReport, observedReport.toSeq.flatMap(qualityWarnings(_, paths)), Some(error))
        )
        catch { case statusError: Throwable => error.addSuppressed(statusError) }
        throw error
    } finally cached.foreach(_.unpersist())
  }

  private[atlas] def validateAndPublish(
      prepared: DataFrame,
      paths: DatasetPaths,
      observe: SilverQualityReport => Unit = _ => ()
  )(publish: DataFrame => Unit): SilverQualityReport = {
    val malformed = prepared.filter(col("_malformed_reason").isNotNull)
    val valid = prepared.filter(col("_malformed_reason").isNull)
    val report = SilverQualityChecks.evaluate(prepared, valid, paths)
    observe(report)
    SilverQualityChecks.write(report, paths)
    if (report.malformedRowCount > 0) {
      malformed
        .withColumnRenamed("_malformed_reason", "malformed_reason")
        .drop("_invalid_main_cnae", "_malformed_secondary_cnae_count", "_invalid_state")
        .write.mode("overwrite").parquet(paths.malformedRows)
      println(s"WARNING: quarantined ${report.malformedRowCount} malformed rows at ${paths.malformedRows}")
    } else deleteGeneratedPath(prepared, paths.malformedRows)
    if (!report.accepted) {
      valid
        .join(
          valid.groupBy("cnpj_full").count().filter(col("count") > 1).select("cnpj_full"),
          Seq("cnpj_full"),
          "inner"
        )
        .transform(published)
        .write.mode("overwrite").parquet(paths.duplicateCnpjFull)
      throw new IllegalStateException(
        s"Silver quality gate rejected ${report.duplicateKeyCount} duplicate valid CNPJ keys; " +
          s"report: ${paths.duplicateCnpjFull}; existing output was not replaced"
      )
    }
    deleteGeneratedPath(prepared, paths.duplicateCnpjFull)
    publish(published(valid))
    report
  }

  private[atlas] def transform(bronze: DataFrame): DataFrame = published(prepare(bronze))

  private[atlas] def prepare(bronze: DataFrame): DataFrame = {
    val normalizedState = upper(nullableTrim(col("state")))
    val normalizedMainCnae = fixedDigits(col("main_cnae"), 7)
    val cleanedRoot = nullableTrim(col("cnpj_root"))
    val cleanedBranch = nullableTrim(col("cnpj_branch"))
    val cleanedCheck = nullableTrim(col("cnpj_check"))
    val cleanedFull = nullableTrim(col("cnpj_full"))
    val cleanedStatus = nullableTrim(col("registration_status_code"))
    val validSecondary = expr(
      "array_distinct(filter(transform(split(coalesce(secondary_cnaes, ''), ','), token -> trim(token)), " +
        "token -> token rlike '^[0-9]{7}$'))"
    )
    val malformedSecondaryCount = expr(
      "cast(size(filter(transform(split(coalesce(secondary_cnaes, ''), ','), token -> trim(token)), " +
        "token -> token <> '' and not(token rlike '^[0-9]{7}$'))) as long)"
    )

    bronze.select(
      cleanedRoot.as("cnpj_root"),
      cleanedBranch.as("cnpj_branch"),
      cleanedCheck.as("cnpj_check"),
      cleanedFull.as("cnpj_full"),
      col("is_headquarters").cast("boolean").as("is_headquarters"),
      nullableTrim(col("trade_name")).as("trade_name"),
      cleanedStatus.as("registration_status_code"),
      (cleanedStatus === "02").as("is_active"),
      col("registration_status_date").cast("date").as("registration_status_date"),
      nullableTrim(col("registration_status_reason")).as("registration_status_reason"),
      col("opening_date").cast("date").as("opening_date"),
      normalizedMainCnae.as("main_cnae"),
      when(nullableTrim(col("secondary_cnaes")).isNull, lit(null).cast(ArrayType(StringType)))
        .otherwise(validSecondary)
        .as("secondary_cnaes"),
      nullableTrim(col("street_type")).as("street_type"),
      nullableTrim(col("street_name")).as("street_name"),
      nullableTrim(col("street_number")).as("street_number"),
      nullableTrim(col("address_extra")).as("address_extra"),
      nullableTrim(col("neighborhood")).as("neighborhood"),
      fixedDigitsAfterCleanup(col("postal_code"), 8).as("postal_code"),
      when(normalizedState.isin(validStates: _*), normalizedState)
        .otherwise(lit(null).cast("string"))
        .as("state"),
      nullableTrim(col("municipality_code")).as("municipality_code"),
      nullableTrim(col("country_code")).as("country_code"),
      nullableTrim(col("foreign_city_name")).as("foreign_city_name"),
      digitsOnly(col("ddd_1")).as("phone_1_area_code"),
      digitsOnly(col("phone_1")).as("phone_1_number"),
      digitsOnly(col("ddd_2")).as("phone_2_area_code"),
      digitsOnly(col("phone_2")).as("phone_2_number"),
      digitsOnly(col("fax_ddd")).as("fax_area_code"),
      digitsOnly(col("fax")).as("fax_number"),
      lower(nullableTrim(col("email"))).as("email"),
      nullableTrim(col("special_status")).as("special_status"),
      col("special_status_date").cast("date").as("special_status_date"),
      nullableTrim(col("source_name")).as("source_name"),
      nullableTrim(col("source_file")).as("source_file"),
      col("ingestion_timestamp").cast("timestamp").as("ingestion_timestamp"),
      current_timestamp().as("silver_transformation_timestamp"),
      (nullableTrim(col("main_cnae")).isNull || normalizedMainCnae.isNull)
        .as("_invalid_main_cnae"),
      malformedSecondaryCount.as("_malformed_secondary_cnae_count"),
      (nullableTrim(col("state")).isNotNull && !normalizedState.isin(validStates: _*))
        .as("_invalid_state"),
      concat_ws(
        "; ",
        when(cleanedRoot.isNull || !cleanedRoot.rlike("^[0-9]{8}$"), lit("cnpj_root must be exactly 8 digits")),
        when(cleanedBranch.isNull || !cleanedBranch.rlike("^[0-9]{4}$"), lit("cnpj_branch must be exactly 4 digits")),
        when(cleanedCheck.isNull || !cleanedCheck.rlike("^[0-9]{2}$"), lit("cnpj_check must be exactly 2 digits")),
        when(cleanedFull.isNull || !cleanedFull.rlike("^[0-9]{14}$"), lit("cnpj_full must be exactly 14 digits")),
        when(cleanedStatus.isNull || !cleanedStatus.isin(validRegistrationStatuses: _*), lit("invalid registration_status_code"))
      ).as("_malformed_reason")
    )
      .withColumn(
        "_malformed_reason",
        when(length(col("_malformed_reason")) === 0, lit(null).cast("string"))
          .otherwise(col("_malformed_reason"))
      )
  }

  private def qualityWarnings(report: SilverQualityReport, paths: DatasetPaths): Seq[QualityWarning] = {
    val malformed = if (report.malformedRowCount > 0)
      Seq(QualityWarning("malformed_rows", report.malformedRowCount, "Structural validation failed", paths.malformedRows))
    else Seq.empty
    val duplicates = if (report.duplicateRowCount > 0)
      Seq(QualityWarning("duplicate_cnpj_full", report.duplicateRowCount, "Conflicting valid CNPJ keys", paths.duplicateCnpjFull))
    else Seq.empty
    malformed ++ duplicates
  }

  private def status(
      config: AtlasConfig,
      paths: DatasetPaths,
      startedAt: Instant,
      runStatus: String,
      report: Option[SilverQualityReport],
      warnings: Seq[QualityWarning],
      error: Option[Throwable] = None
  ): RunStatus = {
    val finishedAt = Instant.now()
    RunStatus(
      "receita", "establishments", config.receita.snapshot, "silver", runStatus,
      startedAt, finishedAt, Duration.between(startedAt, finishedAt).toNanos / 1000000000.0,
      report.map(_.validRowCount), Seq(paths.input), Some(paths.output), Seq("state"), Some("1"),
      Some(config.spark.appName), Some("normalize-receita-estabelecimentos"),
      error.map(_.getClass.getName), error.flatMap(value => Option(value.getMessage)),
      report.map(_.rowCount), report.map(_.validRowCount), report.map(_.malformedRowCount), warnings
    )
  }

  private def published(data: DataFrame): DataFrame = data.drop(diagnosticColumns: _*)

  private def deleteGeneratedPath(data: DataFrame, path: String): Unit = {
    if (path.nonEmpty) {
      val target = new org.apache.hadoop.fs.Path(path)
      target.getFileSystem(data.sparkSession.sparkContext.hadoopConfiguration).delete(target, true)
    }
  }

  private def publishAtomically(data: DataFrame, output: String): Unit = {
    val suffix = java.util.UUID.randomUUID().toString
    val target = new org.apache.hadoop.fs.Path(output)
    val staging = new org.apache.hadoop.fs.Path(s"$output.staging-$suffix")
    val backup = new org.apache.hadoop.fs.Path(s"$output.backup-$suffix")
    val fs = target.getFileSystem(data.sparkSession.sparkContext.hadoopConfiguration)
    data.write.mode("errorifexists").partitionBy("state").parquet(staging.toString)
    val hadTarget = fs.exists(target)
    try {
      if (hadTarget && !fs.rename(target, backup))
        throw new IllegalStateException(s"Could not preserve existing silver output at $output")
      if (!fs.rename(staging, target))
        throw new IllegalStateException(s"Could not publish staged silver output at $output")
      if (hadTarget) fs.delete(backup, true)
    } catch {
      case error: Throwable =>
        fs.delete(staging, true)
        if (hadTarget && fs.exists(backup) && !fs.exists(target)) fs.rename(backup, target)
        throw error
    }
  }

  private def nullableTrim(value: Column): Column =
    when(length(trim(value)) === 0, lit(null).cast("string")).otherwise(trim(value))

  private def fixedDigits(value: Column, width: Int): Column = {
    val cleaned = nullableTrim(value)
    when(cleaned.rlike(s"^[0-9]{$width}$$"), cleaned).otherwise(lit(null).cast("string"))
  }

  private def digitsOnly(value: Column): Column = {
    val cleaned = regexp_replace(nullableTrim(value), "[^0-9]", "")
    when(length(cleaned) === 0, lit(null).cast("string")).otherwise(cleaned)
  }

  private def fixedDigitsAfterCleanup(value: Column, width: Int): Column = {
    val cleaned = digitsOnly(value)
    when(length(cleaned) <= width, lpad(cleaned, width, "0"))
      .otherwise(lit(null).cast("string"))
  }
}
