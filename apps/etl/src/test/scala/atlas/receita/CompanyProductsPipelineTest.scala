package atlas.receita

import atlas.SparkSuite
import atlas.config.{AtlasConfig, CsvConfig, ReceitaConfig, SparkConfig}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import org.apache.spark.sql.functions.col
import org.scalatest.funsuite.AnyFunSuite

class CompanyProductsPipelineTest extends AnyFunSuite with SparkSuite {
  test("normalizes Simples tri-state indicators and rejects duplicate roots") {
    val session = spark
    import session.implicits._
    val root = Files.createTempDirectory("atlas-simples")
    val config = testConfig(root)
    val input = root.resolve("simples.csv")
    Files.writeString(input,
      "12345678;S;20200101;;N;;\n" +
        "12ABC345;;;20210102;S;20200103;\n",
      StandardCharsets.ISO_8859_1)
    val companies = Seq("12345678", "12ABC345").toDF("cnpj_root")
    val tax = CompanyProductsPipeline.buildTaxRegime(spark, config, Seq(input), companies)

    assert(tax.count() === 2L)
    val alpha = tax.filter(col("cnpj_root") === "12345678").head()
    assert(alpha.getAs[Boolean]("is_simples"))
    assert(!alpha.getAs[Boolean]("is_mei"))
    val beta = tax.filter(col("cnpj_root") === "12ABC345").head()
    assert(beta.isNullAt(beta.schema.fieldIndex("is_simples")))
  }

  test("resolves only structurally valid legal-entity partners and never people") {
    val session = spark
    import session.implicits._
    val root = Files.createTempDirectory("atlas-socios")
    val config = testConfig(root)
    val input = root.resolve("socios.csv")
    Files.writeString(input,
      "12345678;1;TARGET SA;12ABC345000199;49;20200101;105;;;;0\n" +
        "12345678;2;MASKED PERSON;***123456**;49;20200101;105;;;;5\n",
      StandardCharsets.ISO_8859_1)
    val companies = Seq("12345678", "12ABC345").toDF("cnpj_root")
    val qualifications = Seq("49" -> "SOCIO-ADMINISTRADOR").toDF("code", "description")
    val partners = CompanyProductsPipeline.buildPartners(
      spark, config, Seq(input), companies, qualifications
    )
    val relationships = CompanyProductsPipeline.buildRelationships(
      spark, config, partners, companies
    )

    assert(partners.count() === 2L)
    assert(relationships.count() === 1L)
    val edge = relationships.head()
    assert(edge.getAs[String]("source_company_cnpj_root") === "12345678")
    assert(edge.getAs[String]("participant_company_cnpj_root") === "12ABC345")
    assert(edge.getAs[String]("relationship_class") === "PARTNER_ADMINISTRATION")
    assert(!relationships.columns.contains("representative_identifier_raw"))
  }

  test("builds deterministic components, cycles, and bounded relationship paths") {
    val session = spark
    import session.implicits._
    val root = Files.createTempDirectory("atlas-network")
    val config = testConfig(root)
    val relationships = Seq(
      ("e1", "AAAAAAAA", "BBBBBBBB"),
      ("e2", "BBBBBBBB", "CCCCCCCC"),
      ("e3", "CCCCCCCC", "AAAAAAAA"),
      ("e4", "DDDDDDDD", "EEEEEEEE")
    ).toDF("relationship_edge_id", "source_company_cnpj_root", "participant_company_cnpj_root")
      .withColumn("relationship_class", org.apache.spark.sql.functions.lit("UNKNOWN_CORPORATE_RELATIONSHIP"))
      .withColumn("participant_qualification_code", org.apache.spark.sql.functions.lit("00"))
      .withColumn("participant_qualification_description", org.apache.spark.sql.functions.lit("UNKNOWN"))
      .withColumn("evidence_source", org.apache.spark.sql.functions.lit("RECEITA_QSA"))
      .withColumn("confidence", org.apache.spark.sql.functions.lit("SOURCE_EVIDENCED"))
      .withColumn("relationship_rule_version", org.apache.spark.sql.functions.lit("1"))

    val network = CompanyProductsPipeline.buildNetwork(spark, config, relationships)
    assert(network.filter(col("source") === "AAAAAAAA").head().getAs[String]("component_id") === "AAAAAAAA")
    assert(network.filter(col("source") === "DDDDDDDD").head().getAs[String]("component_id") === "DDDDDDDD")
    assert(network.filter(col("source") === "AAAAAAAA").head().getAs[Boolean]("edge_in_cycle"))
    val paths = spark.read.parquet(CompanyDataPaths.goldRelationshipPaths(config).toString)
    assert(paths.agg(org.apache.spark.sql.functions.max("path_depth")).head().getInt(0) <= 3)
  }

  private def testConfig(root: Path): AtlasConfig =
    AtlasConfig(
      SparkConfig("local[2]", "atlas-test", 2, root.resolve("spark").toString),
      CsvConfig(";", "ISO-8859-1"),
      ReceitaConfig(
        "2026-07",
        root.resolve("raw/receita/2026-07/estabelecimentos/extracted").toString,
        root.resolve("data/bronze/receita").toString,
        root.resolve("data/silver/receita").toString,
        root.resolve("raw/receita/2026-07/company-data").toString
      ),
      root.resolve("data/_atlas/status").toString,
      "overwrite"
    )
}
