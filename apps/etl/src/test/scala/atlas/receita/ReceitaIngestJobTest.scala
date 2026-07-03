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
}
