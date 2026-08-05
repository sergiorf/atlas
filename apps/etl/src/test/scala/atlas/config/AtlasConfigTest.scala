package atlas.config

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite

class AtlasConfigTest extends AnyFunSuite {
  test("loads the configured silver directory") {
    val file = Files.createTempFile("atlas-config", ".conf")
    val config = """atlas {
      |  status-dir = "custom-status"
      |  output.write-mode = "overwrite"
      |  csv {
      |    delimiter = ";"
      |    encoding = "ISO-8859-1"
      |  }
      |  spark {
      |    app-name = "test"
      |    master = "local[1]"
      |    shuffle-partitions = 1
      |    local-dir = "custom-tmp"
      |  }
      |  receita {
      |    snapshot = "2026-06"
      |    raw-dir = "raw"
      |    bronze-dir = "bronze"
      |    silver-dir = "custom-silver"
      |  }
      |}
      |""".stripMargin
    Files.write(file, config.getBytes(StandardCharsets.UTF_8))

    val loaded = AtlasConfig.load(file.toString)
    assert(loaded.spark.localDir === "custom-tmp")
    assert(loaded.statusDir === "custom-status")
    assert(loaded.receita.snapshot === "2026-06")
    assert(loaded.receita.silverDir === "custom-silver")
    assert(loaded.graph.maxComponentPropagationRounds === 128)
  }

  test("loads and validates the graph component propagation limit") {
    def config(rounds: Int): String = s"""atlas {
      |  status-dir = "status"
      |  output.write-mode = "overwrite"
      |  csv { delimiter = ";", encoding = "UTF-8" }
      |  spark {
      |    app-name = "test"
      |    master = "local[1]"
      |    shuffle-partitions = 1
      |    local-dir = "tmp"
      |  }
      |  graph.max-component-propagation-rounds = $rounds
      |  receita {
      |    snapshot = "2026-07"
      |    raw-dir = "raw"
      |    bronze-dir = "bronze"
      |    silver-dir = "silver"
      |  }
      |}
      |""".stripMargin

    val valid = Files.createTempFile("atlas-graph-config", ".conf")
    Files.write(valid, config(64).getBytes(StandardCharsets.UTF_8))
    assert(AtlasConfig.load(valid.toString).graph.maxComponentPropagationRounds === 64)

    val invalid = Files.createTempFile("atlas-invalid-graph-config", ".conf")
    Files.write(invalid, config(0).getBytes(StandardCharsets.UTF_8))
    val error = intercept[IllegalArgumentException](AtlasConfig.load(invalid.toString))
    assert(error.getMessage.contains("must be at least 1"))
  }
}
