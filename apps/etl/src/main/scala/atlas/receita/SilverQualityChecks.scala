package atlas.receita

import atlas.common.DatasetPaths
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.time.Instant
import org.apache.spark.sql.{Column, DataFrame}
import org.apache.spark.sql.functions.{coalesce, col, count, lit, sum, when}

object SilverQualityChecks {
  def evaluate(data: DataFrame, paths: DatasetPaths): SilverQualityReport =
    evaluate(data, data.filter(col("_malformed_reason").isNull), paths)

  def evaluate(data: DataFrame, valid: DataFrame, paths: DatasetPaths): SilverQualityReport = {
    val metrics = data
      .agg(
        count("*").as("row_count"),
        sumLong(col("_malformed_reason").isNull).as("valid_row_count"),
        sumLong(col("_malformed_reason").isNotNull).as("malformed_row_count"),
        sumLong(col("cnpj_full").isNull || !col("cnpj_full").rlike("^[0-9A-Z]{12}[0-9]{2}$"))
          .as("invalid_cnpj_count"),
        sumLong(col("opening_date").isNull).as("null_opening_date_count"),
        sumLong(col("_invalid_main_cnae")).as("invalid_main_cnae_count"),
        coalesce(sum(col("_malformed_secondary_cnae_count")), lit(0L))
          .as("malformed_secondary_cnae_token_count"),
        sumLong(col("_invalid_state")).as("invalid_state_count"),
        sumLong(col("municipality_code").isNull).as("null_municipality_code_count")
      )
      .head()

    val duplicates = valid
      .filter(col("cnpj_full").isNotNull)
      .groupBy("cnpj_full")
      .count()
      .filter(col("count") > 1)
      .agg(
        count("*").as("duplicate_key_count"),
        coalesce(sum(col("count")), lit(0L)).as("duplicate_row_count")
      )
      .head()

    val invalidCnpjCount = metrics.getAs[Long]("invalid_cnpj_count")
    val alphanumericCnpjCount = valid.filter(col("cnpj_full").rlike("[A-Z]")).select("cnpj_full").count()
    val duplicateKeyCount = duplicates.getAs[Long]("duplicate_key_count")
    SilverQualityReport(
      "silver_establishment",
      paths.input,
      paths.output,
      metrics.getAs[Long]("row_count"),
      metrics.getAs[Long]("valid_row_count"),
      metrics.getAs[Long]("malformed_row_count"),
      invalidCnpjCount,
      alphanumericCnpjCount,
      duplicateKeyCount,
      duplicates.getAs[Long]("duplicate_row_count"),
      metrics.getAs[Long]("null_opening_date_count"),
      metrics.getAs[Long]("invalid_main_cnae_count"),
      metrics.getAs[Long]("malformed_secondary_cnae_token_count"),
      metrics.getAs[Long]("invalid_state_count"),
      metrics.getAs[Long]("null_municipality_code_count"),
      accepted = duplicateKeyCount == 0,
      Instant.now()
    )
  }

  def write(report: SilverQualityReport, paths: DatasetPaths): Unit = {
    writeFile(paths.qualityJson, json(report))
    writeFile(paths.qualityMarkdown, markdown(report))
  }

  private def sumLong(condition: Column): Column =
    coalesce(sum(when(condition, 1L).otherwise(0L)), lit(0L))

  private def writeFile(path: String, content: String): Unit = {
    val target = Paths.get(path)
    Option(target.getParent).foreach(parent => Files.createDirectories(parent))
    Files.write(target, content.getBytes(StandardCharsets.UTF_8))
  }

  private def json(r: SilverQualityReport): String = s"""{
    |  "dataset_name": "${r.datasetName}",
    |  "input_path": "${escape(r.inputPath)}",
    |  "output_path": "${escape(r.outputPath)}",
    |  "status": "${if (r.accepted) "accepted" else "rejected"}",
    |  "row_count": ${r.rowCount},
    |  "valid_row_count": ${r.validRowCount},
    |  "malformed_row_count": ${r.malformedRowCount},
    |  "invalid_cnpj_count": ${r.invalidCnpjCount},
    |  "alphanumeric_cnpj_count": ${r.alphanumericCnpjCount},
    |  "duplicate_key_count": ${r.duplicateKeyCount},
    |  "duplicate_row_count": ${r.duplicateRowCount},
    |  "null_opening_date_count": ${r.nullOpeningDateCount},
    |  "invalid_main_cnae_count": ${r.invalidMainCnaeCount},
    |  "malformed_secondary_cnae_token_count": ${r.malformedSecondaryCnaeTokenCount},
    |  "invalid_state_count": ${r.invalidStateCount},
    |  "null_municipality_code_count": ${r.nullMunicipalityCodeCount},
    |  "run_timestamp": "${r.runTimestamp}"
    |}
    |""".stripMargin

  private def markdown(r: SilverQualityReport): String = s"""# Silver establishment quality report
    |
    |Status: **${if (r.accepted) "accepted" else "rejected"}**
    |
    || Metric | Value |
    || --- | ---: |
    || Rows | ${r.rowCount} |
    || Valid rows | ${r.validRowCount} |
    || Quarantined malformed rows | ${r.malformedRowCount} |
    || Invalid CNPJ | ${r.invalidCnpjCount} |
    || Valid alphanumeric CNPJs | ${r.alphanumericCnpjCount} |
    || Duplicate keys | ${r.duplicateKeyCount} |
    || Rows with duplicate keys | ${r.duplicateRowCount} |
    || Null opening date | ${r.nullOpeningDateCount} |
    || Null or invalid primary CNAE | ${r.invalidMainCnaeCount} |
    || Malformed secondary CNAE tokens | ${r.malformedSecondaryCnaeTokenCount} |
    || Invalid state | ${r.invalidStateCount} |
    || Null municipality code | ${r.nullMunicipalityCodeCount} |
    |
    |Input: `${r.inputPath}`  
    |Output: `${r.outputPath}`  
    |Run timestamp: `${r.runTimestamp}`
    |""".stripMargin

  private def escape(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")
}
