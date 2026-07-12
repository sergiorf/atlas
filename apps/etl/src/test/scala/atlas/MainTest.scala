package atlas

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite

class MainTest extends AnyFunSuite {
  test("parses the status command") {
    assert(Main.parseArgs(List("status")) === Main.Cli("status"))
    assert(Main.parseArgs(List("status", "--json")) === Main.Cli("status", json = true))
  }

  test("help explains command effects and common options") {
    val help = Main.helpText

    assert(help.contains("Pipeline commands:"))
    assert(help.contains("Raw input files are never modified."))
    assert(help.contains("publish the release as latest current"))
    assert(help.contains("LAYER is one of bronze, silver, reports"))
    assert(help.contains("--config PATH"))
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

  test("parses release lifecycle commands") {
    assert(Main.parseArgs(List("releases", "list")) === Main.Cli("releases-list"))
    assert(
      Main.parseArgs(List("releases", "inspect", "--release", "2026-07")) ===
        Main.Cli("releases-inspect", release = Some("2026-07"))
    )
    assert(
      Main.parseArgs(List("releases", "drop-derived", "--release", "2026-07", "--layer", "bronze", "--force")) ===
        Main.Cli("releases-drop-derived", release = Some("2026-07"), layer = Some("bronze"), force = true)
    )
    assert(
      Main.parseArgs(List("releases", "drop-stale-derived", "--force")) ===
        Main.Cli("releases-drop-stale-derived", force = true)
    )
  }
}
