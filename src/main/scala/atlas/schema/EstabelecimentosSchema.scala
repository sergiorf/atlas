package atlas.schema

import org.apache.spark.sql.types.{StringType, StructField, StructType}

object EstabelecimentosSchema {
  val columnNames: Seq[String] = Seq(
    "cnpj_basico", "cnpj_ordem", "cnpj_dv", "identificador_matriz_filial", "nome_fantasia",
    "situacao_cadastral", "data_situacao_cadastral", "motivo_situacao_cadastral",
    "nome_cidade_exterior", "pais", "data_inicio_atividade", "cnae_fiscal_principal",
    "cnae_fiscal_secundaria", "tipo_logradouro", "logradouro", "numero", "complemento",
    "bairro", "cep", "uf", "municipio", "ddd_1", "telefone_1", "ddd_2", "telefone_2",
    "ddd_fax", "fax", "correio_eletronico", "situacao_especial", "data_situacao_especial"
  )

  val raw: StructType = StructType(columnNames.map(StructField(_, StringType, nullable = true)))
}
