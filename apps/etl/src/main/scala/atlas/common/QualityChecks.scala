package atlas.common

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.{coalesce, col, count, length, lit, sum, when}

object QualityChecks {
  def evaluate(data: DataFrame, paths: DatasetPaths): JobResult = {
    val row = data
      .agg(
        count("*").as("row_count"),
        coalesce(
          sum(when(col("cnpj_full").isNull || length(col("cnpj_full")) =!= 14, 1L).otherwise(0L)),
          lit(0L)
        ).as("invalid_cnpj_length_count"),
        coalesce(sum(when(col("cnpj_root").isNull, 1L).otherwise(0L)), lit(0L))
          .as("null_cnpj_root_count"),
        coalesce(sum(when(col("opening_date").isNull, 1L).otherwise(0L)), lit(0L))
          .as("null_opening_date_count"),
        coalesce(sum(when(col("main_cnae").isNull, 1L).otherwise(0L)), lit(0L))
          .as("null_main_cnae_count")
      )
      .head()

    JobResult(
      "receita_estabelecimentos",
      paths.input,
      paths.output,
      row.getLong(0),
      row.getLong(1),
      row.getLong(2),
      row.getLong(3),
      row.getLong(4),
      java.time.Instant.now()
    )
  }

  def write(result: JobResult, paths: DatasetPaths): Unit = {
    writeFile(paths.qualityJson, json(result))
    writeFile(paths.qualityMarkdown, markdown(result))
  }

  private def writeFile(path: String, content: String): Unit = {
    val target = Paths.get(path)
    Option(target.getParent).foreach(parent => Files.createDirectories(parent))
    Files.write(target, content.getBytes(StandardCharsets.UTF_8))
  }

  private def json(r: JobResult): String = s"""{
    |  "dataset_name": "${r.datasetName}",
    |  "input_path": "${r.inputPath.replace("\\", "\\\\")}",
    |  "output_path": "${r.outputPath.replace("\\", "\\\\")}",
    |  "row_count": ${r.rowCount},
    |  "invalid_cnpj_length_count": ${r.invalidCnpjLengthCount},
    |  "null_cnpj_root_count": ${r.nullCnpjRootCount},
    |  "null_opening_date_count": ${r.nullOpeningDateCount},
    |  "null_main_cnae_count": ${r.nullMainCnaeCount},
    |  "run_timestamp": "${r.runTimestamp}"
    |}
    |""".stripMargin

  private def markdown(r: JobResult): String = s"""# Receita estabelecimentos quality report
    |
    || Metric | Value |
    || --- | ---: |
    || Rows | ${r.rowCount} |
    || Invalid CNPJ length | ${r.invalidCnpjLengthCount} |
    || Null CNPJ root | ${r.nullCnpjRootCount} |
    || Null opening date | ${r.nullOpeningDateCount} |
    || Null main CNAE | ${r.nullMainCnaeCount} |
    |
    |Input: `${r.inputPath}`  
    |Output: `${r.outputPath}`  
    |Run timestamp: `${r.runTimestamp}`
    |""".stripMargin
}
