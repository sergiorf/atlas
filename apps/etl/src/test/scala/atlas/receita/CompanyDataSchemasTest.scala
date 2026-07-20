package atlas.receita

import atlas.SparkSuite
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import org.apache.spark.sql.types.{DecimalType, StringType, TimestampType}
import org.scalatest.funsuite.AnyFunSuite
import scala.collection.JavaConverters._

class CompanyDataSchemasTest extends AnyFunSuite with SparkSuite {
  test("empresas source contract has the seven official positions") {
    assert(CompanyDataSchemas.empresaColumns === Seq(
      "cnpj_root",
      "legal_name",
      "legal_nature_code",
      "responsible_qualification_code",
      "share_capital_raw",
      "company_size_code",
      "responsible_federative_entity"
    ))
    assert(CompanyDataSchemas.empresasRaw.fields.forall(_.dataType === StringType))
  }

  test("empresas bronze contract preserves raw capital and declares typed metadata") {
    val schema = CompanyDataSchemas.empresasBronze
    assert(schema("cnpj_root").dataType === StringType)
    assert(schema("share_capital_raw").dataType === StringType)
    assert(schema("share_capital").dataType === DecimalType(20, 2))
    assert(schema("ingestion_timestamp").dataType === TimestampType)
    Seq("source_name", "source_file", "ingestion_timestamp", "release").foreach { name =>
      assert(!schema(name).nullable)
    }
  }

  test("synthetic empresas fixture exercises quoting and numeric and alphanumeric roots") {
    val path = resource("receita/company_data/empresas/valid.csv")
    val rows = spark.read
      .schema(CompanyDataSchemas.empresasRaw)
      .option("header", "false")
      .option("sep", ";")
      .option("quote", "\"")
      .option("escape", "\"")
      .csv(path)
      .collect()
    assert(rows.length === 2)
    assert(rows.map(_.getAs[String]("cnpj_root")).toSeq === Seq("12345678", "12ABC345"))
    assert(rows(1).getAs[String]("legal_name") === "Atlas; Dados")
    assert(rows(1).getAs[String]("share_capital_raw") === "1234,56")
  }

  test("empresas scenario fixture fixes acceptance expectations before ingestion") {
    val scenarios = Files
      .readAllLines(
        Paths.get(resource("receita/company_data/empresas/scenarios.csv")),
        StandardCharsets.UTF_8
      )
      .asScala
    assert(scenarios.exists(_.endsWith(";accepted")))
    assert(scenarios.count(_.endsWith(";reject_root")) === 3)
    assert(scenarios.count(_.endsWith(";reject_capital")) === 2)
    assert(scenarios.exists(_.endsWith(";accepted_null_capital")))
  }

  test("all six Receita references use a two-string-field source contract") {
    assert(CompanyDataSchemas.referenceGroups.size === 6)
    assert(CompanyDataSchemas.referenceGroups.distinct.size === 6)
    assert(CompanyDataSchemas.referenceRaw.fieldNames.toSeq === Seq("code", "description"))
    assert(CompanyDataSchemas.referenceRaw.fields.forall(_.dataType === StringType))

    val lines = Files.readAllLines(
      Paths.get(resource("receita/company_data/references/scenarios.csv")),
      StandardCharsets.UTF_8
    )
    assert(lines.asScala.toSeq === Seq(
      "6201;SOCIEDADE EMPRESÁRIA LIMITADA",
      "6201;SOCIEDADE EMPRESÁRIA LIMITADA",
      "6201;CONFLITO",
      ";SEM CÓDIGO",
      "9999;"
    ))
  }

  test("TOM and IBGE fixture contracts use exact identifiers and nested hierarchy") {
    assert(CompanyDataSchemas.tomMunicipalityColumns === Seq(
      "receita_municipality_code",
      "ibge_municipality_code",
      "receita_municipality_name",
      "ibge_municipality_name",
      "state_abbreviation"
    ))
    assert(CompanyDataSchemas.ibgeMunicipalityRaw("id").dataType === StringType)
    assert(CompanyDataSchemas.ibgeMunicipalityRaw.fieldNames.contains("regiao-imediata"))

    val tom = Files.readAllLines(Paths.get(resource("receita/company_data/geography/tom.csv")), StandardCharsets.UTF_8)
    val ibge = Files.readString(Paths.get(resource("receita/company_data/geography/ibge-municipalities.json")), StandardCharsets.UTF_8)
    val scenarios = Files
      .readAllLines(
        Paths.get(resource("receita/company_data/geography/scenarios.csv")),
        StandardCharsets.UTF_8
      )
      .asScala
    assert(tom.get(1).startsWith("7107;3550308;"))
    assert(ibge.contains("\"id\": \"3550308\""))
    assert(ibge.contains("\"regiao-imediata\""))
    assert(scenarios.exists(_.endsWith(";reject_ambiguous")))
    assert(scenarios.exists(_.endsWith(";reject_unmatched")))
    assert(scenarios.exists(_.endsWith(";reject_missing_parent")))
  }

  test("history fixture defines May seed followed by June and July comparisons") {
    val lines = Files.readAllLines(
      Paths.get(resource("receita/company_data/history/company_states.csv")),
      StandardCharsets.UTF_8
    )
    assert(lines.get(0) === "release;cnpj_root;legal_name;share_capital;expected_change")
    assert(lines.asScala.exists(_.endsWith(";seed")))
    assert(lines.asScala.exists(_.endsWith(";inserted")))
    assert(lines.asScala.exists(_.endsWith(";updated")))
    assert(lines.asScala.exists(_.endsWith(";unchanged")))
    assert(lines.asScala.exists(_.endsWith(";removed")))
  }

  private def resource(name: String): String =
    Paths.get(getClass.getClassLoader.getResource(name).toURI).toString
}
