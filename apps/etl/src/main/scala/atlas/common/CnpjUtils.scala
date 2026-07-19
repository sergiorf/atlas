package atlas.common

import org.apache.spark.sql.Column
import org.apache.spark.sql.functions.{concat, length, lpad, regexp_replace, trim, upper, when}

object CnpjUtils {
  def normalizeRoot(value: String): String = normalize(value, 8)
  def normalizeBranch(value: String): String = normalize(value, 4)
  def normalizeCheck(value: String): String = normalize(value, 2)
  def buildFullCnpj(root: String, branch: String, check: String): String = normalizeRoot(root) + normalizeBranch(branch) + normalizeCheck(check)
  def isValidLength(cnpj: String): Boolean = normalizeDisplay(cnpj).matches("[0-9A-Z]{12}[0-9]{2}")
  def normalizeRoot(column: Column): Column = normalize(column, 8)
  def normalizeBranch(column: Column): Column = normalize(column, 4)
  def normalizeCheck(column: Column): Column = normalize(column, 2)
  def buildFullCnpj(root: Column, branch: Column, check: Column): Column = concat(normalizeRoot(root), normalizeBranch(branch), normalizeCheck(check))
  private def normalize(column: Column, size: Int): Column = {
    val cleaned = upper(regexp_replace(trim(column), "[./-]", ""))
    when(length(cleaned) < size, lpad(cleaned, size, "0")).otherwise(cleaned)
  }
  private def normalize(value: String, size: Int): String = {
    val cleaned = normalizeDisplay(value)
    if (cleaned.length < size) cleaned.reverse.padTo(size, '0').reverse else cleaned
  }
  private def normalizeDisplay(value: String): String =
    Option(value).getOrElse("").trim.toUpperCase(java.util.Locale.ROOT).replaceAll("[./-]", "")
}
