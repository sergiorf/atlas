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
    val summary = spark.read.parquet(paths.summaryRelease.toString).head()
    assert(summary.getAs[Long]("previous_record_count") === 1L)
    assert(summary.getAs[Long]("current_record_count") === 2L)
    assert(summary.getAs[Long]("net_record_delta") === 1L)
    assert(summary.getAs[Long]("inserted_count") === 1L)
    assert(summary.getAs[Long]("updated_count") === 1L)
    assert(summary.getAs[Long]("removed_count") === 0L)
    assert(summary.getSeq[Row](summary.fieldIndex("changed_field_counts")).map(r => r.getString(0) -> r.getLong(1)) === Seq("email" -> 1L))
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
    val summary = spark.read.parquet(paths.summaryRelease.toString).head()
    assert(summary.getAs[Long]("event_count") === 0L)
    assert(summary.getAs[Long]("net_record_delta") === 0L)
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

  test("summarizes state movement, null state, removals, and updated fields deterministically") {
    val root = Files.createTempDirectory("atlas-history-state-summary")
    val config = AtlasConfig(
      SparkConfig("local[2]", "atlas-tests", 2, root.resolve("spark-tmp").toString),
      CsvConfig(";", "UTF-8"),
      ReceitaConfig("2026-07", root.resolve("raw").toString, root.resolve("bronze/receita").toString, root.resolve("silver/receita").toString),
      root.resolve("_atlas/status").toString, "overwrite"
    )
    val paths = ReleasePaths(config)
    val prior = silver("2026-06", "12345678000109", "a@example.com", "A", Some("PE"))
      .unionByName(silver("2026-06", "00000001000199", "b@example.com", "B", None))
      .unionByName(silver("2026-06", "00000002000188", "c@example.com", "C", Some("SP")))
    val candidate = silver("2026-07", "12345678000109", "a@example.com", "A", Some("AL"))
      .unionByName(silver("2026-07", "00000001000199", "new-b@example.com", "B", None))
      .unionByName(silver("2026-07", "00000003000177", "d@example.com", "D", Some("RJ")))

    EstablishmentHistoryJob.compareWithPrior(spark, config, prior, candidate, paths)
    val summary = spark.read.parquet(paths.summaryRelease.toString).head()
    val states = summary.getSeq[Row](summary.fieldIndex("state_counts")).map { row =>
      Option(row.getAs[String]("state")) -> (
        Option(row.getAs[java.lang.Long]("previous_count")).map(_.longValue),
        Option(row.getAs[java.lang.Long]("current_count")).map(_.longValue),
        row.getAs[Long]("delta")
      )
    }
    assert(states.map(_._1) === Seq(None, Some("AL"), Some("PE"), Some("RJ"), Some("SP")))
    assert(states.last === Some("SP") -> (Some(1L), None, -1L))
    val fields = summary.getSeq[Row](summary.fieldIndex("changed_field_counts"))
      .map(row => row.getAs[String]("field_name") -> row.getAs[Long]("count"))
    assert(fields === Seq("email" -> 1L, "state" -> 1L))
  }

  test("rejects equal and older releases without changing current") {
    val root = Files.createTempDirectory("atlas-history-order")
    val base = AtlasConfig(
      SparkConfig("local[2]", "atlas-tests", 2, root.resolve("spark-tmp").toString),
      CsvConfig(";", "UTF-8"),
      ReceitaConfig("2026-06", root.resolve("raw").toString, root.resolve("bronze/receita").toString, root.resolve("silver/receita").toString),
      root.resolve("_atlas/status").toString,
      "overwrite"
    )
    val currentPath = ReleasePaths(base).silverCurrent
    silver("2026-06", "12345678000109", "same@example.com", "Atlas")
      .write.mode("overwrite").parquet(currentPath.toString)

    val equal = intercept[IllegalStateException](EstablishmentHistoryJob.validateAdvance(spark, base))
    assert(equal.getMessage.contains("candidate 2026-06 must be newer than current 2026-06"))
    val older = base.copy(receita = base.receita.copy(snapshot = "2026-05"))
    intercept[IllegalStateException](EstablishmentHistoryJob.validateAdvance(spark, older))
    assert(spark.read.parquet(currentPath.toString).select("release").distinct().head().getString(0) === "2026-06")
  }

  test("requires explicit acknowledgement for legacy current metadata") {
    val root = Files.createTempDirectory("atlas-history-legacy-guard")
    val config = AtlasConfig(
      SparkConfig("local[2]", "atlas-tests", 2, root.resolve("spark-tmp").toString),
      CsvConfig(";", "UTF-8"),
      ReceitaConfig("2026-07", root.resolve("raw").toString, root.resolve("bronze/receita").toString, root.resolve("silver/receita").toString),
      root.resolve("_atlas/status").toString,
      "overwrite"
    )
    silver("2026-06", "12345678000109", "old@example.com", "Atlas")
      .drop("release", "record_hash")
      .write.mode("overwrite").parquet(ReleasePaths(config).silverCurrent.toString)

    intercept[IllegalStateException](EstablishmentHistoryJob.validateAdvance(spark, config))
    assert(EstablishmentHistoryJob.validateAdvance(spark, config, allowLegacyCurrent = true) === EstablishmentHistoryJob.LegacyCurrent)
  }

  private def silver(
      release: String,
      cnpjFull: String,
      email: String,
      tradeName: String,
      state: Option[String] = Some("PE")
  ): DataFrame = {
    val raw = spark.createDataFrame(
      spark.sparkContext.parallelize(Seq(Row(values(cnpjFull, email, tradeName, state): _*))),
      ReceitaSchemas.estabelecimentos
    )
    SilverEstablishmentJob.transform(ReceitaIngestJob.transform(raw))
      .withColumn("release", lit(release))
      .withColumn("record_hash", sha2(to_json(struct(EstablishmentHistoryJob.TrackedFields.map(name => col(name).as(name)): _*)), 256))
  }

  private def values(cnpjFull: String, email: String, tradeName: String, state: Option[String]): Seq[String] =
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
      case "state" => state.orNull
      case "municipality_code" => "2531"
      case "email" => email
      case _ => " "
    }
}
