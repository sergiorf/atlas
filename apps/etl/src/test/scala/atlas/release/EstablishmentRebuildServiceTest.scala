package atlas.release

import atlas.SparkSuite
import atlas.config.{AtlasConfig, CsvConfig, ReceitaConfig, SparkConfig}
import atlas.receita.ReceitaSchemas
import atlas.status.RunStatusRegistry
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.apache.spark.sql.functions.col
import org.scalatest.funsuite.AnyFunSuite

class EstablishmentRebuildServiceTest extends AnyFunSuite with SparkSuite {
  test("rebuilds raw releases chronologically and quarantines the active generation") {
    val root = Files.createTempDirectory("atlas-full-rebuild")
    writeRaw(root, "2026-05", "old@example.com")
    writeRaw(root, "2026-06", "new@example.com")
    val config = testConfig(root)
    val oldHistory = root.resolve("silver/receita/establishment_change_events/to_release=2026-05")
    Files.createDirectories(oldHistory)
    Files.write(oldHistory.resolve("old.marker"), "old".getBytes(StandardCharsets.UTF_8))

    val dryRun = EstablishmentRebuildService.plan(config, ReleaseId.unsafe("2026-05"), ReleaseId.unsafe("2026-06"))
    assert(dryRun.releases.map(_.value) === Seq("2026-05", "2026-06"))
    assert(dryRun.missingMonths.isEmpty)

    val result = EstablishmentRebuildService.force(spark, config, dryRun)
    val paths = ReleasePaths(config)
    val current = spark.read.parquet(paths.silverCurrent.toString)
    val events = spark.read.parquet(paths.historyRoot.resolve("to_release=2026-06").toString)
    val seedSummary = spark.read.parquet(paths.summaryRoot.resolve("to_release=2026-05").toString)
    val changedSummary = spark.read.parquet(paths.summaryRoot.resolve("to_release=2026-06").toString)

    assert(!result.dryRun)
    assert(current.select("release").distinct().head().getString(0) === "2026-06")
    assert(current.filter(col("email") === "new@example.com").count() === 1)
    assert(events.filter(col("change_type") === "updated").count() === 1)
    assert(!Files.exists(paths.historyRoot.resolve("to_release=2026-05")))
    assert(seedSummary.count() === 1L)
    assert(seedSummary.head().isNullAt(seedSummary.schema.fieldIndex("previous_record_count")))
    assert(changedSummary.head().getAs[Long]("updated_count") === 1L)
    assert(Files.exists(paths.bronzeRelease))
    assert(Files.exists(result.trashRoot.get.resolve("history/to_release=2026-05/old.marker")))
    assert(Files.exists(root.resolve("raw/receita/2026-05/estabelecimentos/extracted/part.csv")))

    val statuses = RunStatusRegistry.scan(root.resolve("_atlas/status")).statuses
    assert(statuses.size === 6)
    assert(statuses.forall(status =>
      (status.inputPaths ++ status.outputPath.toSeq ++ status.qualityWarnings.map(_.reportPath))
        .forall(path => !path.contains("rebuild-staging"))
    ))
    val silver = statuses.find(status =>
      status.dataset == "establishments" && status.snapshot == "2026-06" && status.layer == "silver"
    ).get
    assert(silver.status === "success")
    assert(silver.outputRowCount.contains(1L))
    assert(silver.outputPath.contains(paths.silverCurrent.toString))
    assert(Files.exists(java.nio.file.Paths.get(silver.outputPath.get)))
    val bronze = statuses.find(status =>
      status.dataset == "estabelecimentos" && status.snapshot == "2026-06" && status.layer == "bronze"
    ).get
    assert(bronze.outputPath.contains(paths.bronzeRelease.toString))
    assert(Files.exists(java.nio.file.Paths.get(bronze.outputPath.get)))
  }

  test("reports missing intermediate raw months without rejecting the range") {
    val root = Files.createTempDirectory("atlas-full-rebuild-gap")
    writeRaw(root, "2026-05", "old@example.com")
    writeRaw(root, "2026-07", "new@example.com")
    val plan = EstablishmentRebuildService.plan(
      testConfig(root),
      ReleaseId.unsafe("2026-05"),
      ReleaseId.unsafe("2026-07")
    )

    assert(plan.releases.map(_.value) === Seq("2026-05", "2026-07"))
    assert(plan.missingMonths.map(_.value) === Seq("2026-06"))
  }

  test("restores the active generation when promotion fails") {
    val root = Files.createTempDirectory("atlas-full-rebuild-rollback")
    writeRaw(root, "2026-05", "old@example.com")
    val config = testConfig(root).copy(receita = testConfig(root).receita.copy(snapshot = "2026-05"))
    val activeBronze = root.resolve("bronze/receita/estabelecimentos")
    Files.createDirectories(activeBronze)
    Files.write(activeBronze.resolve("old.marker"), "old".getBytes(StandardCharsets.UTF_8))
    Files.write(root.resolve("silver"), "blocks target parent".getBytes(StandardCharsets.UTF_8))
    val plan = EstablishmentRebuildService.plan(config, ReleaseId.unsafe("2026-05"), ReleaseId.unsafe("2026-05"))

    intercept[Throwable](EstablishmentRebuildService.force(spark, config, plan))

    assert(Files.exists(activeBronze.resolve("old.marker")))
    assert(Files.exists(root.resolve("raw/receita/2026-05/estabelecimentos/extracted/part.csv")))
  }

  test("restores an interrupted promotion from its journal") {
    val root = Files.createTempDirectory("atlas-full-rebuild-interrupted")
    val config = testConfig(root)
    val staging = root.resolve("_atlas/rebuild-staging/interrupted")
    val trash = root.resolve("_atlas/_trash/interrupted/full-establishments-rebuild")
    val active = root.resolve("bronze/receita/estabelecimentos")
    val backup = trash.resolve("bronze")
    Files.createDirectories(active)
    Files.createDirectories(backup)
    Files.write(active.resolve("new.marker"), "new".getBytes(StandardCharsets.UTF_8))
    Files.write(backup.resolve("old.marker"), "old".getBytes(StandardCharsets.UTF_8))
    val journal = root.resolve("_atlas/transactions/establishments-rebuild.tsv")
    Files.createDirectories(journal.getParent)
    val stagedBronze = staging.resolve("bronze/receita/estabelecimentos")
    val lines = Seq(
      s"$staging\t$trash",
      Seq("bronze", active, stagedBronze, backup, true).mkString("\t")
    ).mkString("\n") + "\n"
    Files.write(journal, lines.getBytes(StandardCharsets.UTF_8))

    EstablishmentRebuildService.recoverInterrupted(config)

    assert(Files.exists(active.resolve("old.marker")))
    assert(Files.exists(trash.resolve("interrupted-staging/interrupted-promoted/bronze/new.marker")))
    assert(!Files.exists(journal))
  }

  private def testConfig(root: java.nio.file.Path): AtlasConfig = AtlasConfig(
    SparkConfig("local[2]", "atlas-tests", 2, root.resolve("spark-tmp").toString),
    CsvConfig(";", "UTF-8"),
    ReceitaConfig(
      "2026-06",
      root.resolve("raw/receita/2026-06/estabelecimentos/extracted").toString,
      root.resolve("bronze/receita").toString,
      root.resolve("silver/receita").toString
    ),
    root.resolve("_atlas/status").toString,
    "overwrite"
  )

  private def writeRaw(root: java.nio.file.Path, release: String, email: String): Unit = {
    val values = ReceitaSchemas.estabelecimentoColumns.map {
      case "cnpj_root" => "12345678"
      case "cnpj_branch" => "0001"
      case "cnpj_check" => "09"
      case "headquarters_branch_code" => "1"
      case "trade_name" => "Atlas"
      case "registration_status_code" => "02"
      case "registration_status_date" => "20240101"
      case "opening_date" => "20240131"
      case "main_cnae" => "6201501"
      case "secondary_cnaes" => "6202300"
      case "street_type" => "Rua"
      case "street_name" => "Exemplo"
      case "street_number" => "10"
      case "postal_code" => "12345678"
      case "state" => "PE"
      case "municipality_code" => "2531"
      case "ddd_1" => "81"
      case "phone_1" => "99998888"
      case "email" => email
      case _ => ""
    }
    val extracted = root.resolve(s"raw/receita/$release/estabelecimentos/extracted")
    Files.createDirectories(extracted)
    Files.write(extracted.resolve("part.csv"), (values.mkString(";") + "\n").getBytes(StandardCharsets.UTF_8))
  }
}
