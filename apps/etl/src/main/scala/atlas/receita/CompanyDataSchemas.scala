package atlas.receita

import org.apache.spark.sql.types.{DecimalType, StringType, StructField, StructType, TimestampType}

/** Declarative design contracts for the planned Receita company data foundation.
  *
  * These schemas have no readers, writers, jobs, paths, or CLI integration. They make source and
  * target shapes reviewable before acquisition and bronze ingestion are implemented.
  */
object CompanyDataSchemas {
  val empresaColumns: Seq[String] = Seq(
    "cnpj_root",
    "legal_name",
    "legal_nature_code",
    "responsible_qualification_code",
    "share_capital_raw",
    "company_size_code",
    "responsible_federative_entity"
  )

  val empresasRaw: StructType = nullableStrings(empresaColumns)

  val empresasBronze: StructType = StructType(
    Seq(
      StructField("cnpj_root", StringType, nullable = true),
      StructField("legal_name", StringType, nullable = true),
      StructField("legal_nature_code", StringType, nullable = true),
      StructField("responsible_qualification_code", StringType, nullable = true),
      StructField("share_capital_raw", StringType, nullable = true),
      StructField("share_capital", DecimalType(20, 2), nullable = true),
      StructField("company_size_code", StringType, nullable = true),
      StructField("responsible_federative_entity", StringType, nullable = true),
      StructField("source_name", StringType, nullable = false),
      StructField("source_file", StringType, nullable = false),
      StructField("ingestion_timestamp", TimestampType, nullable = false),
      StructField("release", StringType, nullable = false)
    )
  )

  val referenceGroups: Seq[String] = Seq(
    "cnae",
    "municipality",
    "legal_nature",
    "country",
    "partner_qualification",
    "registration_status_reason"
  )
  val referenceRaw: StructType = nullableStrings(Seq("code", "description"))

  val tomMunicipalityColumns: Seq[String] = Seq(
    "receita_municipality_code",
    "ibge_municipality_code",
    "receita_municipality_name",
    "ibge_municipality_name",
    "state_abbreviation"
  )
  val tomMunicipalitiesRaw: StructType = nullableStrings(tomMunicipalityColumns)

  val ibgeMunicipalityRaw: StructType = StructType(
    Seq(
      StructField("id", StringType, nullable = false),
      StructField("nome", StringType, nullable = false),
      StructField(
        "regiao-imediata",
        StructType(
          Seq(
            StructField("id", StringType, nullable = true),
            StructField("nome", StringType, nullable = true),
            StructField(
              "regiao-intermediaria",
              StructType(
                Seq(
                  StructField("id", StringType, nullable = true),
                  StructField("nome", StringType, nullable = true),
                  StructField(
                    "UF",
                    StructType(
                      Seq(
                        StructField("id", StringType, nullable = false),
                        StructField("sigla", StringType, nullable = false),
                        StructField("nome", StringType, nullable = false),
                        StructField(
                          "regiao",
                          StructType(
                            Seq(
                              StructField("id", StringType, nullable = false),
                              StructField("sigla", StringType, nullable = false),
                              StructField("nome", StringType, nullable = false)
                            )
                          ),
                          nullable = false
                        )
                      )
                    ),
                    nullable = false
                  )
                )
              ),
              nullable = true
            )
          )
        ),
        nullable = true
      )
    )
  )

  private def nullableStrings(names: Seq[String]): StructType =
    StructType(names.map(StructField(_, StringType, nullable = true)))
}
