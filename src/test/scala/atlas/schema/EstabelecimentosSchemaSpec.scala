package atlas.schema

import org.scalatest.funsuite.AnyFunSuite

class EstabelecimentosSchemaSpec extends AnyFunSuite {
  test("official Estabelecimentos layout has the expected positions") {
    assert(EstabelecimentosSchema.columnNames.size === 30)
    assert(EstabelecimentosSchema.columnNames.take(3) === Seq("cnpj_basico", "cnpj_ordem", "cnpj_dv"))
    assert(EstabelecimentosSchema.columnNames(19) === "uf")
    assert(EstabelecimentosSchema.columnNames.last === "data_situacao_especial")
  }
}
