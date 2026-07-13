package atlas.common

import atlas.config.ReceitaConfig
import org.scalatest.funsuite.AnyFunSuite

class DatasetPathsTest extends AnyFunSuite {
  test("selects the raw directory matching the requested release") {
    val config = ReceitaConfig(
      "2026-05",
      "data/raw/receita/2026-06/estabelecimentos/extracted",
      "data/bronze/receita",
      "data/silver/receita"
    )

    val paths = DatasetPaths.estabelecimentos(config)

    assert(paths.input === "data/raw/receita/2026-05/estabelecimentos/extracted/*")
    assert(paths.output === "data/bronze/receita/estabelecimentos/release=2026-05")
  }

  test("preserves an explicit raw directory without a release segment") {
    val config = ReceitaConfig(
      "2026-05",
      "/mnt/receita/current/estabelecimentos/extracted",
      "data/bronze/receita",
      "data/silver/receita"
    )

    assert(
      DatasetPaths.estabelecimentos(config).input ===
        "/mnt/receita/current/estabelecimentos/extracted/*"
    )
  }
}
