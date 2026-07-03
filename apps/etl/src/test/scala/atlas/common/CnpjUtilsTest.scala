package atlas.common

import org.scalatest.funsuite.AnyFunSuite
class CnpjUtilsTest extends AnyFunSuite {
  test("builds a normalized full CNPJ") { assert(CnpjUtils.buildFullCnpj("12.345.678", "1", "9") === "12345678000109") }
  test("removes punctuation") { assert(CnpjUtils.onlyDigits("12.345/6-7") === "1234567") }
  test("left pads branch and check") { assert(CnpjUtils.normalizeBranch("12") === "0012"); assert(CnpjUtils.normalizeCheck("3") === "03") }
  test("checks final length") { assert(CnpjUtils.isValidLength("12.345.678/0001-90")); assert(!CnpjUtils.isValidLength("123")) }
}
