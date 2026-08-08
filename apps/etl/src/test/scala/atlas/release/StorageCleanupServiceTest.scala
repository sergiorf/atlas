package atlas.release

import atlas.config.{AtlasConfig, CsvConfig, ReceitaConfig, SparkConfig}
import atlas.status.{RunStatus, RunStatusRegistry}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Instant
import org.scalatest.funsuite.AnyFunSuite

class StorageCleanupServiceTest extends AnyFunSuite {
  private val now = Instant.parse("2026-07-20T12:00:00Z")
  private val old = Instant.parse("2026-07-10T12:00:00Z")

  test("dry run combines eligible trash and failed bundles without changing files") {
    val root = Files.createTempDirectory("atlas-storage-cleanup-plan")
    val config = testConfig(root)
    val failed = failedCandidate(root, "2026-06-11111111-1111-1111-1111-111111111111", old)
    val trash = root.resolve("_atlas/_trash/2026-07-10T120000000Z/stale-derived")
    TrashManifest.write(
      trash,
      TrashManifest("stale-derived", old, Seq(root.resolve("old").toString), Seq.empty, Seq("test"))
    )
    Files.write(trash.resolve("file"), Array.fill[Byte](7)(1))

    val result = StorageCleanupService.inspect(config, olderThanDays = 7, now)

    assert(result.dryRun)
    assert(result.failedBundles.head.eligible)
    assert(result.trash.generations.head.eligible)
    assert(Files.exists(failed))
    assert(Files.exists(trash))
    assert(StorageCleanupService.render(result).contains("Eligible for quarantine"))
    assert(StorageCleanupService.json(result).contains("\"contract_version\":2"))
  }

  test(
    "force deletes old trash and quarantines failed bundles without deleting them in the same invocation"
  ) {
    val root = Files.createTempDirectory("atlas-storage-cleanup-force")
    val config = testConfig(root)
    val failed = failedCandidate(root, "2026-06-22222222-2222-2222-2222-222222222222", old)
    val trash = root.resolve("_atlas/_trash/2026-07-10T120000000Z/stale-derived")
    TrashManifest.write(
      trash,
      TrashManifest("stale-derived", old, Seq(root.resolve("old").toString), Seq.empty, Seq("test"))
    )
    Files.write(trash.resolve("file"), Array.fill[Byte](7)(1))

    val result = StorageCleanupService.force(config, olderThanDays = 7, now)
    val quarantined = root
      .resolve("_atlas/_trash/2026-07-20T120000Z")
      .resolve("failed-company-bundle-2026-06-22222222-2222-2222-2222-222222222222")

    assert(!Files.exists(trash))
    assert(!Files.exists(failed))
    assert(Files.exists(quarantined))
    assert(TrashManifest.read(quarantined).operationType === "failed-company-bundle")
    assert(
      RunStatusRegistry
        .scan(quarantined.resolve("data/_atlas/status"))
        .statuses
        .flatMap(_.outputPath)
        .forall(_.startsWith(quarantined.toString))
    )
    assert(result.trash.deletedBytes > 0)
    assert(result.quarantinedBytes > 0)

    StorageCleanupService.force(config, olderThanDays = 0, now)
    assert(!Files.exists(quarantined))
  }

  test("blocks young, malformed, symbolic, active-status, and current-pointer candidates") {
    val root = Files.createTempDirectory("atlas-storage-cleanup-blocked")
    val config = testConfig(root)
    failedCandidate(
      root,
      "2026-07-33333333-3333-3333-3333-333333333333",
      Instant.parse("2026-07-19T12:00:00Z")
    )
    val referenced =
      failedCandidate(root, "2026-06-44444444-4444-4444-4444-444444444444", old)
    writeStatus(
      java.nio.file.Paths.get(config.statusDir),
      old,
      Some(referenced.resolve("data/output").toString)
    )
    val current =
      failedCandidate(root, "2026-05-55555555-5555-5555-5555-555555555555", old)
    val pointer = root.resolve("_atlas/bundles/current_bundle.json")
    Files.createDirectories(pointer.getParent)
    Files.writeString(
      pointer,
      """{"bundle_id":"2026-05-55555555-5555-5555-5555-555555555555","release":"2026-05"}"""
    )
    val linked =
      failedCandidate(root, "2026-04-66666666-6666-6666-6666-666666666666", old)
    Files.createSymbolicLink(linked.resolve("escape"), root.resolve("outside"))
    Files.writeString(linked.resolve("bundle-manifest.json"), "broken")
    val malformed = root.resolve("_atlas/bundles/failed/not-a-bundle")
    Files.createDirectories(malformed)

    val result = StorageCleanupService.inspect(config, olderThanDays = 7, now)

    assert(result.failedBundles.forall(!_.eligible))
    assert(result.failedBundles.exists(_.blockingReasons.exists(_.contains("younger"))))
    assert(
      result.failedBundles
        .find(_.path == referenced)
        .exists(_.blockingReasons.exists(_.contains("active status")))
    )
    assert(
      result.failedBundles
        .find(_.path == current)
        .exists(_.blockingReasons.exists(_.contains("current bundle")))
    )
    assert(
      result.failedBundles
        .find(_.path == linked)
        .exists(_.blockingReasons.exists(_.contains("symbolic link")))
    )
    assert(
      result.failedBundles
        .find(_.path == linked)
        .exists(_.blockingReasons.exists(_.contains("malformed failed bundle manifest")))
    )
    assert(
      result.failedBundles
        .find(_.path == malformed)
        .exists(_.blockingReasons.exists(_.contains("unrecognized")))
    )
  }

  test("force refuses a held company bundle lock") {
    val root = Files.createTempDirectory("atlas-storage-cleanup-lock")
    val config = testConfig(root)
    failedCandidate(root, "2026-06-77777777-7777-7777-7777-777777777777", old)
    PublicationLock.withCompanyBundleLock(config) {
      assertThrows[IllegalStateException](StorageCleanupService.force(config, 7, now))
    }
  }

  test("protects current and predecessor bundles and selects older bronze and completed work") {
    val root = Files.createTempDirectory("atlas-storage-cleanup-v2")
    val config = testConfig(root)
    val first = bundle(root, "2026-05-11111111-1111-1111-1111-111111111111", "2026-05", None)
    val second = bundle(root, "2026-06-22222222-2222-2222-2222-222222222222", "2026-06", Some(first.getFileName.toString))
    val current = bundle(root, "2026-07-33333333-3333-3333-3333-333333333333", "2026-07", Some(second.getFileName.toString))
    val pointer = root.resolve("_atlas/bundles/current_bundle.json")
    Files.writeString(pointer, s"""{"bundle_id":"${current.getFileName}","release":"2026-07"}""")

    Seq("2026-05", "2026-06", "2026-07").foreach { release =>
      Files.createDirectories(root.resolve(s"raw/receita/$release/estabelecimentos/extracted"))
      val bronze = root.resolve(s"bronze/receita/estabelecimentos/release=$release")
      Files.createDirectories(bronze)
      Files.write(bronze.resolve("part.parquet"), Array[Byte](1))
      writeSuccessfulStatus(config, release, "bronze", Some(bronze.toString))
    }
    val work = root.resolve("_atlas/work/receita/estabelecimentos/release=2026-06/silver_candidate")
    Files.createDirectories(work)
    Files.write(work.resolve("part.parquet"), Array[Byte](1))
    writeSuccessfulStatus(config, "2026-06", "silver", Some(work.toString))

    val policy = StorageCleanupPolicy(0, 0, 2, 1, StorageCleanupPolicy.Kinds)
    val result = StorageCleanupService.inspect(config, policy, now)

    assert(result.candidates.find(_.path == first).exists(_.eligible))
    assert(result.candidates.find(_.path == second).exists(_.blockingReasonCodes.contains("retained_predecessor")))
    assert(result.candidates.find(_.path == current).exists(_.blockingReasonCodes.contains("current_bundle")))
    assert(result.candidates.find(_.id == "2026-05").exists(candidate => candidate.kind == "bronze" && candidate.eligible))
    assert(result.candidates.find(candidate => candidate.kind == "bronze" && candidate.id == "2026-07").exists(_.blockingReasonCodes.contains("retention_count")))
    assert(result.candidates.find(_.kind == "work").exists(_.eligible))
  }

  test("inactive bundle cleanup fails closed for malformed pointer and manifest") {
    val root = Files.createTempDirectory("atlas-storage-cleanup-v2-malformed")
    val config = testConfig(root)
    val generation = root.resolve("_atlas/bundles/generations/2026-05-11111111-1111-1111-1111-111111111111")
    Files.createDirectories(generation)
    Files.writeString(generation.resolve("bundle-manifest.json"), "broken")
    Files.writeString(root.resolve("_atlas/bundles/current_bundle.json"), "broken")

    val result = StorageCleanupService.inspect(config, StorageCleanupPolicy(0, 0, 2, 1, Set("inactive-bundles")), now)

    assert(result.candidates.head.blockingReasonCodes.contains("malformed_pointer"))
    assert(result.candidates.head.blockingReasonCodes.contains("malformed_manifest"))
  }

  private def failedCandidate(root: Path, bundleId: String, finishedAt: Instant): Path = {
    val candidate = root.resolve("_atlas/bundles/failed").resolve(bundleId)
    Files.createDirectories(candidate.resolve("data"))
    Files.write(candidate.resolve("data/file"), Array.fill[Byte](11)(1))
    writeStatus(candidate.resolve("data/_atlas/status"), finishedAt, Some(candidate.toString))
    candidate
  }

  private def bundle(root: Path, id: String, release: String, previous: Option[String]): Path = {
    val path = root.resolve("_atlas/bundles/generations").resolve(id)
    Files.createDirectories(path)
    Files.writeString(path.resolve("data.parquet"), "fixture")
    val previousJson = previous.fold("null")(value => s""""$value"""")
    Files.writeString(path.resolve("bundle-manifest.json"), s"""{"bundle_id":"$id","release":"$release","created_at":"$old","previous_bundle_id":$previousJson}""")
    path
  }

  private def writeSuccessfulStatus(config: AtlasConfig, release: String, layer: String, output: Option[String]): Unit =
    RunStatusRegistry.write(
      java.nio.file.Paths.get(config.statusDir),
      RunStatus("receita", s"fixture-$release-$layer", release, layer, "success", old, old, 0, Some(1), Seq.empty, output, Seq.empty, None, None, None)
    )

  private def writeStatus(root: Path, finishedAt: Instant, output: Option[String]): Unit =
    RunStatusRegistry.write(
      root,
      RunStatus(
        "receita",
        "companies",
        "2026-06",
        "silver",
        "failed",
        finishedAt,
        finishedAt,
        0,
        None,
        Seq.empty,
        output,
        Seq.empty,
        Some("1"),
        Some("test"),
        Some("test"),
        Some("failure"),
        Some("fixture")
      )
    )

  private def testConfig(root: Path): AtlasConfig =
    AtlasConfig(
      SparkConfig("local[1]", "test", 1, root.resolve("spark-tmp").toString),
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
}
