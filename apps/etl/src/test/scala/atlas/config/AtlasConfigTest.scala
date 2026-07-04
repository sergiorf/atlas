package atlas.config

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite

class AtlasConfigTest extends AnyFunSuite {
  test("loads the configured silver directory") {
    val file = Files.createTempFile("atlas-config", ".conf")
    val config = """atlas {
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
      |    raw-dir = "raw"
      |    bronze-dir = "bronze"
      |    silver-dir = "custom-silver"
      |  }
      |}
      |""".stripMargin
    Files.write(file, config.getBytes(StandardCharsets.UTF_8))

    val loaded = AtlasConfig.load(file.toString)
    assert(loaded.spark.localDir === "custom-tmp")
    assert(loaded.receita.silverDir === "custom-silver")
  }
}
