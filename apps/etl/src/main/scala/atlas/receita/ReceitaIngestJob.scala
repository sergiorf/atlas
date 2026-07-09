package atlas.receita

import atlas.common.{CnpjUtils, DatasetPaths, JobResult, QualityChecks}
import atlas.config.AtlasConfig
import atlas.status.{RunStatus, RunStatusRegistry}
import java.nio.file.Paths
import java.time.{Duration, Instant}
import org.apache.spark.sql.{Column, DataFrame, SparkSession}
import org.apache.spark.sql.functions.{
  col,
  current_timestamp,
  input_file_name,
  length,
  lit,
  to_date,
  trim,
  when
}
import org.apache.spark.storage.StorageLevel

object ReceitaIngestJob {
  private val dateColumns = Set("registration_status_date", "opening_date", "special_status_date")
  def run(spark: SparkSession, config: AtlasConfig): JobResult = {
    val startedAt = Instant.now()
    val paths = DatasetPaths.estabelecimentos(config.receita)
    var rowCount: Option[Long] = None
    try {
      val raw = spark.read
        .schema(ReceitaSchemas.estabelecimentos)
        .option("header", "false")
        .option("sep", config.csv.delimiter)
        .option("encoding", config.csv.encoding)
        .option("quote", "\"")
        .option("escape", "\"")
        .option("mode", "PERMISSIVE")
        .csv(paths.input)
      val bronze = transform(raw).persist(StorageLevel.DISK_ONLY)
      try {
        val result = QualityChecks.evaluate(bronze, paths)
        rowCount = Some(result.rowCount)
        bronze.write.mode(config.writeMode).partitionBy("state").parquet(paths.output)
        QualityChecks.write(result, paths)
        RunStatusRegistry.write(
          Paths.get(config.statusDir),
          status(config, paths, startedAt, "success", rowCount)
        )
        result
      } finally bronze.unpersist()
    } catch {
      case error: Throwable =>
        try
          RunStatusRegistry.write(
            Paths.get(config.statusDir),
            status(config, paths, startedAt, "failed", rowCount, Some(error))
          )
        catch { case statusError: Throwable => error.addSuppressed(statusError) }
        throw error
    }
  }

  private def status(
      config: AtlasConfig,
      paths: DatasetPaths,
      startedAt: Instant,
      runStatus: String,
      rowCount: Option[Long],
      error: Option[Throwable] = None
  ): RunStatus = {
    val finishedAt = Instant.now()
    RunStatus(
      source = "receita",
      dataset = "estabelecimentos",
      snapshot = config.receita.snapshot,
      layer = "bronze",
      status = runStatus,
      startedAt = startedAt,
      finishedAt = finishedAt,
      durationSeconds = Duration.between(startedAt, finishedAt).toNanos / 1000000000.0,
      rowCount = rowCount,
      inputPaths = Seq(paths.input),
      outputPath = Some(paths.output),
      partitionColumns = Seq("state"),
      schemaVersion = Some("1"),
      applicationName = Some(config.spark.appName),
      jobName = Some("ingest-receita-estabelecimentos"),
      errorType = error.map(_.getClass.getName),
      errorMessage = error.flatMap(value => Option(value.getMessage))
    )
  }
  private[atlas] def transform(raw: DataFrame): DataFrame = {
    val cleaned = raw
      .select(ReceitaSchemas.estabelecimentoColumns.map { name =>
        if (dateColumns.contains(name)) receitaDate(col(name)).as(name)
        else nullableTrim(col(name)).as(name)
      }: _*)
      .withColumn("cnpj_root", CnpjUtils.normalizeRoot(col("cnpj_root")))
      .withColumn("cnpj_branch", CnpjUtils.normalizeBranch(col("cnpj_branch")))
      .withColumn("cnpj_check", CnpjUtils.normalizeCheck(col("cnpj_check")))
    cleaned
      .withColumn(
        "cnpj_full",
        CnpjUtils.buildFullCnpj(col("cnpj_root"), col("cnpj_branch"), col("cnpj_check"))
      )
      .withColumn("is_headquarters", col("headquarters_branch_code") === "1")
      .withColumn("source_name", lit("receita_cnpj_estabelecimentos"))
      .withColumn("source_file", input_file_name())
      .withColumn("ingestion_timestamp", current_timestamp())
  }
  private def nullableTrim(c: Column): Column =
    when(length(trim(c)) === 0, lit(null).cast("string")).otherwise(trim(c))
  private def receitaDate(c: Column): Column =
    when(nullableTrim(c).rlike("^[0-9]{8}$"), to_date(nullableTrim(c), "yyyyMMdd"))
      .otherwise(lit(null).cast("date"))
}
