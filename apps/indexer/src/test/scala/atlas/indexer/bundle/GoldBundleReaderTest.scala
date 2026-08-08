package atlas.indexer.bundle

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Instant
import org.scalatest.funsuite.AnyFunSuite

class GoldBundleReaderTest extends AnyFunSuite {
  test("reads current and explicitly selected validated gold components") {
    val fixture = writeFixture()
    val reader = new GoldBundleReader(fixture.bundleRoot)

    val current = reader.readCurrent()
    val selected = reader.readSelected("2026-07-fixture")

    assert(current.manifest.bundleId === "2026-07-fixture")
    assert(current.manifest.release === "2026-07")
    assert(current.companyProfiles === fixture.generation.resolve("data/gold/receita/company_profiles_current"))
    assert(current.leads === fixture.generation.resolve("data/gold/receita/leads_new_companies_current"))
    assert(selected.manifestSha256 === current.manifestSha256)
  }

  test("rejects traversal, changed data, and non-full validation") {
    val traversal = writeFixture(componentPath = Some("../outside"))
    val traversalError = intercept[IllegalArgumentException](
      new GoldBundleReader(traversal.bundleRoot).readCurrent())
    assert(traversalError.getMessage.contains("escapes"))

    val changed = writeFixture()
    Files.writeString(changed.generation.resolve(
      "data/gold/receita/company_profiles_current/part.parquet"), "changed", StandardCharsets.UTF_8)
    val hashError = intercept[IllegalArgumentException](
      new GoldBundleReader(changed.bundleRoot).readCurrent())
    assert(hashError.getMessage.contains("Component hash mismatch"))

    val structural = writeFixture(validationMode = "structural")
    val modeError = intercept[IllegalArgumentException](
      new GoldBundleReader(structural.bundleRoot).readCurrent())
    assert(modeError.getMessage.contains("requires full"))
  }

  test("rejects stale attestations and warnings that are not explicitly allowlisted") {
    val stale = writeFixture(attestedManifestHash = Some("0" * 64))
    val staleError = intercept[IllegalArgumentException](
      new GoldBundleReader(stale.bundleRoot).readCurrent())
    assert(staleError.getMessage.contains("manifest hash mismatch"))

    val warned = writeFixture(result = "PASS_WITH_WARNINGS", warnings = Seq("join_coverage"))
    val warningError = intercept[IllegalArgumentException](
      new GoldBundleReader(warned.bundleRoot).readCurrent())
    assert(warningError.getMessage.contains("non-allowlisted warnings"))

    val accepted = new GoldBundleReader(warned.bundleRoot, Set("join_coverage")).readCurrent()
    assert(accepted.attestation.warningCodes === Seq("join_coverage"))
  }

  test("rejects malformed identifiers, pointer release mismatch, and missing gold components") {
    val fixture = writeFixture()
    intercept[IllegalArgumentException](new GoldBundleReader(fixture.bundleRoot).readSelected("../escape"))

    Files.writeString(fixture.bundleRoot.resolve("current_bundle.json"),
      """{"bundle_id":"2026-07-fixture","release":"2026-06"}""", StandardCharsets.UTF_8)
    val releaseError = intercept[IllegalArgumentException](
      new GoldBundleReader(fixture.bundleRoot).readCurrent())
    assert(releaseError.getMessage.contains("pointer release"))

    val missing = writeFixture(includeLeads = false)
    val missingError = intercept[IllegalArgumentException](
      new GoldBundleReader(missing.bundleRoot).readCurrent())
    assert(missingError.getMessage.contains("Missing required gold components"))
  }

  private final case class Fixture(bundleRoot: Path, generation: Path)

  private def writeFixture(
      componentPath: Option[String] = None,
      validationMode: String = "full",
      result: String = "PASS",
      warnings: Seq[String] = Seq.empty,
      attestedManifestHash: Option[String] = None,
      includeLeads: Boolean = true
  ): Fixture = {
    val root = Files.createTempDirectory("atlas-indexer-bundle")
    val bundleRoot = root.resolve("_atlas/bundles")
    val generation = bundleRoot.resolve("generations/2026-07-fixture")
    val profilesRelative = "data/gold/receita/company_profiles_current"
    val leadsRelative = "data/gold/receita/leads_new_companies_current"
    val profiles = generation.resolve(profilesRelative)
    val leads = generation.resolve(leadsRelative)
    Files.createDirectories(profiles)
    Files.writeString(profiles.resolve("part.parquet"), "profiles", StandardCharsets.UTF_8)
    if (includeLeads) {
      Files.createDirectories(leads)
      Files.writeString(leads.resolve("part.parquet"), "leads", StandardCharsets.UTF_8)
    }

    val hashReader = new GoldBundleReader(bundleRoot)
    val profilesHash = hashReader.directorySha256(profiles)
    val leadHash = if (includeLeads) Some(hashReader.directorySha256(leads)) else None
    val components = Seq(Some(
      s"""{"name":"gold_company_profiles","path":"${componentPath.getOrElse(profilesRelative)}","sha256":"$profilesHash"}"""),
      leadHash.map(hash =>
        s"""{"name":"gold_leads_new_companies","path":"$leadsRelative","sha256":"$hash"}""")
    ).flatten
    Files.createDirectories(generation)
    val manifest =
      s"""{"manifest_version":1,"bundle_id":"2026-07-fixture","release":"2026-07","components":[${components.mkString(",")}]}"""
    val manifestPath = generation.resolve("bundle-manifest.json")
    Files.writeString(manifestPath, manifest, StandardCharsets.UTF_8)
    val manifestHash = hashReader.fileSha256(manifestPath)

    Files.createDirectories(bundleRoot.resolve("validation"))
    val warningJson = warnings.map(value => "\"" + value + "\"").mkString(",")
    val attestedComponents = Seq(
      s"""{"name":"gold_company_profiles","sha256":"$profilesHash"}""",
      leadHash.map(hash => s"""{"name":"gold_leads_new_companies","sha256":"$hash"}""").orNull
    ).filter(_ != null)
    val attestation =
      s"""{"attestation_version":1,"bundle_id":"2026-07-fixture","bundle_manifest_sha256":"${attestedManifestHash.getOrElse(manifestHash)}","validator_version":"1","validation_contract_version":"1","mode":"$validationMode","result":"$result","completed_at":"${Instant.parse("2026-08-09T00:00:00Z")}","warning_codes":[$warningJson],"components":[${attestedComponents.mkString(",")}]}"""
    Files.writeString(bundleRoot.resolve("validation/2026-07-fixture.json"), attestation,
      StandardCharsets.UTF_8)
    Files.writeString(bundleRoot.resolve("current_bundle.json"),
      """{"bundle_id":"2026-07-fixture","release":"2026-07"}""", StandardCharsets.UTF_8)
    Fixture(bundleRoot, generation)
  }
}
