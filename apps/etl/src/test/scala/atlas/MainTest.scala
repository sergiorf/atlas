package atlas

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite

class MainTest extends AnyFunSuite {
  test("parses the status command") {
    assert(Main.parseArgs(List("status")) === Main.Cli("status"))
  }

  test("status output explains an empty registry") {
    val root = Files.createTempDirectory("atlas-empty-status")
    assert(Main.statusOutput(root.toString).contains("No ETL status has been recorded yet"))
  }

  test("status output reports a malformed file without throwing") {
    val root = Files.createTempDirectory("atlas-malformed-status")
    val file = root.resolve("broken.json")
    Files.write(file, "broken".getBytes(StandardCharsets.UTF_8))
    assert(Main.statusOutput(root.toString).contains("Malformed status file"))
  }

  test("parses the silver normalization command") {
    assert(
      Main.parseArgs(List("normalize-receita-estabelecimentos")) ===
        Main.Cli("normalize-receita-estabelecimentos")
    )
    assert(
      Main.parseArgs(List("normalize-receita-estabelecimentos", "--config", "custom.conf")) ===
        Main.Cli("normalize-receita-estabelecimentos", "custom.conf")
    )
  }
}
