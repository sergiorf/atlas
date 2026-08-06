package atlas

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import atlas.status.RunStatusRegistry
import org.scalatest.funsuite.AnyFunSuite

class MainTest extends AnyFunSuite {
  test("parses controlled lead export filters") {
    val cli = Main.parseArgs(List(
      "export-leads", "--group", "software_services", "--state", "PE",
      "--opened-from", "2026-07-01", "--opened-before", "2026-08-01",
      "--output", "/tmp/leads", "--limit", "500"
    ))
    assert(cli.command === "export-leads")
    assert(cli.group.contains("software_services"))
    assert(cli.state.contains("PE"))
    assert(cli.limit === 500)
  }
  test("parses the status command") {
    assert(Main.parseArgs(List("status")) === Main.Cli("status"))
    assert(Main.parseArgs(List("status", "--json")) === Main.Cli("status", json = true))
    assert(Main.parseArgs(List("status", "--verbose")) === Main.Cli("status", verbose = true))
    assert(
      Main.parseArgs(List("status", "--release", "2026-07", "--verbose")) ===
        Main.Cli("status", release = Some("2026-07"), verbose = true)
    )
    assert(
      Main.parseArgs(List("status", "--verbose", "--release", "2026-07")) ===
        Main.Cli("status", release = Some("2026-07"), verbose = true)
    )
  }

  test("rejects ambiguous or invalid status options") {
    Seq(
      List("status", "--json", "--verbose"),
      List("status", "--json", "--release", "2026-07"),
      List("status", "--release"),
      List("status", "--release", "invalid"),
      List("status", "--verbose", "--verbose"),
      List("status", "--unknown")
    ).foreach(args => intercept[IllegalArgumentException](Main.parseArgs(args)))
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
    assert(
      Main.statusOutput(root.toString, Main.StatusOptions(json = true)) ===
        RunStatusRegistry.jsonArray(Seq.empty)
    )
    intercept[IllegalArgumentException](
      Main.statusOutput(root.toString, Main.StatusOptions(release = Some("2026-07")))
    )
  }

  test("status output reports a malformed file without throwing") {
    val root = Files.createTempDirectory("atlas-malformed-status")
    val file = root.resolve("broken.json")
    Files.write(file, "broken".getBytes(StandardCharsets.UTF_8))
    val output = Main.statusOutput(root.toString)
    assert(output.contains("REGISTRY ERRORS"))
    assert(output.contains("Malformed status file"))
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

  test("parses guarded refresh and full rebuild options") {
    assert(
      Main.parseArgs(
        List("refresh-receita-estabelecimentos", "--release", "2026-07", "--allow-legacy-current")
      ) ===
        Main.Cli(
          "refresh-receita-estabelecimentos",
          release = Some("2026-07"),
          allowLegacyCurrent = true
        )
    )
    assert(
      Main.parseArgs(
        List(
          "releases",
          "rebuild-establishments",
          "--from-release",
          "2026-05",
          "--to-release",
          "2026-06"
        )
      ) ===
        Main.Cli(
          "releases-rebuild-establishments",
          fromRelease = Some("2026-05"),
          toRelease = Some("2026-06")
        )
    )
    assert(
      Main.parseArgs(
        List(
          "releases",
          "rebuild-establishments",
          "--from-release",
          "2026-05",
          "--to-release",
          "2026-06",
          "--force"
        )
      ) ===
        Main.Cli(
          "releases-rebuild-establishments",
          force = true,
          fromRelease = Some("2026-05"),
          toRelease = Some("2026-06")
        )
    )
    assert(
      Main.parseArgs(
        List(
          "releases",
          "rebuild-company-data",
          "--from-release",
          "2026-05",
          "--to-release",
          "2026-07",
          "--force"
        )
      ) ===
        Main.Cli(
          "releases-rebuild-company-data",
          force = true,
          fromRelease = Some("2026-05"),
          toRelease = Some("2026-07")
        )
    )
    assert(
      Main.parseArgs(List("releases", "inspect-bundle")) === Main.Cli("releases-inspect-bundle")
    )
    assert(
      Main.parseArgs(List("releases", "inspect-bundle", "--release", "2026-07")) ===
        Main.Cli("releases-inspect-bundle", release = Some("2026-07"))
    )
    assert(
      Main.parseArgs(
        List("releases", "validate-bundle", "--bundle-id", "bundle-july", "--full", "--json")
      ) === Main.Cli(
        "releases-validate-bundle", json = true, bundleId = Some("bundle-july"), full = true
      )
    )
    assert(
      Main.parseArgs(List("releases", "validate-bundle")) ===
        Main.Cli("releases-validate-bundle")
    )
    assert(Main.helpText.contains("releases validate-bundle"))
    assert(
      Main.parseArgs(List("refresh-receita-company-data", "--release", "2026-08")) ===
        Main.Cli("refresh-receita-company-data", release = Some("2026-08"))
    )
  }

  test("parses release lifecycle commands") {
    assert(Main.parseArgs(List("releases", "list")) === Main.Cli("releases-list"))
    assert(
      Main.parseArgs(List("releases", "inspect", "--release", "2026-07")) ===
        Main.Cli("releases-inspect", release = Some("2026-07"))
    )
    assert(
      Main.parseArgs(
        List("releases", "drop-derived", "--release", "2026-07", "--layer", "bronze", "--force")
      ) ===
        Main.Cli(
          "releases-drop-derived",
          release = Some("2026-07"),
          layer = Some("bronze"),
          force = true
        )
    )
    assert(
      Main.parseArgs(List("releases", "drop-stale-derived", "--force")) ===
        Main.Cli("releases-drop-stale-derived", force = true)
    )
  }

  test("parses trash purge retention and force options") {
    assert(Main.parseArgs(List("releases", "purge-trash")) === Main.Cli("releases-purge-trash"))
    assert(
      Main.parseArgs(List("releases", "purge-trash", "--force")) === Main.Cli(
        "releases-purge-trash",
        force = true
      )
    )
    assert(
      Main.parseArgs(List("releases", "purge-trash", "--older-than-days", "0", "--force")) ===
        Main.Cli("releases-purge-trash", force = true, olderThanDays = 0)
    )
    assertThrows[IllegalArgumentException](
      Main.parseArgs(List("releases", "purge-trash", "--older-than-days", "-1"))
    )
    assert(Main.helpText.contains("releases purge-trash"))
    assert(Main.helpText.contains("seven-day recovery window"))
  }

  test("parses read-only storage usage options") {
    assert(Main.parseArgs(List("storage", "usage")) === Main.Cli("storage-usage"))
    assert(
      Main.parseArgs(
        List("storage", "usage", "--category", "raw", "--release", "2026-07", "--top", "5")
      ) === Main.Cli(
        "storage-usage",
        release = Some("2026-07"),
        category = Some("raw"),
        top = 5
      )
    )
    assert(
      Main.parseArgs(List("storage", "usage", "--json")) === Main.Cli("storage-usage", json = true)
    )
    assertThrows[IllegalArgumentException](Main.parseArgs(List("storage", "usage", "--top", "0")))
    assertThrows[IllegalArgumentException](
      Main.parseArgs(List("storage", "usage", "--category", "downloads"))
    )
    assert(Main.helpText.contains("storage usage"))
  }

  test("parses unified storage cleanup options") {
    assert(Main.parseArgs(List("storage", "cleanup")) === Main.Cli("storage-cleanup"))
    assert(
      Main.parseArgs(List("storage", "cleanup", "--older-than-days", "0", "--force")) ===
        Main.Cli("storage-cleanup", force = true, olderThanDays = 0)
    )
    assert(
      Main.parseArgs(List("storage", "cleanup", "--json")) ===
        Main.Cli("storage-cleanup", json = true)
    )
    Seq(
      List("storage", "cleanup", "--json", "--force"),
      List("storage", "cleanup", "--force", "--dry-run"),
      List("storage", "cleanup", "--older-than-days", "-1"),
      List("storage", "cleanup", "--older-than-days", "7", "--older-than-days", "0")
    ).foreach(args => assertThrows[IllegalArgumentException](Main.parseArgs(args)))
    assert(Main.helpText.contains("storage cleanup"))
  }
}
