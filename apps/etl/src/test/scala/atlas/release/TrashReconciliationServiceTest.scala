package atlas.release

import atlas.config.{AtlasConfig, CsvConfig, ReceitaConfig, SparkConfig}
import atlas.status.{RunStatus, RunStatusRegistry}
import java.nio.file.{Files, Path}
import java.time.Instant
import org.scalatest.funsuite.AnyFunSuite

class TrashReconciliationServiceTest extends AnyFunSuite {
  test("writes a recovery manifest only after active replacements and statuses are proven") {
    val root = Files.createTempDirectory("atlas-trash-reconcile")
    val config = testConfig(root)
    val legacy = root.resolve("_atlas/_trash/2026-07-01T120000000Z/full-establishments-rebuild")
    Files.createDirectories(legacy.resolve("bronze"))
    Files.createDirectories(root.resolve("bronze/receita/estabelecimentos"))
    Seq("bronze", "silver", "history").foreach(layer => writeStatus(config, layer))

    val dryRun = TrashReconciliationService.inspect(config)
    assert(dryRun.candidates.head.eligible)
    assert(!Files.exists(legacy.resolve(TrashManifest.FileName)))

    val forced = TrashReconciliationService.force(config)
    assert(forced.manifestsWritten === 1)
    assert(TrashManifest.read(legacy).replacementPaths.nonEmpty)
    assert(TrashPurgeService.inspect(config, olderThanDays = 0, Instant.parse("2026-07-02T12:00:00Z")).generations.head.eligible)
  }

  private def writeStatus(config: AtlasConfig, layer: String): Unit = RunStatusRegistry.write(
    java.nio.file.Paths.get(config.statusDir),
    RunStatus("receita", s"fixture-$layer", "2026-07", layer, "success", Instant.parse("2026-07-01T13:00:00Z"), Instant.parse("2026-07-01T13:00:00Z"), 0, Some(1), Seq.empty, Some(s"data/$layer"), Seq.empty, None, None, None)
  )

  private def testConfig(root: Path): AtlasConfig = AtlasConfig(
    SparkConfig("local[1]", "test", 1, root.resolve("spark-tmp").toString),
    CsvConfig(";", "UTF-8"),
    ReceitaConfig("2026-07", root.resolve("raw/receita/2026-07/estabelecimentos/extracted").toString, root.resolve("bronze/receita").toString, root.resolve("silver/receita").toString),
    root.resolve("_atlas/status").toString,
    "overwrite"
  )
}
