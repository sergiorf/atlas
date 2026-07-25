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
