package atlas.transform

import atlas.schema.EstabelecimentosSchema
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.col

object EstabelecimentosTransformer {
  private val dateColumns = Set(
    "data_situacao_cadastral",
    "data_inicio_atividade",
    "data_situacao_especial"
  )

  def transform(raw: DataFrame): DataFrame = {
    val normalized = raw.select(EstabelecimentosSchema.columnNames.map { name =>
      if (dateColumns.contains(name)) Cleaning.receitaDate(col(name)).as(name)
      else Cleaning.nullableTrim(col(name)).as(name)
    }: _*)
    normalized.withColumn("cnpj", Cleaning.fullCnpj)
  }
}
