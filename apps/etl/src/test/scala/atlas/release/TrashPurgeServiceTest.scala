package atlas.release

import atlas.config.{AtlasConfig, CsvConfig, ReceitaConfig, SparkConfig}
import atlas.status.{RunStatus, RunStatusRegistry}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Instant
import org.scalatest.funsuite.AnyFunSuite

class TrashPurgeServiceTest extends AnyFunSuite {
  private val now = Instant.parse("2026-07-18T12:00:00Z")

  test("purges eligible generations independently and prunes empty timestamp directories") {
    val root = Files.createTempDirectory("atlas-purge-mixed")
    val config = testConfig(root)
    val old = operation(root, "2026-07-10T120000000000000Z", "stale-derived", "stale-derived")
    val young = operation(root, "2026-07-17T120000000000000Z", "failed-establishments-rebuild", "failed-rebuild")
    Files.write(old.resolve("old.bin"), Array.fill[Byte](5)(1))
    Files.write(young.resolve("young.bin"), Array.fill[Byte](7)(1))

    val result = TrashPurgeService.force(config, olderThanDays = 7, now)

    withClue(result.generations) { assert(!Files.exists(old)) }
    assert(Files.exists(young))
    assert(result.deletedBytes > 5)
    assert(result.skippedBytes > 7)
    assert(!Files.exists(old.getParent))
    assert(Files.exists(young.getParent))
  }

  test("uses seven full days as an inclusive eligibility boundary") {
    val root = Files.createTempDirectory("atlas-purge-boundary")
    val config = testConfig(root)
    operation(root, "2026-07-11T120000000000000Z", "stale-derived", "stale-derived")
    operation(root, "2026-07-11T120001000000000Z", "failed-establishments-rebuild", "failed-rebuild")

    val result = TrashPurgeService.inspect(config, now = now)

    withClue(result.generations) { assert(result.generations.count(_.eligible) === 1) }
    assert(result.generations.count(_.blockingReasons.exists(_.contains("younger"))) === 1)
  }

  test("blocks unknown layouts, symbolic links, and active journal references") {
    val root = Files.createTempDirectory("atlas-purge-blockers")
    val config = testConfig(root)
    val unknown = root.resolve("_atlas/_trash/not-a-time/mystery")
    Files.createDirectories(unknown)
    val linked = operation(root, "2026-07-01T120000000000000Z", "stale-derived", "stale-derived")
    Files.createSymbolicLink(linked.resolve("escape"), root.resolve("outside"))
    val journalled = operation(root, "2026-07-02T120000000000000Z", "failed-establishments-rebuild", "failed-rebuild")
    val journal = root.resolve("_atlas/transactions/establishments-rebuild.tsv")
    Files.createDirectories(journal.getParent)
    Files.write(journal, s"staging\t$journalled\n".getBytes(StandardCharsets.UTF_8))

    val result = TrashPurgeService.inspect(config, olderThanDays = 0, now)

    withClue(result.generations) { assert(result.generations.find(_.path == unknown).exists(_.blockingReasons.exists(_.contains("unknown")))) }
    assert(result.generations.find(_.path == linked).exists(_.blockingReasons.exists(_.contains("symbolic link"))))
    assert(result.generations.find(_.path == journalled).exists(_.blockingReasons.exists(_.contains("journal"))))
  }

  test("requires full rebuild replacements and complete canonical statuses") {
    val root = Files.createTempDirectory("atlas-purge-full")
    val config = testConfig(root)
    val full = operation(root, "2026-07-01T120000000000000Z", "full-establishments-rebuild", "full-rebuild-backup", Seq(root.resolve("active").toString))
    var result = TrashPurgeService.inspect(config, olderThanDays = 0, now)
    assert(result.generations.head.blockingReasons.exists(_.contains("required active output")))

    Files.createDirectories(root.resolve("active"))
    Seq("bronze", "silver", "history").foreach(layer => writeStatus(config, layer))
    result = TrashPurgeService.inspect(config, olderThanDays = 0, now)
    assert(result.generations.head.eligible)

    Files.write(root.resolve("_atlas/status/broken.json"), "broken".getBytes(StandardCharsets.UTF_8))
    result = TrashPurgeService.inspect(config, olderThanDays = 0, now)
    assert(result.generations.head.blockingReasons.exists(_.contains("malformed active status")))
    assert(Files.exists(full))
  }

  test("recognizes conservative legacy layouts but blocks legacy full rebuild backups") {
    val root = Files.createTempDirectory("atlas-purge-legacy")
    val config = testConfig(root)
    Files.createDirectories(root.resolve("_atlas/_trash/2026-07-01T120000000000000Z/release=2026-06/bronze"))
    Files.createDirectories(root.resolve("_atlas/_trash/2026-07-02T120000000000000Z/full-establishments-rebuild/bronze"))
    val result = TrashPurgeService.inspect(config, olderThanDays = 0, now)
    withClue(result.generations) { assert(result.generations.find(_.operationType == "release-drop").exists(_.eligible)) }
    assert(result.generations.find(_.operationType == "full-rebuild-backup").exists(_.blockingReasons.exists(_.contains("no replacement expectations"))))
  }

  test("force refuses a held establishment publication lock") {
    val root = Files.createTempDirectory("atlas-purge-lock")
    val config = testConfig(root)
    operation(root, "2026-07-01T120000000000000Z", "stale-derived", "stale-derived")
    PublicationLock.withEstablishmentsLock(config) {
      assertThrows[IllegalStateException](TrashPurgeService.force(config, olderThanDays = 0, now))
    }
  }

  test("blocks a malformed manifest without deleting its generation") {
    val root = Files.createTempDirectory("atlas-purge-manifest")
    val config = testConfig(root)
    val generation = root.resolve("_atlas/_trash/2026-07-01T120000000000000Z/stale-derived")
    Files.createDirectories(generation)
    Files.write(generation.resolve(TrashManifest.FileName), "broken".getBytes(StandardCharsets.UTF_8))
    val result = TrashPurgeService.force(config, olderThanDays = 0, now)
    assert(result.generations.head.blockingReasons.exists(_.contains("malformed trash manifest")))
    assert(Files.exists(generation))
  }

  private def operation(root: Path, timestamp: String, name: String, operationType: String, replacements: Seq[String] = Seq.empty): Path = {
    val path = root.resolve("_atlas/_trash").resolve(timestamp).resolve(name)
    TrashManifest.write(path, TrashManifest(operationType, now, Seq(root.resolve("original").toString), replacements, Seq("fixture")))
    path
  }

  private def writeStatus(config: AtlasConfig, layer: String): Unit = RunStatusRegistry.write(
    java.nio.file.Paths.get(config.statusDir),
    RunStatus("receita", if (layer == "silver") "establishments" else "estabelecimentos", "2026-06", layer, "success", now, now, 0, Some(1), Seq("data/input"), Some(s"data/$layer/output"), Seq.empty, None, None, None)
  )

  private def testConfig(root: Path): AtlasConfig = AtlasConfig(
    SparkConfig("local[2]", "atlas-tests", 2, root.resolve("spark-tmp").toString),
    CsvConfig(";", "UTF-8"),
    ReceitaConfig("2026-06", root.resolve("raw/receita/2026-06/estabelecimentos/extracted").toString, root.resolve("bronze/receita").toString, root.resolve("silver/receita").toString),
    root.resolve("_atlas/status").toString,
    "overwrite"
  )
}
