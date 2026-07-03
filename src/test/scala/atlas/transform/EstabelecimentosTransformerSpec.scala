package atlas.transform

import atlas.SparkSuite
import atlas.schema.EstabelecimentosSchema
import java.sql.Date
import org.apache.spark.sql.Row
import org.scalatest.funsuite.AnyFunSuite

class EstabelecimentosTransformerSpec extends AnyFunSuite with SparkSuite {
  test("cleans strings, parses dates, and constructs a 14-digit CNPJ") {
    val values = EstabelecimentosSchema.columnNames.map {
      case "cnpj_basico"             => "00123456"
      case "cnpj_ordem"              => "0001"
      case "cnpj_dv"                 => "90"
      case "nome_fantasia"           => "  Atlas Ltda  "
      case "data_situacao_cadastral" => "20240131"
      case "data_inicio_atividade"    => "not-a-date"
      case "data_situacao_especial"  => ""
      case _                           => "  "
    }
    val rows = spark.sparkContext.parallelize(Seq(Row(values: _*)))
    val raw = spark.createDataFrame(rows, EstabelecimentosSchema.raw)
    val result = EstabelecimentosTransformer.transform(raw).head()

    assert(result.getAs[String]("cnpj") === "00123456000190")
    assert(result.getAs[String]("nome_fantasia") === "Atlas Ltda")
    assert(result.getAs[Date]("data_situacao_cadastral") === Date.valueOf("2024-01-31"))
    assert(result.isNullAt(result.fieldIndex("data_inicio_atividade")))
    assert(result.isNullAt(result.fieldIndex("uf")))
  }
}
