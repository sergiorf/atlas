package atlas.receita

import atlas.SparkSuite
import atlas.common.DatasetPaths
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.sql.Timestamp
import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.sql.functions.lit
import org.scalatest.funsuite.AnyFunSuite

class SilverEstablishmentJobTest extends AnyFunSuite with SparkSuite {
  test("creates the curated normalized silver fields") {
    val result = SilverEstablishmentJob.transform(bronzeFixture()).head()

    assert(result.getAs[String]("cnpj_full") === "12345678000109")
    assert(result.getAs[Boolean]("is_active"))
    assert(result.getAs[String]("main_cnae") === "6201501")
    assert(result.getSeq[String](result.fieldIndex("secondary_cnaes")) === Seq("6202300", "6204000"))
    assert(result.getAs[String]("state") === "PE")
    assert(result.getAs[String]("postal_code") === "01234567")
    assert(result.getAs[String]("phone_1_area_code") === "81")
    assert(result.getAs[String]("phone_1_number") === "99998888")
    assert(result.getAs[String]("email") === "contact@example.com")
    assert(result.getAs[Timestamp]("silver_transformation_timestamp") != null)
    assert(!result.schema.fieldNames.exists(_.startsWith("_")))
  }

  test("reports malformed normalized values without publishing diagnostic columns") {
    val prepared = SilverEstablishmentJob.prepare(
      bronzeFixture()
        .withColumn("main_cnae", lit("bad"))
        .withColumn("secondary_cnaes", lit("6201501,bad,6201501"))
        .withColumn("state", lit("P3"))
    )
    val root = Files.createTempDirectory("atlas-silver-quality")
    val paths = DatasetPaths(
      "bronze",
      "silver",
      root.resolve("quality.json").toString,
      root.resolve("quality.md").toString
    )
    val report = SilverQualityChecks.evaluate(prepared, paths)

    assert(report.accepted)
    assert(report.invalidMainCnaeCount === 1)
    assert(report.malformedSecondaryCnaeTokenCount === 1)
    assert(report.invalidStateCount === 1)
  }

  test("rejects invalid or duplicate identities") {
    val invalid = bronzeFixture().withColumn("cnpj_full", lit("123"))
    val duplicate = bronzeFixture().unionByName(bronzeFixture())
    val root = Files.createTempDirectory("atlas-silver-identities")
    val paths = DatasetPaths("bronze", "silver", root.resolve("q.json").toString, root.resolve("q.md").toString)

    assert(!SilverQualityChecks.evaluate(SilverEstablishmentJob.prepare(invalid), paths).accepted)
    val duplicateReport = SilverQualityChecks.evaluate(SilverEstablishmentJob.prepare(duplicate), paths)
    assert(!duplicateReport.accepted)
    assert(duplicateReport.duplicateKeyCount === 1)
    assert(duplicateReport.duplicateRowCount === 2)
  }

  test("publishes valid data and preserves the prior publication after a rejected rerun") {
    val root = Files.createTempDirectory("atlas-silver-job")
    val paths = DatasetPaths(
      "bronze/estabelecimentos",
      "silver/establishments",
      root.resolve("quality.json").toString,
      root.resolve("quality.md").toString
    )
    var publishedCnpjs = Seq.empty[String]
    val publish: DataFrame => Unit = data => {
      publishedCnpjs = data.select("cnpj_full").collect().map(_.getString(0)).toSeq
    }

    val accepted = SilverEstablishmentJob.validateAndPublish(
      SilverEstablishmentJob.prepare(bronzeFixture()),
      paths
    )(publish)
    assert(accepted.accepted)
    assert(publishedCnpjs === Seq("12345678000109"))

    intercept[IllegalStateException] {
      SilverEstablishmentJob.validateAndPublish(
        SilverEstablishmentJob.prepare(bronzeFixture().unionByName(bronzeFixture())),
        paths
      )(publish)
    }

    assert(publishedCnpjs === Seq("12345678000109"))
    val json = new String(Files.readAllBytes(java.nio.file.Paths.get(paths.qualityJson)), StandardCharsets.UTF_8)
    assert(json.contains("\"status\": \"rejected\""))
  }

  private def bronzeFixture(): DataFrame = {
    val values = ReceitaSchemas.estabelecimentoColumns.map {
      case "cnpj_root" => "12345678"
      case "cnpj_branch" => "0001"
      case "cnpj_check" => "09"
      case "headquarters_branch_code" => "1"
      case "trade_name" => " Atlas "
      case "registration_status_code" => "02"
      case "registration_status_date" => "20240101"
      case "opening_date" => "20240131"
      case "main_cnae" => "6201501"
      case "secondary_cnaes" => "6202300,6204000,6202300"
      case "street_type" => "Rua"
      case "street_name" => "Exemplo"
      case "street_number" => "10"
      case "postal_code" => "1234567"
      case "state" => " pe "
      case "municipality_code" => "2531"
      case "ddd_1" => "(81)"
      case "phone_1" => "9999-8888"
      case "email" => " Contact@Example.COM "
      case _ => " "
    }
    val raw = spark.createDataFrame(
      spark.sparkContext.parallelize(Seq(Row(values: _*))),
      ReceitaSchemas.estabelecimentos
    )
    ReceitaIngestJob.transform(raw)
  }
}
