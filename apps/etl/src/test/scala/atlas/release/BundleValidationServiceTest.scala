package atlas.release

import atlas.SparkSuite
import atlas.config.{AtlasConfig, CsvConfig, ReceitaConfig, SparkConfig}
import atlas.receita.CompanyDataManifestReader
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import org.scalatest.funsuite.AnyFunSuite

class BundleValidationServiceTest extends AnyFunSuite with SparkSuite {
  test("reports every structural check for a valid immutable rebuild bundle") {
    val fixture = writeBundleFixture()
    val report = BundleValidationService.validate(fixture.config)
    assert(report.result === "PASS")
    assert(report.checks.forall(check => Set("PASS", "SKIP").contains(check.status.name)))
    assert(report.checks.exists(_.id === "components.required_present"))
    assert(report.checks.count(_.id.endsWith(".partition")) === 6)
    assert(report.checks.exists(check => check.id === "data.full_validation" && check.status == BundleCheckStatus.Skip))
    assert(BundleValidationService.render(report).contains("checks_total="))
    assert(BundleValidationService.json(report).contains("\"checks\":["))
  }

  test("fails the named component hash check after published data changes") {
    val fixture = writeBundleFixture()
    Files.writeString(fixture.generation.resolve("data/silver/receita/companies_current/part.parquet"), "changed")
    val report = BundleValidationService.validate(fixture.config)
    val failure = report.checks.find(_.id === "component.companies.hash").get
    assert(report.result === "FAIL")
    assert(failure.status === BundleCheckStatus.Fail)
    assert(failure.expected.nonEmpty && failure.observed.nonEmpty)
  }

  test("rejects a component path that escapes the selected generation") {
    val fixture = writeBundleFixture(Some("companies" -> "../outside"))
    val report = BundleValidationService.validate(fixture.config)
    assert(report.checks.exists(check =>
      check.id === "component.companies.path" && check.status == BundleCheckStatus.Fail))
  }

  test("full validation reports data checks and non-blocking join misses") {
    val fixture = writeBundleFixture(realParquet = true)
    val report = BundleValidationService.validate(fixture.config, full = true, spark = Some(spark))
    assert(report.failed === 0, BundleValidationService.render(report))
    assert(report.result === "PASS_WITH_WARNINGS")
    assert(report.exitCode === 0)
    assert(report.checks.exists(check => check.id === "companies.unique_cnpj_root" && check.status == BundleCheckStatus.Pass))
    assert(report.checks.count(_.id.endsWith(".arithmetic")) === 6)
    assert(report.checks.exists(check =>
      check.id === "establishments.company_join_coverage" && check.status == BundleCheckStatus.Warn))
  }

  test("writes a versioned full-validation attestation bound to the immutable manifest") {
    val fixture = writeBundleFixture(realParquet = true)
    val report = BundleValidationService.validate(fixture.config, full = true, spark = Some(spark))

    val path = BundleValidationService.writeAttestation(fixture.config, report)
    val value = com.typesafe.config.ConfigFactory.parseFile(path.toFile).resolve()

    assert(value.getInt("attestation_version") === 1)
    assert(value.getString("bundle_id") === "bundle-july")
    assert(value.getString("mode") === "full")
    assert(value.getString("result") === "PASS_WITH_WARNINGS")
    assert(value.getString("bundle_manifest_sha256") === CompanyDataManifestReader.sha256(
      fixture.generation.resolve("bundle-manifest.json")))
    assert(value.getStringList("warning_codes").contains("establishments.company_join_coverage"))
    assert(value.getConfigList("components").size() === 13)
  }

  private final class Fixture(val config: AtlasConfig, val generation: Path)

  private def writeBundleFixture(
      componentPathOverride: Option[(String, String)] = None,
      realParquet: Boolean = false
  ): Fixture = {
    val root = Files.createTempDirectory("atlas-bundle-validation")
    val release = "2026-07"
    val config = testConfig(root, release)
    val generation = root.resolve("_atlas/bundles/generations/bundle-july")
    Files.createDirectories(generation)
    val componentPaths = Seq(
      "companies" -> "data/silver/receita/companies_current",
      "establishments" -> "data/silver/receita/establishments_current",
      "company_history" -> "data/silver/receita/company_change_events",
      "establishment_history" -> "data/silver/receita/establishment_change_events",
      "company_summaries" -> "data/silver/receita/company_release_summaries",
      "establishment_summaries" -> "data/silver/receita/establishment_release_summaries",
      "municipality_geography" -> "data/silver/receita/municipality_geography",
      "cnae" -> "data/silver/receita/references/cnae",
      "municipality" -> "data/silver/receita/references/municipality",
      "legal_nature" -> "data/silver/receita/references/legal_nature",
      "country" -> "data/silver/receita/references/country",
      "partner_qualification" -> "data/silver/receita/references/partner_qualification",
      "registration_status_reason" -> "data/silver/receita/references/registration_status_reason"
    )
    if (realParquet) writeParquetComponents(generation, componentPaths, release)
    else {
      componentPaths.foreach { case (name, relative) =>
        val directory = generation.resolve(relative)
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("part.parquet"), name, StandardCharsets.UTF_8)
      }
      Seq("2026-05", "2026-06", "2026-07").foreach { value =>
        Seq("company_release_summaries", "establishment_release_summaries").foreach { name =>
          val directory = generation.resolve(s"data/silver/receita/$name/to_release=$value")
          Files.createDirectories(directory)
          Files.writeString(directory.resolve("part.parquet"), s"$name-$value", StandardCharsets.UTF_8)
        }
      }
    }
    val establishmentManifest = root.resolve(s"raw/receita/$release/estabelecimentos/manifest.json")
    Files.createDirectories(establishmentManifest.getParent)
    Files.writeString(establishmentManifest, "{month=2026-07}", StandardCharsets.UTF_8)
    val companyRoot = root.resolve(s"raw/receita/$release/company-data")
    Files.createDirectories(companyRoot)
    val tom = companyRoot.resolve("tom.csv")
    val ibge = companyRoot.resolve("ibge.json")
    Files.writeString(tom, "tom", StandardCharsets.UTF_8)
    Files.writeString(ibge, "[]", StandardCharsets.UTF_8)
    val companyManifest = companyRoot.resolve("source-manifest.json")
    Files.writeString(companyManifest,
      """{"references":{"tom":{"path":"tom.csv"},"ibge_localities":{"path":"ibge.json"}}}""",
      StandardCharsets.UTF_8)
    val components = componentPaths.map { case (name, original) =>
      val relative = componentPathOverride.collect { case (`name`, replacement) => replacement }.getOrElse(original)
      val hash = BundleValidationService.directoryHash(generation.resolve(original))
      s"""{"name":"$name","path":"$relative","sha256":"$hash"}"""
    }.mkString(",")
    val body =
      s"""{"manifest_version":1,"bundle_id":"bundle-july","release":"$release","previous_bundle_id":null,"releases":["2026-05","2026-06","2026-07"],"establishment_source_manifest_sha256":"${CompanyDataManifestReader.sha256(establishmentManifest)}","company_source_manifest_sha256":"${CompanyDataManifestReader.sha256(companyManifest)}","tom_sha256":"${CompanyDataManifestReader.sha256(tom)}","ibge_sha256":"${CompanyDataManifestReader.sha256(ibge)}","components":[$components]}"""
    Files.writeString(generation.resolve("bundle-manifest.json"), body, StandardCharsets.UTF_8)
    Files.writeString(root.resolve("_atlas/bundles/current_bundle.json"),
      s"""{"bundle_id":"bundle-july","release":"$release"}""", StandardCharsets.UTF_8)
    new Fixture(config, generation)
  }

  private def writeParquetComponents(
      generation: Path,
      componentPaths: Seq[(String, String)],
      release: String
  ): Unit = {
    val session = spark
    import session.implicits._
    val paths = componentPaths.toMap
    Seq(("12345678", release)).toDF("cnpj_root", "release").write.parquet(
      generation.resolve(paths("companies")).toString
    )
    Seq(
      ("12345678000100", "12345678", release),
      ("87654321000100", "87654321", release)
    ).toDF("cnpj_full", "cnpj_root", "release").write.parquet(
      generation.resolve(paths("establishments")).toString
    )
    val special = Set("companies", "establishments", "company_summaries", "establishment_summaries")
    componentPaths.filterNot(value => special(value._1)).foreach { case (_, relative) =>
      Seq("fixture").toDF("value").write.parquet(generation.resolve(relative).toString)
    }
    Seq("2026-05", "2026-06", "2026-07").zipWithIndex.foreach { case (value, index) =>
      val previous = if (index == 0) None else Some(1L)
      Seq((previous, 1L, 0L, 0L, 0L, 0L)).toDF(
        "previous_record_count", "current_record_count", "inserted_count", "updated_count",
        "removed_count", "event_count"
      ).write.parquet(generation.resolve(
        s"data/silver/receita/company_release_summaries/to_release=$value"
      ).toString)
      Seq((previous, 1L, 0L, 0L, 0L, 0L)).toDF(
        "previous_record_count", "current_record_count", "inserted_count", "updated_count",
        "removed_count", "event_count"
      ).write.parquet(generation.resolve(
        s"data/silver/receita/establishment_release_summaries/to_release=$value"
      ).toString)
    }
    val coverage = generation.resolve(s"data/_atlas/quality/receita/company-data/$release/municipality_geography_coverage.json")
    Files.createDirectories(coverage.getParent)
    Files.writeString(coverage, "{\"unresolved_codes\":0}", StandardCharsets.UTF_8)
  }

  private def testConfig(root: Path, release: String): AtlasConfig = AtlasConfig(
    SparkConfig("local[1]", "test", 1, root.resolve("spark").toString), CsvConfig(";", "UTF-8"),
    ReceitaConfig(release,
      root.resolve(s"raw/receita/$release/estabelecimentos/extracted").toString,
      root.resolve("bronze/receita").toString, root.resolve("silver/receita").toString,
      root.resolve(s"raw/receita/$release/company-data").toString),
    root.resolve("_atlas/status").toString, "overwrite"
  )
}
