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
  }

  test("missing derived path is idempotent") {
    val root = Files.createTempDirectory("atlas-release-missing")
    val result = ReleaseDropService.force(sampleConfig(root), ReleaseId.unsafe("2026-07"), ReleaseLayer.Bronze)
    assert(result.entries.forall(!_.exists))
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
