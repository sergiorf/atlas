package atlas.export

import atlas.SparkSuite
import atlas.config.{AtlasConfig, CsvConfig, ReceitaConfig, SparkConfig}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import org.scalatest.funsuite.AnyFunSuite

class LeadExportServiceTest extends AnyFunSuite with SparkSuite {
  test("exports a bounded current-gold projection and writes lineage manifest") {
    val session = spark
    import session.implicits._
    val root = Files.createTempDirectory("atlas-lead-export")
    val config = testConfig(root)
    val generation = root.resolve("data/_atlas/bundles/generations/bundle-1")
    val leads = generation.resolve("data/gold/receita/leads_new_companies_current")
    Seq(
      ("12345678000109", "software_services", "PE", "2531", "2026-07-10"),
      ("12345678000280", "software_services", "SP", "7107", "2026-07-11"),
      ("12345678000361", "restaurants", "PE", "2531", "2026-07-12")
    ).toDF("cnpj_full", "business_group", "state_abbreviation",
      "receita_municipality_code", "opening_date")
      .withColumn("opening_date", org.apache.spark.sql.functions.col("opening_date").cast("date"))
      .write.parquet(leads.toString)
    Files.createDirectories(generation)
    Files.writeString(generation.resolve("bundle-manifest.json"),
      """{"manifest_version":1,"bundle_id":"bundle-1","release":"2026-07","components":[{"name":"gold_leads_new_companies","path":"data/gold/receita/leads_new_companies_current","sha256":"fixture"}]}""")
    val bundleRoot = root.resolve("data/_atlas/bundles")
    Files.writeString(bundleRoot.resolve("current_bundle.json"),
      """{"bundle_id":"bundle-1","release":"2026-07"}""")

    val output = root.resolve("exports/leads")
    val result = LeadExportService.run(spark, config, LeadExportRequest(
      "software_services", Some("PE"), Some("2531"), Some("2026-07-01"),
      Some("2026-08-01"), "csv", output, 10, force = false
    ))

    assert(result.rowCount === 1L)
    assert(Files.isDirectory(output))
    val manifest = Files.readString(result.manifest, StandardCharsets.UTF_8)
    assert(manifest.contains("\"row_count\":1"))
    assert(manifest.contains("\"group\":\"software_services\""))
    intercept[IllegalArgumentException] {
      LeadExportService.run(spark, config, LeadExportRequest(
        "software_services", None, None, None, None, "csv", output, 10, force = false
      ))
    }
  }

  private def testConfig(root: Path): AtlasConfig =
    AtlasConfig(
      SparkConfig("local[1]", "atlas-test", 1, root.resolve("spark").toString),
      CsvConfig(";", "ISO-8859-1"),
      ReceitaConfig("2026-07", root.resolve("data/raw/receita/2026-07/estabelecimentos/extracted").toString,
        root.resolve("data/bronze/receita").toString, root.resolve("data/silver/receita").toString,
        root.resolve("data/raw/receita/2026-07/company-data").toString),
      root.resolve("data/_atlas/status").toString,
      "overwrite"
    )
}
