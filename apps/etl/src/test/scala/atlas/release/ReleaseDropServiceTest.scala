package atlas.release

import atlas.config.{AtlasConfig, CsvConfig, ReceitaConfig, SparkConfig}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite

class ReleaseDropServiceTest extends AnyFunSuite {
  test("dry run does not delete and force quarantines only derived files") {
    val root = Files.createTempDirectory("atlas-release-drop")
    val bronze = root.resolve("bronze/receita/estabelecimentos/release=2026-07")
    val raw = root.resolve("raw/receita/2026-07/estabelecimentos/extracted")
    Files.createDirectories(bronze)
    Files.createDirectories(raw)
    Files.write(bronze.resolve("part.parquet"), "derived".getBytes(StandardCharsets.UTF_8))
    Files.write(raw.resolve("source.csv"), "raw".getBytes(StandardCharsets.UTF_8))
    val config = sampleConfig(root)

    val dryRun = ReleaseDropService.plan(config, ReleaseId.unsafe("2026-07"), ReleaseLayer.Bronze)
    assert(dryRun.entries.exists(e => e.label == "bronze" && e.exists))
    assert(Files.exists(bronze))

    val forced = ReleaseDropService.force(config, ReleaseId.unsafe("2026-07"), ReleaseLayer.Bronze)
    assert(!Files.exists(bronze))
    assert(Files.exists(raw.resolve("source.csv")))
    assert(forced.trashRoot.exists(path => Files.exists(path.resolve("bronze/part.parquet"))))
    assert(forced.trashRoot.exists(path => Files.exists(path.resolve(TrashManifest.FileName))))
    assert(forced.trashRoot.map(TrashManifest.read).exists(_.operationType == "release-drop"))
  }

  test("missing derived path is idempotent") {
    val root = Files.createTempDirectory("atlas-release-missing")
    val result = ReleaseDropService.force(sampleConfig(root), ReleaseId.unsafe("2026-07"), ReleaseLayer.Bronze)
    assert(result.entries.forall(!_.exists))
  }

  test("history release lifecycle includes events and summaries") {
    val root = Files.createTempDirectory("atlas-release-history-drop")
    val config = sampleConfig(root)
    val paths = ReleasePaths(config)
    Files.createDirectories(paths.historyRelease)
    Files.createDirectories(paths.summaryRelease)
    Files.write(paths.historyRelease.resolve("events"), "events".getBytes(StandardCharsets.UTF_8))
    Files.write(paths.summaryRelease.resolve("summary"), "summary".getBytes(StandardCharsets.UTF_8))

    val result = ReleaseDropService.force(config, ReleaseId.unsafe("2026-07"), ReleaseLayer.History)
    assert(result.entries.map(_.label) === Seq("history", "summary"))
    assert(!Files.exists(paths.historyRelease))
    assert(!Files.exists(paths.summaryRelease))
    assert(result.trashRoot.exists(root => Files.exists(root.resolve("summary/summary"))))
  }

  test("stale derived cleanup quarantines legacy silver outputs only") {
    val root = Files.createTempDirectory("atlas-stale-derived")
    val legacy = root.resolve("silver/receita/establishments")
    val current = root.resolve("silver/receita/establishments_current")
    val history = root.resolve("silver/receita/establishment_change_events/to_release=2026-07")
    val oldReport = root.resolve("silver/receita/establishments_quality_report.json")
    Files.createDirectories(legacy)
    Files.createDirectories(current)
    Files.createDirectories(history)
    Files.write(legacy.resolve("part.parquet"), "legacy".getBytes(StandardCharsets.UTF_8))
    Files.write(current.resolve("part.parquet"), "current".getBytes(StandardCharsets.UTF_8))
    Files.write(history.resolve("part.parquet"), "history".getBytes(StandardCharsets.UTF_8))
    Files.write(oldReport, "report".getBytes(StandardCharsets.UTF_8))
    val config = sampleConfig(root)

    val dryRun = StaleDerivedCleanupService.plan(config)
    assert(dryRun.entries.exists(e => e.label == "legacy_silver_establishments" && e.exists))
    assert(Files.exists(legacy))

    val forced = StaleDerivedCleanupService.force(config)
    assert(!Files.exists(legacy))
    assert(!Files.exists(oldReport))
    assert(Files.exists(current.resolve("part.parquet")))
    assert(Files.exists(history.resolve("part.parquet")))
    assert(forced.trashRoot.exists(path => Files.exists(path.resolve("legacy_silver_establishments/part.parquet"))))
    assert(forced.trashRoot.exists(path => Files.exists(path.resolve("legacy_silver_quality_report_json"))))
    assert(forced.trashRoot.map(TrashManifest.read).exists(_.operationType == "stale-derived"))
  }

  private def sampleConfig(root: java.nio.file.Path): AtlasConfig = AtlasConfig(
    SparkConfig("local[1]", "atlas-tests", 1, root.resolve("spark-tmp").toString),
    CsvConfig(";", "UTF-8"),
    ReceitaConfig(
      "2026-07",
      root.resolve("raw/receita/2026-07/estabelecimentos/extracted").toString,
      root.resolve("bronze/receita").toString,
      root.resolve("silver/receita").toString
    ),
    root.resolve("_atlas/status").toString,
    "overwrite"
  )
}
