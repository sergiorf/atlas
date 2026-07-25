package atlas.release

import atlas.SparkSuite
import atlas.config.{AtlasConfig, CsvConfig, ReceitaConfig, SparkConfig}
import atlas.receita.CompanyDataPaths
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite

class CompanyGeographyCoverageTest extends AnyFunSuite with SparkSuite {
  test("writes actionable diagnostics for unresolved establishment municipality codes") {
    val root = Files.createTempDirectory("atlas-geography-coverage")
    val config = testConfig(root)
    val session = spark
    import session.implicits._
    val establishments = Seq(
      ("A", "1182", "MT"),
      ("B", "1182", "MT"),
      ("C", "7107", "SP")
    ).toDF("cnpj_full", "municipality_code", "state")
    val geography = Seq(
      ("7107", "SP", "current_tom")
    ).toDF("receita_municipality_code", "state_abbreviation", "mapping_source")

    val error = intercept[IllegalStateException] {
      CompanyBundleService.validateGeographyCoverage(establishments, geography, config)
    }

    assert(error.getMessage.contains("1 unresolved municipality code"))
    assert(error.getMessage.contains("\"municipality_code\":\"1182\""))
    assert(error.getMessage.contains("\"establishment_count\":2"))
    assert(Files.isDirectory(CompanyDataPaths.geographyCoverage(config)))
    assert(Files.readString(CompanyDataPaths.geographyCoverageSummary(config))
      .contains("\"unresolved_establishment_rows\":2"))
  }

  test("records establishment-state conflicts without rejecting the bundle candidate") {
    val root = Files.createTempDirectory("atlas-geography-state-conflict")
    val config = testConfig(root)
    val session = spark
    import session.implicits._
    val establishments = Seq(
      ("39868640000153", "6969", "PA")
    ).toDF("cnpj_full", "municipality_code", "state")
    val geography = Seq(
      ("6969", "SP", "current_tom")
    ).toDF("receita_municipality_code", "state_abbreviation", "mapping_source")

    CompanyBundleService.validateGeographyCoverage(establishments, geography, config)

    val diagnostic = spark.read.parquet(CompanyDataPaths.geographyCoverage(config).toString).head()
    assert(diagnostic.getAs[String]("coverage_status") === "state_conflict")
    assert(diagnostic.getAs[Long]("establishment_count") === 1L)
    val summary = Files.readString(CompanyDataPaths.geographyCoverageSummary(config))
    assert(summary.contains("\"state_conflict_codes\":1"))
    assert(summary.contains("\"state_conflict_establishment_rows\":1"))
  }

  private def testConfig(root: java.nio.file.Path): AtlasConfig = AtlasConfig(
    SparkConfig("local[1]", "test", 1, root.resolve("spark").toString),
    CsvConfig(";", "ISO-8859-1"),
    ReceitaConfig(
      "2026-07",
      root.resolve("raw/receita/2026-07/estabelecimentos/extracted").toString,
      root.resolve("bronze/receita").toString,
      root.resolve("silver/receita").toString,
      root.resolve("raw/receita/2026-07/company-data").toString
    ),
    root.resolve("_atlas/status").toString,
    "overwrite"
  )
}
