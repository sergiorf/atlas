package atlas.release

import atlas.config.{AtlasConfig, CsvConfig, ReceitaConfig, SparkConfig}
import java.nio.file.{Files, Path}
import org.scalatest.funsuite.AnyFunSuite

class StorageUsageServiceTest extends AnyFunSuite {
  test("partitions raw, bundle, staging, trash, and Spark storage without double counting") {
    val root = Files.createTempDirectory("atlas-storage")
    val config = testConfig(root)
    write(root.resolve("data/raw/receita/2026-07/company-data/archive.zip"), 11)
    write(root.resolve("data/_atlas/bundles/generations/2026-07-id/data/file.parquet"), 13)
    write(root.resolve("data/_atlas/bundles/staging/2026-08-id/data/file.parquet"), 17)
    write(root.resolve("data/_atlas/bundles/failed/2026-08-id/data/file.parquet"), 5)
    write(root.resolve("data/_atlas/_trash/2026-07-01T000000Z/stale-derived/file"), 19)
    write(root.resolve("spark-tmp/block"), 23)

    val result = StorageUsageService.inspect(config)
    val sizes = result.locations.groupBy(_.category).mapValues(_.map(_.bytes).sum)

    assert(sizes("raw") === 11)
    assert(sizes("bundles") === 13)
    assert(sizes("staging") === 22)
    assert(sizes("trash") === 19)
    assert(sizes("spark") === 23)
    assert(result.locations.map(_.bytes).sum === 88)
    assert(result.locations.find(_.category == "raw").exists(_.policy == "protected_raw"))
    assert(result.locations.find(_.category == "trash").exists(_.action.contains("purge-trash")))
  }

  test("filters storage by release and category") {
    val root = Files.createTempDirectory("atlas-storage-filter")
    val config = testConfig(root)
    write(root.resolve("data/raw/receita/2026-06/company-data/archive.zip"), 7)
    write(root.resolve("data/raw/receita/2026-07/company-data/archive.zip"), 9)
    write(root.resolve("data/_atlas/bundles/generations/2026-07-id/data/file"), 15)

    val raw = StorageUsageService.inspect(config, Some("raw"), Some("2026-07"))

    assert(raw.locations.map(_.category).toSet === Set("raw"))
    assert(raw.locations.map(_.bytes).sum === 9)
    assert(raw.release.contains("2026-07"))
  }

  test("renders human output and versioned exact-byte JSON") {
    val root = Files.createTempDirectory("atlas-storage-render")
    val config = testConfig(root)
    write(root.resolve("data/raw/receita/2026-07/file"), 1024)
    val result = StorageUsageService.inspect(config)

    val text = StorageUsageService.render(result)
    val json = StorageUsageService.json(result)

    assert(text.contains("ATLAS STORAGE USAGE"))
    assert(text.contains("1.0 KiB"))
    assert(text.contains("protected_raw"))
    assert(json.contains("\"contract_version\":1"))
    assert(json.contains("\"bytes\":1024"))
  }

  test("rejects unknown categories") {
    val root = Files.createTempDirectory("atlas-storage-invalid")
    intercept[IllegalArgumentException](
      StorageUsageService.inspect(testConfig(root), category = Some("downloads"))
    )
  }

  private def write(path: Path, bytes: Int): Unit = {
    Files.createDirectories(path.getParent)
    Files.write(path, Array.fill[Byte](bytes)(1))
  }

  private def testConfig(root: Path): AtlasConfig =
    AtlasConfig(
      SparkConfig("local[1]", "test", 1, root.resolve("spark-tmp").toString),
      CsvConfig(";", "ISO-8859-1"),
      ReceitaConfig(
        "2026-07",
        root.resolve("data/raw/receita/2026-07/estabelecimentos/extracted").toString,
        root.resolve("data/bronze/receita").toString,
        root.resolve("data/silver/receita").toString,
        root.resolve("data/raw/receita/2026-07/company-data").toString
      ),
      root.resolve("data/_atlas/status").toString,
      "overwrite"
    )
}
