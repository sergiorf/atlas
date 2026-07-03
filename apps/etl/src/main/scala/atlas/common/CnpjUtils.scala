package atlas.common

import org.apache.spark.sql.Column
import org.apache.spark.sql.functions.{concat, lpad, regexp_replace}

object CnpjUtils {
  def onlyDigits(value: String): String = Option(value).getOrElse("").replaceAll("[^0-9]", "")
  def normalizeRoot(value: String): String = leftPad(onlyDigits(value), 8)
  def normalizeBranch(value: String): String = leftPad(onlyDigits(value), 4)
  def normalizeCheck(value: String): String = leftPad(onlyDigits(value), 2)
  def buildFullCnpj(root: String, branch: String, check: String): String = normalizeRoot(root) + normalizeBranch(branch) + normalizeCheck(check)
  def isValidLength(cnpj: String): Boolean = onlyDigits(cnpj).length == 14
  def normalizeRoot(column: Column): Column = normalize(column, 8)
  def normalizeBranch(column: Column): Column = normalize(column, 4)
  def normalizeCheck(column: Column): Column = normalize(column, 2)
  def buildFullCnpj(root: Column, branch: Column, check: Column): Column = concat(normalizeRoot(root), normalizeBranch(branch), normalizeCheck(check))
  private def normalize(column: Column, size: Int): Column = lpad(regexp_replace(column, "[^0-9]", ""), size, "0")
  private def leftPad(value: String, size: Int): String = value.takeRight(size).reverse.padTo(size, '0').reverse
}
