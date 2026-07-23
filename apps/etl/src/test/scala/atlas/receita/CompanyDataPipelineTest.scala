package atlas.receita

import atlas.SparkSuite
import atlas.config.{AtlasConfig, CsvConfig, ReceitaConfig, SparkConfig}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import org.apache.spark.sql.DataFrame
import org.scalatest.funsuite.AnyFunSuite

class CompanyDataPipelineTest extends AnyFunSuite with SparkSuite {
  test("writes source-faithful company bronze and enriched silver candidate") {
    val root = Files.createTempDirectory("atlas-company-pipeline")
    val config = testConfig(root)
    val empresas = root.resolve("empresas.csv")
    Files.writeString(empresas,
      "12345678;ALPHA;2062;49;1234,56;05;\n12ABC345;BETA;2062;49;;01;\n",
      StandardCharsets.ISO_8859_1
    )
    val bronze = CompanyDataPipeline.writeBronzeCompanies(spark, config, Seq(empresas))
    assert(bronze.count() === 2L)
    assert(bronze.filter("cnpj_root = '12ABC345'").head().isNullAt(bronze.schema.fieldIndex("share_capital")))

    val references = Map(
      "legal_nature" -> reference(Seq("2062" -> "SOCIEDADE EMPRESARIA LIMITADA")),
      "partner_qualification" -> reference(Seq("49" -> "SOCIO-ADMINISTRADOR"))
    )
    val count = CompanyDataPipeline.writeSilverCompanies(spark, config, bronze, references)
    assert(count === 2L)
    val companies = spark.read.parquet(CompanyDataPaths.silverCompanyCandidate(config).toString)
    assert(companies.filter("cnpj_root = '12345678'").head().getAs[String]("legal_nature_description") === "SOCIEDADE EMPRESARIA LIMITADA")
    assert(companies.select("release").distinct().head().getString(0) === "2026-07")
  }

  test("publishes unknown optional reference codes with a quality diagnostic") {
    val root = Files.createTempDirectory("atlas-company-missing-reference")
    val config = testConfig(root)
    val empresas = root.resolve("empresas.csv")
    Files.writeString(empresas, "12345678;ALPHA;2062;36;1234,56;05;\n", StandardCharsets.ISO_8859_1)
    val bronze = CompanyDataPipeline.writeBronzeCompanies(spark, config, Seq(empresas))
    val references = Map(
      "legal_nature" -> reference(Seq("2062" -> "SOCIEDADE EMPRESARIA LIMITADA")),
      "partner_qualification" -> reference(Seq("35" -> "TUTOR", "37" -> "SOCIO NO EXTERIOR"))
    )

    assert(CompanyDataPipeline.writeSilverCompanies(spark, config, bronze, references) === 1L)
    val companies = spark.read.parquet(CompanyDataPaths.silverCompanyCandidate(config).toString)
    assert(companies.head().getAs[String]("responsible_qualification_code") === "36")
    assert(companies.head().isNullAt(companies.schema.fieldIndex("responsible_qualification_description")))
    val diagnostic = spark.read.parquet(
      CompanyDataPaths.qualityRoot(config).resolve("missing_reference_descriptions").toString
    ).head()
    assert(diagnostic.getAs[String]("cnpj_root") === "12345678")
    assert(diagnostic.getAs[String]("dimension") === "partner_qualification")
    assert(diagnostic.getAs[String]("code") === "36")
    assert(diagnostic.getAs[String]("release") === "2026-07")
  }

  test("builds exact TOM to IBGE geography with pinned hashes") {
    val root = Files.createTempDirectory("atlas-company-geography")
    val config = testConfig(root)
    val tom = root.resolve("tom.csv")
    Files.writeString(tom,
      "CÓDIGO DO MUNICÍPIO - TOM;CÓDIGO DO MUNICÍPIO - IBGE;MUNICÍPIO - TOM;MUNICÍPIO - IBGE;UF\n" +
        "7107;3550308;SAO PAULO;São Paulo;SP\n" +
        "9707;0;EXTERIOR;;EX\n",
      StandardCharsets.ISO_8859_1
    )
    val ibge = root.resolve("ibge.json")
    Files.writeString(ibge,
      """[{"id":"3550308","nome":"São Paulo","regiao-imediata":{"id":"350001","nome":"São Paulo","regiao-intermediaria":{"id":"3501","nome":"São Paulo","UF":{"id":"35","sigla":"SP","nome":"São Paulo","regiao":{"id":"3","sigla":"SE","nome":"Sudeste"}}}}}]""",
      StandardCharsets.UTF_8
    )
    val manifest = CompanyDataManifest("2026-07", root.resolve("manifest.json"), "manifest", Map.empty, tom, "tom-hash", ibge, "ibge-hash", None)
    val geography = CompanyDataPipeline.writeGeography(spark, config, manifest)
    assert(geography.count() === 2L)
    val row = geography.filter("receita_municipality_code = '7107'").head()
    assert(row.getAs[String]("ibge_municipality_code") === "3550308")
    assert(row.getAs[String]("state_abbreviation") === "SP")
    assert(!row.getAs[Boolean]("is_exterior"))
    assert(row.getAs[String]("tom_source_hash") === "tom-hash")
    val exterior = geography.filter("receita_municipality_code = '9707'").head()
    assert(exterior.getAs[Boolean]("is_exterior"))
    assert(exterior.getAs[String]("ibge_municipality_code") === "0")
    assert(exterior.getAs[String]("state_abbreviation") === "EX")
    assert(exterior.isNullAt(exterior.fieldIndex("state_code")))
    assert(exterior.isNullAt(exterior.fieldIndex("ibge_municipality_name")))
  }

  test("rejects unmatched TOM municipalities other than the official exterior sentinel") {
    val root = Files.createTempDirectory("atlas-company-geography-unmatched")
    val config = testConfig(root)
    val tom = root.resolve("tom.csv")
    Files.writeString(tom, "9999;0;UNKNOWN;;EX\n", StandardCharsets.ISO_8859_1)
    val ibge = root.resolve("ibge.json")
    Files.writeString(ibge,
      """[{"id":"3550308","nome":"São Paulo","regiao-imediata":{"id":"350001","nome":"São Paulo","regiao-intermediaria":{"id":"3501","nome":"São Paulo","UF":{"id":"35","sigla":"SP","nome":"São Paulo","regiao":{"id":"3","sigla":"SE","nome":"Sudeste"}}}}}]""",
      StandardCharsets.UTF_8
    )
    val manifest = CompanyDataManifest("2026-07", root.resolve("manifest.json"), "manifest", Map.empty, tom, "tom-hash", ibge, "ibge-hash", None)
    val error = intercept[IllegalStateException] {
      CompanyDataPipeline.writeGeography(spark, config, manifest)
    }
    assert(error.getMessage === "TOM-to-IBGE geography contains unmatched or parentless municipalities")
  }

  private def reference(rows: Seq[(String, String)]): DataFrame = {
    val session = spark
    import session.implicits._
    rows.toDF("code", "description")
  }

  private def testConfig(root: Path): AtlasConfig = AtlasConfig(
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
