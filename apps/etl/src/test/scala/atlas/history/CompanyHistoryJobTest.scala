package atlas.history

import atlas.SparkSuite
import atlas.config.{AtlasConfig, CsvConfig, ReceitaConfig, SparkConfig}
import atlas.receita.CompanyDataPaths
import java.nio.file.Files
import org.apache.spark.sql.functions.col
import org.scalatest.funsuite.AnyFunSuite

class CompanyHistoryJobTest extends AnyFunSuite with SparkSuite {
  test("seeds May and records June company insert update and removal") {
    val root = Files.createTempDirectory("atlas-company-history")
    val may = config(root, "2026-05")
    writeCandidate(may, Seq(
      company("12345678", "ALPHA", "100.00", "2026-05"),
      company("87654321", "REMOVE", "20.00", "2026-05")
    ))
    val seed = CompanyHistoryJob.refresh(spark, may, "bundle-may")
    assert(seed.previousRows.isEmpty)
    assert(seed.eventCount === 0L)

    val june = config(root, "2026-06")
    writeCandidate(june, Seq(
      company("12345678", "ALPHA UPDATED", "100.00", "2026-06"),
      company("12ABC345", "INSERT", "30.00", "2026-06")
    ))
    val result = CompanyHistoryJob.refresh(spark, june, "bundle-june")
    assert(result.previousRows.contains(2L))
    assert(result.inserted === 1L)
    assert(result.updated === 1L)
    assert(result.removed === 1L)
    val events = spark.read.parquet(CompanyHistoryJob.eventRelease(june).toString)
    assert(events.select("change_type").distinct().collect().map(_.getString(0)).toSet === Set("inserted", "updated", "removed"))
    assert(events.filter(col("change_type") === "updated").head().getAs[Seq[_]]("changed_fields").nonEmpty)
  }

  private def writeCandidate(config: AtlasConfig, rows: Seq[CompanyRow]): Unit = {
    val session = spark
    import session.implicits._
    rows.toDF().write.mode("overwrite").parquet(CompanyDataPaths.silverCompanyCandidate(config).toString)
  }

  private def company(root: String, name: String, capital: String, release: String): CompanyRow =
    CompanyRow(root, name, "2062", "49", new java.math.BigDecimal(capital), "05", null, "source", new java.sql.Timestamp(0), new java.sql.Timestamp(0), release, name)

  private def config(root: java.nio.file.Path, release: String): AtlasConfig = AtlasConfig(
    SparkConfig("local[1]", "test", 1, root.resolve("spark").toString),
    CsvConfig(";", "UTF-8"),
    ReceitaConfig(
      release,
      root.resolve(s"raw/receita/$release/estabelecimentos/extracted").toString,
      root.resolve("bronze/receita").toString,
      root.resolve("silver/receita").toString,
      root.resolve(s"raw/receita/$release/company-data").toString
    ),
    root.resolve("_atlas/status").toString,
    "overwrite"
  )
}

final case class CompanyRow(
    cnpj_root: String,
    legal_name: String,
    legal_nature_code: String,
    responsible_qualification_code: String,
    share_capital: java.math.BigDecimal,
    company_size_code: String,
    responsible_federative_entity: String,
    source_file: String,
    ingestion_timestamp: java.sql.Timestamp,
    silver_transformation_timestamp: java.sql.Timestamp,
    release: String,
    record_hash: String
)
