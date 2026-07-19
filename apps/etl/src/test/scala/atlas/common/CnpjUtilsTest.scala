package atlas.common

import org.scalatest.funsuite.AnyFunSuite
class CnpjUtilsTest extends AnyFunSuite {
  test("builds a normalized full CNPJ") { assert(CnpjUtils.buildFullCnpj("12.345.678", "1", "9") === "12345678000109") }
  test("preserves and uppercases alphanumeric components") {
    assert(CnpjUtils.buildFullCnpj("12abc345", "01de", "35") === "12ABC34501DE35")
  }
  test("left pads branch and check") { assert(CnpjUtils.normalizeBranch("12") === "0012"); assert(CnpjUtils.normalizeCheck("3") === "03") }
  test("does not strip unknown characters or truncate over-width components") {
    assert(CnpjUtils.normalizeRoot("12_AB345") === "12_AB345")
    assert(CnpjUtils.normalizeBranch("ABCDE") === "ABCDE")
  }
  test("checks final length") { assert(CnpjUtils.isValidLength("12.345.678/0001-90")); assert(!CnpjUtils.isValidLength("123")) }
}
