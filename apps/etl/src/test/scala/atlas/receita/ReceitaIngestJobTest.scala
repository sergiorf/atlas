package atlas.receita

import atlas.SparkSuite
import java.sql.Date
import org.apache.spark.sql.Row
import org.scalatest.funsuite.AnyFunSuite

class ReceitaIngestJobTest extends AnyFunSuite with SparkSuite {
  test("schema has the official 30 positions") { assert(ReceitaSchemas.estabelecimentoColumns.size === 30); assert(ReceitaSchemas.estabelecimentoColumns(19) === "state") }
  test("normalizes establishment fields and metadata") {
    val values = ReceitaSchemas.estabelecimentoColumns.map {
      case "cnpj_root" => "12.345.678"; case "cnpj_branch" => "1"; case "cnpj_check" => "9"
      case "headquarters_branch_code" => "1"; case "trade_name" => " Atlas "
      case "opening_date" => "20240131"; case "registration_status_date" => "bad"; case _ => " "
    }
    val raw = spark.createDataFrame(spark.sparkContext.parallelize(Seq(Row(values: _*))), ReceitaSchemas.estabelecimentos)
    val result = ReceitaIngestJob.transform(raw).head()
    assert(result.getAs[String]("cnpj_full") === "12345678000109")
    assert(result.getAs[Boolean]("is_headquarters")); assert(result.getAs[String]("trade_name") === "Atlas")
    assert(result.getAs[Date]("opening_date") === Date.valueOf("2024-01-31")); assert(result.getAs[String]("source_name") === "receita_cnpj_estabelecimentos")
  }
  test("ingests numeric and alphanumeric CNPJs in the same fixture") {
    def row(root: String, branch: String, check: String): Row = Row(ReceitaSchemas.estabelecimentoColumns.map {
      case "cnpj_root" => root; case "cnpj_branch" => branch; case "cnpj_check" => check; case _ => " "
    }: _*)
    val raw = spark.createDataFrame(spark.sparkContext.parallelize(Seq(
      row("12.345.678", "1", "9"), row("12abc345", "01de", "35")
    )), ReceitaSchemas.estabelecimentos)
    val identifiers = ReceitaIngestJob.transform(raw).select("cnpj_full").collect().map(_.getString(0)).toSet
    assert(identifiers === Set("12345678000109", "12ABC34501DE35"))
  }
}
