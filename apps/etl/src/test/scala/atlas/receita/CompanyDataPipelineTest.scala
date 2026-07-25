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

  test("quarantines every row for duplicate company roots and publishes unaffected companies") {
    val root = Files.createTempDirectory("atlas-company-duplicates")
    val config = testConfig(root)
    val empresas = root.resolve("empresas.csv")
    Files.writeString(empresas,
      "08314885;FLAVIO PAVAO DE SOUZA;4120;59;0,00;05;\n" +
        "08314885;;0000;00;0,00;;\n" +
        "12345678;ALPHA;2062;49;1234,56;05;\n",
      StandardCharsets.ISO_8859_1)
    val bronze = CompanyDataPipeline.writeBronzeCompanies(spark, config, Seq(empresas))
    val references = Map(
      "legal_nature" -> reference(Seq("2062" -> "SOCIEDADE EMPRESARIA LIMITADA")),
      "partner_qualification" -> reference(Seq("49" -> "SOCIO-ADMINISTRADOR"))
    )

    val metrics = CompanyDataPipeline.writeSilverCompaniesWithMetrics(spark, config, bronze, references)
    val companies = spark.read.parquet(CompanyDataPaths.silverCompanyCandidate(config).toString)
    val duplicates = spark.read.parquet(
      CompanyDataPaths.qualityRoot(config).resolve("duplicate_companies").toString)

    assert(metrics.inputRowCount === 3L)
    assert(metrics.rowCount === 1L)
    assert(metrics.duplicateRowCount === 2L)
    assert(metrics.duplicateKeyCount === 1L)
    assert(metrics.quarantinedRowCount === 2L)
    assert(companies.select("cnpj_root").collect().map(_.getString(0)).toSeq === Seq("12345678"))
    assert(duplicates.count() === 2L)
    assert(duplicates.select("cnpj_root").distinct().head().getString(0) === "08314885")
    assert(duplicates.select("duplicate_group_size").distinct().head().getLong(0) === 2L)
    assert(duplicates.select("duplicate_business_variant_count").distinct().head().getLong(0) === 2L)
    assert(duplicates.select("quality_reason").distinct().head().getString(0) === "duplicate_cnpj_root")
  }

  test("quarantines identical duplicate company rows without selecting a survivor") {
    val root = Files.createTempDirectory("atlas-company-identical-duplicates")
    val config = testConfig(root)
    val empresas = root.resolve("empresas.csv")
    val row = "12345678;ALPHA;2062;49;1234,56;05;\n"
    Files.writeString(empresas, row + row, StandardCharsets.ISO_8859_1)
    val bronze = CompanyDataPipeline.writeBronzeCompanies(spark, config, Seq(empresas))
    val references = Map(
      "legal_nature" -> reference(Seq("2062" -> "SOCIEDADE EMPRESARIA LIMITADA")),
      "partner_qualification" -> reference(Seq("49" -> "SOCIO-ADMINISTRADOR"))
    )

    val metrics = CompanyDataPipeline.writeSilverCompaniesWithMetrics(spark, config, bronze, references)
    val companies = spark.read.parquet(CompanyDataPaths.silverCompanyCandidate(config).toString)
    val duplicates = spark.read.parquet(
      CompanyDataPaths.qualityRoot(config).resolve("duplicate_companies").toString)

    assert(metrics.rowCount === 0L)
    assert(metrics.duplicateRowCount === 2L)
    assert(metrics.duplicateKeyCount === 1L)
    assert(companies.count() === 0L)
    assert(duplicates.select("duplicate_business_variant_count").distinct().head().getLong(0) === 1L)
  }

  test("counts malformed rows and multiple duplicate groups without overlap") {
    val root = Files.createTempDirectory("atlas-company-quality-counts")
    val config = testConfig(root)
    val empresas = root.resolve("empresas.csv")
    Files.writeString(empresas,
      "11111111;DUPLICATE A;2062;49;1,00;05;\n" +
        "11111111;DUPLICATE B;2062;49;1,00;05;\n" +
        "22222222;DUPLICATE C;2062;49;1,00;05;\n" +
        "22222222;DUPLICATE D;2062;49;1,00;05;\n" +
        "BAD;MALFORMED;2062;49;1,00;05;\n" +
        "33333333;ACCEPTED;2062;49;1,00;05;\n",
      StandardCharsets.ISO_8859_1)
    val bronze = CompanyDataPipeline.writeBronzeCompanies(spark, config, Seq(empresas))
    val references = Map(
      "legal_nature" -> reference(Seq("2062" -> "SOCIEDADE EMPRESARIA LIMITADA")),
      "partner_qualification" -> reference(Seq("49" -> "SOCIO-ADMINISTRADOR"))
    )

    val metrics = CompanyDataPipeline.writeSilverCompaniesWithMetrics(spark, config, bronze, references)
    val malformed = spark.read.parquet(
      CompanyDataPaths.qualityRoot(config).resolve("malformed_companies").toString)
    val duplicates = spark.read.parquet(
      CompanyDataPaths.qualityRoot(config).resolve("duplicate_companies").toString)

    assert(metrics.inputRowCount === 6L)
    assert(metrics.rowCount === 1L)
    assert(metrics.malformedRowCount === 1L)
    assert(metrics.duplicateRowCount === 4L)
    assert(metrics.duplicateKeyCount === 2L)
    assert(metrics.quarantinedRowCount === 5L)
    assert(malformed.count() === 1L)
    assert(duplicates.count() === 4L)
    assert(duplicates.select("cnpj_root").distinct().count() === 2L)
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

  test("canonicalizes short TOM codes and records current-source provenance") {
    val root = Files.createTempDirectory("atlas-company-geography-padding")
    val config = testConfig(root)
    val tom = root.resolve("tom.csv")
    Files.writeString(tom, "3;1100205;PORTO VELHO;Porto Velho;RO\n", StandardCharsets.ISO_8859_1)
    val ibge = root.resolve("ibge.json")
    Files.writeString(ibge, ibgeRow("1100205", "Porto Velho", "11", "RO", "Rondônia", "1", "N", "Norte"),
      StandardCharsets.UTF_8)
    val manifest = CompanyDataManifest("2026-07", root.resolve("manifest.json"), "manifest", Map.empty,
      tom, "tom-hash", ibge, "ibge-hash", None)

    val row = CompanyDataPipeline.writeGeography(spark, config, manifest).head()
    assert(row.getAs[String]("receita_municipality_code") === "0003")
    assert(row.getAs[String]("mapping_source") === "current_tom")
    assert(row.getAs[String]("mapping_source_release") === "2026-07")
    assert(row.getAs[Boolean]("current_tom_present"))
  }

  test("uses the reviewed Boa Esperanca do Norte override when TOM omits it") {
    val root = Files.createTempDirectory("atlas-company-geography-override")
    val config = testConfig(root)
    val tom = root.resolve("tom.csv")
    Files.writeString(tom, "7107;3550308;SAO PAULO;São Paulo;SP\n", StandardCharsets.ISO_8859_1)
    val ibge = root.resolve("ibge.json")
    Files.writeString(ibge, "[" +
      ibgeObject("3550308", "São Paulo", "35", "SP", "São Paulo", "3", "SE", "Sudeste") + "," +
      ibgeObject("5101837", "Boa Esperança do Norte", "51", "MT", "Mato Grosso", "5", "CO", "Centro-Oeste") +
      "]", StandardCharsets.UTF_8)
    val manifest = CompanyDataManifest("2026-07", root.resolve("manifest.json"), "manifest", Map.empty,
      tom, "tom-hash", ibge, "ibge-hash", None)

    val row = CompanyDataPipeline.writeGeography(spark, config, manifest)
      .filter("receita_municipality_code = '1182'").head()
    assert(row.getAs[String]("ibge_municipality_code") === "5101837")
    assert(row.getAs[String]("mapping_source") === "verified_override")
    assert(!row.getAs[Boolean]("current_tom_present"))
    assert(row.getAs[String]("evidence_reference").contains("boaesperancadonorte.mt.gov.br"))
  }

  test("rejects a current TOM mapping that contradicts a reviewed override") {
    val root = Files.createTempDirectory("atlas-company-geography-override-conflict")
    val config = testConfig(root)
    val tom = root.resolve("tom.csv")
    Files.writeString(tom, "1182;3550308;CONFLICT;São Paulo;SP\n", StandardCharsets.ISO_8859_1)
    val ibge = root.resolve("ibge.json")
    Files.writeString(ibge, "[" +
      ibgeObject("3550308", "São Paulo", "35", "SP", "São Paulo", "3", "SE", "Sudeste") + "," +
      ibgeObject("5101837", "Boa Esperança do Norte", "51", "MT", "Mato Grosso", "5", "CO", "Centro-Oeste") +
      "]", StandardCharsets.UTF_8)
    val manifest = CompanyDataManifest("2026-07", root.resolve("manifest.json"), "manifest", Map.empty,
      tom, "tom-hash", ibge, "ibge-hash", None)

    val error = intercept[IllegalStateException] {
      CompanyDataPipeline.writeGeography(spark, config, manifest)
    }
    assert(error.getMessage === "Current TOM conflicts with a reviewed municipality override")
  }

  test("carries forward an uncontradicted mapping from the previous geography version") {
    val root = Files.createTempDirectory("atlas-company-geography-carry-forward")
    val june = testConfig(root, "2026-06")
    val juneTom = root.resolve("june-tom.csv")
    Files.writeString(juneTom, "3;1100205;PORTO VELHO;Porto Velho;RO\n", StandardCharsets.ISO_8859_1)
    val ibge = root.resolve("ibge.json")
    Files.writeString(ibge, ibgeRow("1100205", "Porto Velho", "11", "RO", "Rondônia", "1", "N", "Norte"),
      StandardCharsets.UTF_8)
    CompanyDataPipeline.writeGeography(spark, june,
      CompanyDataManifest("2026-06", root.resolve("manifest.json"), "manifest", Map.empty,
        juneTom, "june-tom-hash", ibge, "ibge-hash", None))

    val july = testConfig(root, "2026-07")
    val julyTom = root.resolve("july-tom.csv")
    Files.writeString(julyTom, "9707;0;EXTERIOR;;EX\n", StandardCharsets.ISO_8859_1)
    val row = CompanyDataPipeline.writeGeography(spark, july,
      CompanyDataManifest("2026-07", root.resolve("manifest.json"), "manifest", Map.empty,
        julyTom, "july-tom-hash", ibge, "ibge-hash", None))
      .filter("receita_municipality_code = '0003'").head()

    assert(row.getAs[String]("mapping_source") === "carried_forward")
    assert(row.getAs[String]("mapping_source_release") === "2026-06")
    assert(!row.getAs[Boolean]("current_tom_present"))
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
    assert(error.getMessage === "TOM contains malformed canonical municipality codes")
  }

  private def reference(rows: Seq[(String, String)]): DataFrame = {
    val session = spark
    import session.implicits._
    rows.toDF("code", "description")
  }

  private def ibgeRow(
      code: String, name: String, stateCode: String, state: String, stateName: String,
      regionCode: String, region: String, regionName: String
  ): String = "[" + ibgeObject(code, name, stateCode, state, stateName, regionCode, region, regionName) + "]"

  private def ibgeObject(
      code: String, name: String, stateCode: String, state: String, stateName: String,
      regionCode: String, region: String, regionName: String
  ): String =
    s"""{"id":"$code","nome":"$name","regiao-imediata":{"id":"x","nome":"Immediate","regiao-intermediaria":{"id":"y","nome":"Intermediate","UF":{"id":"$stateCode","sigla":"$state","nome":"$stateName","regiao":{"id":"$regionCode","sigla":"$region","nome":"$regionName"}}}}}"""

  private def testConfig(root: Path, release: String = "2026-07"): AtlasConfig = AtlasConfig(
    SparkConfig("local[1]", "test", 1, root.resolve("spark").toString),
    CsvConfig(";", "ISO-8859-1"),
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
