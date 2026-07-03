package atlas.transform

import org.apache.spark.sql.Column
import org.apache.spark.sql.functions.{col, concat, length, lit, to_date, trim, when}

object Cleaning {
  def nullableTrim(column: Column): Column =
    when(length(trim(column)) === 0, lit(null).cast("string")).otherwise(trim(column))

  def receitaDate(column: Column): Column =
    when(nullableTrim(column).rlike("^[0-9]{8}$"), to_date(nullableTrim(column), "yyyyMMdd"))
      .otherwise(lit(null).cast("date"))

  def fullCnpj: Column = concat(col("cnpj_basico"), col("cnpj_ordem"), col("cnpj_dv"))
}
