package atlas.release

import atlas.config.{AtlasConfig, CsvConfig, ReceitaConfig, SparkConfig}
import com.typesafe.config.ConfigFactory
import java.nio.file.{Files, Path}
import org.scalatest.funsuite.AnyFunSuite

class WslReclaimPreflightServiceTest extends AnyFunSuite {
  test("reports filesystem and trash evidence without changing data") {
    val root = Files.createTempDirectory("atlas-wsl-preflight")
    val trash = root.resolve("_atlas/_trash/item.bin")
    Files.createDirectories(trash.getParent)
    Files.write(trash, Array.fill[Byte](5)(1))
    val config = testConfig(root)

    val result = WslReclaimPreflightService.inspect(config)
    val json = WslReclaimPreflightService.json(result)

    assert(result.filesystemTotalBytes > 0)
    assert(result.trashBytes >= 5)
    assert(Files.exists(trash))
    assert(ConfigFactory.parseString(json).getInt("contract_version") === 1)
    assert(WslReclaimPreflightService.render(result).contains("compact-atlas-wsl.ps1"))
  }

  private def testConfig(root: Path): AtlasConfig = AtlasConfig(
    SparkConfig("local[1]", "test", 1, root.resolve("spark-tmp").toString),
    CsvConfig(";", "UTF-8"),
    ReceitaConfig("2026-07", root.resolve("raw/receita/2026-07/estabelecimentos/extracted").toString, root.resolve("bronze/receita").toString, root.resolve("silver/receita").toString),
    root.resolve("_atlas/status").toString,
    "overwrite"
  )
}
