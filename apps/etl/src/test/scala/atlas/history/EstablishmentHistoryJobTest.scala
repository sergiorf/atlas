package atlas.history

import atlas.SparkSuite
import atlas.config.{AtlasConfig, CsvConfig, ReceitaConfig, SparkConfig}
import atlas.receita.{ReceitaIngestJob, ReceitaSchemas, SilverEstablishmentJob}
import atlas.release.ReleasePaths
import java.nio.file.Files
import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.sql.functions._
import org.scalatest.funsuite.AnyFunSuite

class EstablishmentHistoryJobTest extends AnyFunSuite with SparkSuite {
  test("stores selected field deltas without full old records") {
    val root = Files.createTempDirectory("atlas-history")
    val config = AtlasConfig(
      SparkConfig("local[2]", "atlas-tests", 2, root.resolve("spark-tmp").toString),
      CsvConfig(";", "UTF-8"),
      ReceitaConfig("2026-07", root.resolve("raw").toString, root.resolve("bronze/receita").toString, root.resolve("silver/receita").toString),
      root.resolve("_atlas/status").toString,
      "overwrite"
    )
    val paths = ReleasePaths(config)
    val prior = silver("2026-06", "12345678000109", "old@example.com", "Atlas")
    val candidate = silver("2026-07", "12345678000109", "new@example.com", "Atlas")
      .unionByName(silver("2026-07", "00000001000199", "other@example.com", "Other"))

    val result = EstablishmentHistoryJob.compareWithPrior(spark, config, prior, candidate, paths)

    assert(result.updatedCount === 1)
    assert(result.insertedCount === 1)
    assert(result.removedCount === 0)
    val events = spark.read.parquet(paths.historyRelease.toString)
    assert(events.count() === 2)
    val updated = events.filter(col("change_type") === "updated").head()
    val fields = updated.getSeq[Row](updated.fieldIndex("changed_fields")).map(_.getAs[String]("field_name"))
    assert(fields === Seq("email"))
  }

  test("unchanged releases do not write history events") {
    val root = Files.createTempDirectory("atlas-history-unchanged")
    val config = AtlasConfig(
      SparkConfig("local[2]", "atlas-tests", 2, root.resolve("spark-tmp").toString),
      CsvConfig(";", "UTF-8"),
      ReceitaConfig("2026-07", root.resolve("raw").toString, root.resolve("bronze/receita").toString, root.resolve("silver/receita").toString),
      root.resolve("_atlas/status").toString,
      "overwrite"
    )
    val paths = ReleasePaths(config)
    val prior = silver("2026-06", "12345678000109", "same@example.com", "Atlas")
    val candidate = silver("2026-07", "12345678000109", "same@example.com", "Atlas")

    val result = EstablishmentHistoryJob.compareWithPrior(spark, config, prior, candidate, paths)

    assert(result.eventRowCount === 0)
    assert(!Files.exists(paths.historyRelease))
  }

  test("compares a legacy current table without release metadata or record hashes") {
    val root = Files.createTempDirectory("atlas-history-legacy-current")
    val config = AtlasConfig(
      SparkConfig("local[2]", "atlas-tests", 2, root.resolve("spark-tmp").toString),
      CsvConfig(";", "UTF-8"),
      ReceitaConfig("2026-07", root.resolve("raw").toString, root.resolve("bronze/receita").toString, root.resolve("silver/receita").toString),
      root.resolve("_atlas/status").toString,
      "overwrite"
    )
    val paths = ReleasePaths(config)
    val prior = silver("2026-06", "12345678000109", "old@example.com", "Atlas").drop("release", "record_hash")
    val candidate = silver("2026-07", "12345678000109", "new@example.com", "Atlas")

    val result = EstablishmentHistoryJob.compareWithPrior(spark, config, prior, candidate, paths)

    assert(result.updatedCount === 1)
    val event = spark.read.parquet(paths.historyRelease.toString).head()
    assert(event.isNullAt(event.fieldIndex("from_release")))
    assert(event.getAs[String]("change_type") === "updated")
  }

  private def silver(release: String, cnpjFull: String, email: String, tradeName: String): DataFrame = {
    val raw = spark.createDataFrame(
      spark.sparkContext.parallelize(Seq(Row(values(cnpjFull, email, tradeName): _*))),
      ReceitaSchemas.estabelecimentos
    )
    SilverEstablishmentJob.transform(ReceitaIngestJob.transform(raw))
      .withColumn("release", lit(release))
      .withColumn("record_hash", sha2(to_json(struct(EstablishmentHistoryJob.TrackedFields.map(name => col(name).as(name)): _*)), 256))
  }

  private def values(cnpjFull: String, email: String, tradeName: String): Seq[String] =
    ReceitaSchemas.estabelecimentoColumns.map {
      case "cnpj_root" => cnpjFull.take(8)
      case "cnpj_branch" => cnpjFull.slice(8, 12)
      case "cnpj_check" => cnpjFull.takeRight(2)
      case "headquarters_branch_code" => "1"
      case "trade_name" => tradeName
      case "registration_status_code" => "02"
      case "registration_status_date" => "20240101"
      case "opening_date" => "20240131"
      case "main_cnae" => "6201501"
      case "secondary_cnaes" => "6202300"
      case "state" => "PE"
      case "municipality_code" => "2531"
      case "email" => email
      case _ => " "
    }
}
