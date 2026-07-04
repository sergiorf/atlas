package atlas.receita

import atlas.common.DatasetPaths
import atlas.config.AtlasConfig
import org.apache.spark.sql.{Column, DataFrame, SparkSession}
import org.apache.spark.sql.functions.{col, current_timestamp, expr, length, lit, lower, lpad, regexp_replace, trim, upper, when}
import org.apache.spark.sql.types.{ArrayType, StringType}
import org.apache.spark.storage.StorageLevel

object SilverEstablishmentJob {
  private val diagnosticColumns = Seq(
    "_invalid_main_cnae",
    "_malformed_secondary_cnae_count",
    "_invalid_state"
  )

  def run(spark: SparkSession, config: AtlasConfig): SilverQualityReport = {
    val paths = DatasetPaths.silverEstablishments(config.receita)
    val prepared = prepare(spark.read.parquet(paths.input)).persist(StorageLevel.DISK_ONLY)
    try {
      validateAndPublish(prepared, paths) { data =>
        data.write.mode(config.writeMode).partitionBy("state").parquet(paths.output)
      }
    } finally prepared.unpersist()
  }

  private[receita] def validateAndPublish(
      prepared: DataFrame,
      paths: DatasetPaths
  )(publish: DataFrame => Unit): SilverQualityReport = {
    val report = SilverQualityChecks.evaluate(prepared, paths)
    SilverQualityChecks.write(report, paths)
    if (!report.accepted) {
      throw new IllegalStateException(
        s"Silver quality gate rejected ${report.invalidCnpjCount} invalid CNPJ rows and " +
          s"${report.duplicateKeyCount} duplicate keys; existing output was not replaced"
      )
    }
    publish(published(prepared))
    report
  }

  private[receita] def transform(bronze: DataFrame): DataFrame = published(prepare(bronze))

  private[receita] def prepare(bronze: DataFrame): DataFrame = {
    val normalizedState = upper(nullableTrim(col("state")))
    val normalizedMainCnae = fixedDigits(col("main_cnae"), 7)
    val validSecondary = expr(
      "array_distinct(filter(transform(split(coalesce(secondary_cnaes, ''), ','), token -> trim(token)), " +
        "token -> token rlike '^[0-9]{7}$'))"
    )
    val malformedSecondaryCount = expr(
      "cast(size(filter(transform(split(coalesce(secondary_cnaes, ''), ','), token -> trim(token)), " +
        "token -> token <> '' and not(token rlike '^[0-9]{7}$'))) as long)"
    )

    bronze.select(
      nullableTrim(col("cnpj_root")).as("cnpj_root"),
      nullableTrim(col("cnpj_branch")).as("cnpj_branch"),
      nullableTrim(col("cnpj_check")).as("cnpj_check"),
      nullableTrim(col("cnpj_full")).as("cnpj_full"),
      col("is_headquarters").cast("boolean").as("is_headquarters"),
      nullableTrim(col("trade_name")).as("trade_name"),
      nullableTrim(col("registration_status_code")).as("registration_status_code"),
      (nullableTrim(col("registration_status_code")) === "02").as("is_active"),
      col("registration_status_date").cast("date").as("registration_status_date"),
      nullableTrim(col("registration_status_reason")).as("registration_status_reason"),
      col("opening_date").cast("date").as("opening_date"),
      normalizedMainCnae.as("main_cnae"),
      when(nullableTrim(col("secondary_cnaes")).isNull, lit(null).cast(ArrayType(StringType)))
        .otherwise(validSecondary)
        .as("secondary_cnaes"),
      nullableTrim(col("street_type")).as("street_type"),
      nullableTrim(col("street_name")).as("street_name"),
      nullableTrim(col("street_number")).as("street_number"),
      nullableTrim(col("address_extra")).as("address_extra"),
      nullableTrim(col("neighborhood")).as("neighborhood"),
      fixedDigitsAfterCleanup(col("postal_code"), 8).as("postal_code"),
      when(normalizedState.rlike("^[A-Z]{2}$"), normalizedState)
        .otherwise(lit(null).cast("string"))
        .as("state"),
      nullableTrim(col("municipality_code")).as("municipality_code"),
      nullableTrim(col("country_code")).as("country_code"),
      nullableTrim(col("foreign_city_name")).as("foreign_city_name"),
      digitsOnly(col("ddd_1")).as("phone_1_area_code"),
      digitsOnly(col("phone_1")).as("phone_1_number"),
      digitsOnly(col("ddd_2")).as("phone_2_area_code"),
      digitsOnly(col("phone_2")).as("phone_2_number"),
      digitsOnly(col("fax_ddd")).as("fax_area_code"),
      digitsOnly(col("fax")).as("fax_number"),
      lower(nullableTrim(col("email"))).as("email"),
      nullableTrim(col("special_status")).as("special_status"),
      col("special_status_date").cast("date").as("special_status_date"),
      nullableTrim(col("source_name")).as("source_name"),
      nullableTrim(col("source_file")).as("source_file"),
      col("ingestion_timestamp").cast("timestamp").as("ingestion_timestamp"),
      current_timestamp().as("silver_transformation_timestamp"),
      (nullableTrim(col("main_cnae")).isNull || normalizedMainCnae.isNull)
        .as("_invalid_main_cnae"),
      malformedSecondaryCount.as("_malformed_secondary_cnae_count"),
      (nullableTrim(col("state")).isNotNull && !normalizedState.rlike("^[A-Z]{2}$"))
        .as("_invalid_state")
    )
  }

  private def published(data: DataFrame): DataFrame = data.drop(diagnosticColumns: _*)

  private def nullableTrim(value: Column): Column =
    when(length(trim(value)) === 0, lit(null).cast("string")).otherwise(trim(value))

  private def fixedDigits(value: Column, width: Int): Column = {
    val cleaned = nullableTrim(value)
    when(cleaned.rlike(s"^[0-9]{$width}$$"), cleaned).otherwise(lit(null).cast("string"))
  }

  private def digitsOnly(value: Column): Column = {
    val cleaned = regexp_replace(nullableTrim(value), "[^0-9]", "")
    when(length(cleaned) === 0, lit(null).cast("string")).otherwise(cleaned)
  }

  private def fixedDigitsAfterCleanup(value: Column, width: Int): Column = {
    val cleaned = digitsOnly(value)
    when(length(cleaned) <= width, lpad(cleaned, width, "0"))
      .otherwise(lit(null).cast("string"))
  }
}
