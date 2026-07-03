package atlas.receita

import org.apache.spark.sql.types.{StringType, StructField, StructType}
object ReceitaSchemas {
  val estabelecimentoColumns: Seq[String] = Seq(
    "cnpj_root", "cnpj_branch", "cnpj_check", "headquarters_branch_code", "trade_name",
    "registration_status_code", "registration_status_date", "registration_status_reason",
    "foreign_city_name", "country_code", "opening_date", "main_cnae", "secondary_cnaes",
    "street_type", "street_name", "street_number", "address_extra", "neighborhood",
    "postal_code", "state", "municipality_code", "ddd_1", "phone_1", "ddd_2", "phone_2",
    "fax_ddd", "fax", "email", "special_status", "special_status_date"
  )
  val estabelecimentos: StructType = StructType(estabelecimentoColumns.map(StructField(_, StringType, nullable = true)))
}
