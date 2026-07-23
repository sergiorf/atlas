package atlas.release

import atlas.config.{AtlasConfig, CsvConfig, ReceitaConfig, SparkConfig}
import atlas.receita.CompanyDataManifestReader
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import org.scalatest.funsuite.AnyFunSuite

class CompanyBundleServiceTest extends AnyFunSuite {
  test("preflights complete same-release company and establishment manifests") {
    val root = Files.createTempDirectory("atlas-company-bundle-plan")
    val config = testConfig(root, "2026-07")
    writeCompanyManifest(config, "2026-07")
    writeEstablishmentManifest(config, "2026-07")
    val plan = CompanyBundleService.plan(config, ReleaseId.unsafe("2026-07"), ReleaseId.unsafe("2026-07"))
    assert(plan.releases === Seq(ReleaseId.unsafe("2026-07")))
    assert(plan.dryRun)
  }

  test("rejects mixed company-data releases before Spark work") {
    val root = Files.createTempDirectory("atlas-company-bundle-mixed")
    val config = testConfig(root, "2026-07")
    writeCompanyManifest(config, "2026-06")
    writeEstablishmentManifest(config, "2026-07")
    assertThrows[IllegalArgumentException](
      CompanyBundleService.plan(config, ReleaseId.unsafe("2026-07"), ReleaseId.unsafe("2026-07"))
    )
  }

  test("inspects the atomically selected bundle without Spark") {
    val root = Files.createTempDirectory("atlas-company-bundle-inspect")
    val config = testConfig(root, "2026-07")
    val bundleRoot = root.resolve("_atlas/bundles")
    val generation = bundleRoot.resolve("generations/bundle-july")
    Files.createDirectories(generation)
    Files.writeString(generation.resolve("bundle-manifest.json"),
      "{\"manifest_version\":1,\"bundle_id\":\"bundle-july\",\"release\":\"2026-07\"}\n")
    Files.createDirectories(bundleRoot)
    Files.writeString(bundleRoot.resolve("current_bundle.json"), "{\"bundle_id\":\"bundle-july\",\"release\":\"2026-07\"}\n")
    val inspected = CompanyBundleService.inspect(config, None).get
    assert(inspected.bundleId === "bundle-july")
    assert(inspected.current)
  }

  test("promotes an immutable generation and atomically selects it") {
    val root = Files.createTempDirectory("atlas-company-bundle-publish")
    val config = testConfig(root, "2026-07")
    val staging = root.resolve("_atlas/bundles/staging/bundle-july")
    Files.createDirectories(staging)
    Files.writeString(staging.resolve("bundle-manifest.json"),
      "{\"manifest_version\":1,\"bundle_id\":\"bundle-july\",\"release\":\"2026-07\"}\n")
    CompanyBundleService.publish(config, staging, "bundle-july", ReleaseId.unsafe("2026-07"))
    val inspected = CompanyBundleService.inspect(config, None).get
    assert(inspected.bundleId === "bundle-july")
    assert(inspected.current)
    assert(!Files.exists(staging))
  }

  private def writeCompanyManifest(config: AtlasConfig, declaredRelease: String): Unit = {
    val raw = Path.of(config.receita.companyDataRawDir)
    Files.createDirectories(raw)
    val names = Seq("empresas", "cnae", "municipios", "naturezas", "paises", "qualificacoes", "motivos")
    val datasets = names.map { name =>
      val file = raw.resolve(s"$name.zip")
      Files.writeString(file, name, StandardCharsets.UTF_8)
      val hash = CompanyDataManifestReader.sha256(file)
      s"""{"logical_name":"$name","release":"$declaredRelease","parser":{"encoding":"latin-1","delimiter":";","header":false,"quote":"\\\"","escape":"\\\"","expected_fields":2},"archives":[{"filename":"$name.zip","path":"$name.zip","sha256":"$hash","bytes":${Files.size(file)},"source_url":"test","retrieved_at":"now","members":[{"path":"x","bytes":1,"crc32":"00000000","sha256":"$hash"}]}]}"""
    }.mkString(",")
    val tom = raw.resolve("tom.csv")
    val ibge = raw.resolve("ibge.json")
    Files.writeString(tom, "tom", StandardCharsets.UTF_8)
    Files.writeString(ibge, "[]", StandardCharsets.UTF_8)
    val body = s"""{"manifest_version":1,"release":"$declaredRelease","created_at":"now","publisher":"test","producer_version":"test","discovery_run_id":"test","datasets":[$datasets],"references":{"tom":{"path":"tom.csv","sha256":"${CompanyDataManifestReader.sha256(tom)}"},"ibge_localities":{"path":"ibge.json","sha256":"${CompanyDataManifestReader.sha256(ibge)}"}}}"""
    Files.writeString(raw.resolve("source-manifest.json"), body, StandardCharsets.UTF_8)
  }

  private def writeEstablishmentManifest(config: AtlasConfig, release: String): Unit = {
    val extracted = Path.of(config.receita.rawDir)
    Files.createDirectories(extracted)
    Files.writeString(extracted.resolve("part.csv"), "fixture")
    Files.createDirectories(extracted.getParent.resolve("archives"))
    Files.write(extracted.getParent.resolve("archives/part.zip"), Array[Byte](1))
    Files.writeString(extracted.getParent.resolve("manifest.json"),
      s"""{"dataset":"estabelecimentos","month":"$release","files":{"part.zip":{"bytes":1,"extracted":true,"status":"complete","url":"test"}}}""",
      StandardCharsets.UTF_8
    )
  }

  private def testConfig(root: Path, release: String): AtlasConfig = AtlasConfig(
    SparkConfig("local[1]", "test", 1, root.resolve("spark").toString), CsvConfig(";", "UTF-8"),
    ReceitaConfig(
      release,
      root.resolve(s"raw/receita/$release/estabelecimentos/extracted").toString,
      root.resolve("bronze/receita").toString,
      root.resolve("silver/receita").toString,
      root.resolve(s"raw/receita/$release/company-data").toString
    ),
    root.resolve("_atlas/status").toString,
    "overwrite"
  )
}
