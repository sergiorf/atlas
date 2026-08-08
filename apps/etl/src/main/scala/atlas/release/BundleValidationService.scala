package atlas.release

import atlas.config.AtlasConfig
import atlas.receita.{CompanyDataManifestReader, CompanyDataSchemas}
import com.typesafe.config.{Config, ConfigFactory}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path, Paths, StandardCopyOption}
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions.{col, count, lit}

final case class BundleComponent(name: String, path: String, sha256: String)

final case class BundleManifest(
    manifestVersion: Int,
    bundleId: String,
    release: ReleaseId,
    previousBundleId: Option[String],
    releases: Seq[ReleaseId],
    establishmentSourceManifestSha256: String,
    companySourceManifestSha256: String,
    tomSha256: String,
    ibgeSha256: String,
    components: Seq[BundleComponent]
)

sealed trait BundleCheckStatus { def name: String }
object BundleCheckStatus {
  case object Pass extends BundleCheckStatus { val name = "PASS" }
  case object Warn extends BundleCheckStatus { val name = "WARN" }
  case object Fail extends BundleCheckStatus { val name = "FAIL" }
  case object Skip extends BundleCheckStatus { val name = "SKIP" }
}

final case class BundleValidationCheck(
    id: String,
    status: BundleCheckStatus,
    description: String,
    expected: Option[String] = None,
    observed: Option[String] = None,
    release: Option[String] = None,
    component: Option[String] = None,
    diagnosticPath: Option[String] = None,
    durationMs: Long = 0L
)

final case class BundleValidationReport(
    validatorVersion: String,
    contractVersion: String,
    bundleId: String,
    release: String,
    mode: String,
    checks: Seq[BundleValidationCheck],
    durationMs: Long
) {
  val failed: Int = checks.count(_.status == BundleCheckStatus.Fail)
  val warnings: Int = checks.count(_.status == BundleCheckStatus.Warn)
  val passed: Int = checks.count(_.status == BundleCheckStatus.Pass)
  val skipped: Int = checks.count(_.status == BundleCheckStatus.Skip)
  val result: String = if (failed > 0) "FAIL" else if (warnings > 0) "PASS_WITH_WARNINGS" else "PASS"
  val exitCode: Int = if (failed > 0) 1 else 0
}

object BundleValidationService {
  val ValidatorVersion = "1"
  val ContractVersion = "1"

  private val BaseComponents = Set(
    "companies", "establishments", "company_history", "establishment_history",
    "company_summaries", "establishment_summaries", "municipality_geography"
  ) ++ CompanyDataSchemas.referenceGroups
  private val ProductComponents = Set(
    "company_tax_regime", "partners", "company_relationships", "relationship_observations",
    "gold_company_profiles", "gold_company_partner_network", "gold_company_relationship_paths",
    "gold_leads_new_companies"
  )

  def validate(
      config: AtlasConfig,
      bundleId: Option[String] = None,
      full: Boolean = false,
      spark: Option[SparkSession] = None
  ): BundleValidationReport = {
    require(!full || spark.nonEmpty, "Full bundle validation requires Spark")
    val started = System.nanoTime()
    val checks = ArrayBuffer.empty[BundleValidationCheck]
    val selected = selectGeneration(config, bundleId)
    val manifestPath = selected.resolve("bundle-manifest.json")
    val manifest = timed(checks, "manifest.parse", "Bundle manifest is well formed") {
      readManifest(manifestPath)
    }
    val pointer = readPointer(bundleRoot(config).resolve("current_bundle.json"))
    checks += check(
      "pointer.matches_manifest",
      if (bundleId.isDefined || pointer._1 == manifest.bundleId) BundleCheckStatus.Pass else BundleCheckStatus.Fail,
      if (bundleId.isDefined) "Explicit bundle selection does not require the current pointer"
      else "Current pointer selects the validated manifest",
      Some(if (bundleId.isDefined) manifest.bundleId else pointer._1), Some(manifest.bundleId)
    )
    checks += check(
      "pointer.release_matches_manifest",
      if (bundleId.isDefined || pointer._2 == manifest.release.value) BundleCheckStatus.Pass else BundleCheckStatus.Fail,
      if (bundleId.isDefined) "Explicit bundle selection does not require the current pointer release"
      else "Current pointer release matches the manifest",
      Some(if (bundleId.isDefined) manifest.release.value else pointer._2), Some(manifest.release.value)
    )
    checks += componentNamesCheck(manifest)
    checks += releaseChainCheck(manifest)
    checks ++= componentPathChecks(selected, manifest)
    checks ++= sourceHashChecks(config, manifest)
    checks += predecessorCheck(config, manifest)
    checks ++= summaryPartitionChecks(selected, manifest)
    if (full) checks ++= fullChecks(spark.get, selected, manifest)
    else checks += check("data.full_validation", BundleCheckStatus.Skip,
      "Spark data checks were not requested", observed = Some("use --full"))
    BundleValidationReport(
      ValidatorVersion, ContractVersion, manifest.bundleId, manifest.release.value,
      if (full) "full" else "structural", checks.toSeq, elapsedMs(started)
    )
  }

  def render(report: BundleValidationReport): String = {
    val lines = report.checks.map { value =>
      val details = Seq(
        value.release.map(v => s"release=$v"), value.component.map(v => s"component=$v"),
        value.expected.map(v => s"expected=$v"), value.observed.map(v => s"observed=$v"),
        value.diagnosticPath.map(v => s"diagnostic=$v"), Some(s"duration_ms=${value.durationMs}")
      ).flatten
      f"${value.status.name}%-4s  ${value.id}%-42s ${details.mkString(" ")}\n      ${value.description}"
    }
    (lines :+ "" :+ s"bundle_id=${report.bundleId}" :+ s"release=${report.release}" :+
      s"mode=${report.mode}" :+ s"result=${report.result}" :+ s"checks_total=${report.checks.size}" :+
      s"passed=${report.passed}" :+ s"warnings=${report.warnings}" :+ s"failed=${report.failed}" :+
      s"skipped=${report.skipped}" :+ s"duration_ms=${report.durationMs}").mkString("\n")
  }

  def json(report: BundleValidationReport): String = {
    val values = report.checks.map { value =>
      s"""{"id":"${escape(value.id)}","status":"${value.status.name.toLowerCase}","description":"${escape(value.description)}","expected":${optional(value.expected)},"observed":${optional(value.observed)},"release":${optional(value.release)},"component":${optional(value.component)},"diagnostic_path":${optional(value.diagnosticPath)},"duration_ms":${value.durationMs}}"""
    }.mkString(",")
    s"""{"validator_version":"${report.validatorVersion}","contract_version":"${report.contractVersion}","bundle_id":"${escape(report.bundleId)}","release":"${report.release}","mode":"${report.mode}","result":"${report.result.toLowerCase}","summary":{"total":${report.checks.size},"passed":${report.passed},"warnings":${report.warnings},"failed":${report.failed},"skipped":${report.skipped}},"duration_ms":${report.durationMs},"checks":[$values]}"""
  }

  def writeAttestation(config: AtlasConfig, report: BundleValidationReport): Path = {
    require(report.mode == "full", "Bundle validation attestation requires full validation")
    require(report.failed == 0, "Cannot attest a bundle with blocking validation failures")
    val selected = selectGeneration(config, Some(report.bundleId))
    val manifestPath = selected.resolve("bundle-manifest.json")
    val manifest = readManifest(manifestPath)
    require(manifest.release.value == report.release, "Validation report release does not match bundle manifest")
    val warnings = report.checks.filter(_.status == BundleCheckStatus.Warn).map(_.id).distinct.sorted
    val components = manifest.components.sortBy(_.name).map { component =>
      s"""{"name":"${escape(component.name)}","sha256":"${component.sha256}"}"""
    }.mkString(",")
    val warningJson = warnings.map(value => "\"" + escape(value) + "\"").mkString(",")
    val body =
      s"""{"attestation_version":1,"bundle_id":"${escape(report.bundleId)}","bundle_manifest_sha256":"${CompanyDataManifestReader.sha256(manifestPath)}","validator_version":"${escape(report.validatorVersion)}","validation_contract_version":"${escape(report.contractVersion)}","mode":"${escape(report.mode)}","result":"${escape(report.result)}","completed_at":"${Instant.now()}","warning_codes":[$warningJson],"components":[$components]}"""
    val root = bundleRoot(config).resolve("validation")
    Files.createDirectories(root)
    val target = root.resolve(s"${report.bundleId}.json")
    val temporary = root.resolve(s"${report.bundleId}.${UUID.randomUUID()}.tmp")
    Files.writeString(temporary, body + "\n", StandardCharsets.UTF_8)
    try Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    catch { case _: java.nio.file.AtomicMoveNotSupportedException =>
      Files.deleteIfExists(temporary)
      throw new IllegalStateException(s"Atomic validation-attestation replacement is not supported at $target")
    }
    target
  }

  private[release] def readManifest(path: Path): BundleManifest = {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
      throw new IllegalStateException(s"Missing bundle manifest: $path")
    val config = ConfigFactory.parseFile(path.toFile).resolve()
    val components = config.getConfigList("components").asScala.map { value =>
      BundleComponent(value.getString("name"), value.getString("path"), value.getString("sha256"))
    }
    val releases = config.getStringList("releases").asScala.map(ReleaseId.unsafe)
    BundleManifest(
      config.getInt("manifest_version"), config.getString("bundle_id"),
      ReleaseId.unsafe(config.getString("release")), optionalString(config, "previous_bundle_id"),
      releases, config.getString("establishment_source_manifest_sha256"),
      config.getString("company_source_manifest_sha256"), config.getString("tom_sha256"),
      config.getString("ibge_sha256"), components
    )
  }

  private def selectGeneration(config: AtlasConfig, requested: Option[String]): Path = {
    val root = bundleRoot(config)
    val id = requested.getOrElse(readPointer(root.resolve("current_bundle.json"))._1)
    if (id.isEmpty || id.contains('/') || id.contains('\\') || id == "." || id == "..")
      throw new IllegalArgumentException(s"Invalid bundle ID: $id")
    val path = root.resolve("generations").resolve(id).normalize()
    val generations = root.resolve("generations").normalize()
    if (!path.startsWith(generations) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
      throw new IllegalArgumentException(s"Bundle generation does not exist: $id")
    path
  }

  private def readPointer(path: Path): (String, String) = {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
      throw new IllegalStateException(s"Missing current bundle pointer: $path")
    val value = ConfigFactory.parseFile(path.toFile).resolve()
    value.getString("bundle_id") -> value.getString("release")
  }

  private def componentNamesCheck(manifest: BundleManifest): BundleValidationCheck = {
    val names = manifest.components.map(_.name)
    val duplicates = names.groupBy(identity).collect { case (name, values) if values.size > 1 => name }.toSeq.sorted
    val missingBase = BaseComponents -- names
    val presentProducts = ProductComponents.intersect(names.toSet)
    val missingProducts = if (presentProducts.nonEmpty) ProductComponents -- names else Set.empty[String]
    val failures = Seq(
      if (duplicates.nonEmpty) Some(s"duplicate=${duplicates.mkString(",")}") else None,
      if (missingBase.nonEmpty) Some(s"missing=${missingBase.toSeq.sorted.mkString(",")}") else None,
      if (missingProducts.nonEmpty) Some(s"partial_products_missing=${missingProducts.toSeq.sorted.mkString(",")}") else None
    ).flatten
    check("components.required_present", if (failures.isEmpty) BundleCheckStatus.Pass else BundleCheckStatus.Fail,
      "Required components are present exactly once; product components are all-or-none",
      observed = Some(if (failures.isEmpty) s"${names.size} components" else failures.mkString("; ")))
  }

  private def releaseChainCheck(manifest: BundleManifest): BundleValidationCheck = {
    val values = manifest.releases
    val valid = values.nonEmpty && values.distinct == values && values == values.sorted && values.last == manifest.release
    check("releases.chronological", if (valid) BundleCheckStatus.Pass else BundleCheckStatus.Fail,
      "Manifest releases are non-empty, unique, chronological, and end at the bundle release",
      expected = Some(s"ordered range ending ${manifest.release}"), observed = Some(values.mkString(",")))
  }

  private def componentPathChecks(root: Path, manifest: BundleManifest): Seq[BundleValidationCheck] =
    manifest.components.map { component =>
      val started = System.nanoTime()
      val relative = Paths.get(component.path)
      val resolved = root.resolve(relative).normalize()
      val contained = !relative.isAbsolute && resolved.startsWith(root.normalize())
      if (!contained) check(s"component.${component.name}.path", BundleCheckStatus.Fail,
        "Component path must remain inside its bundle generation", component = Some(component.name),
        observed = Some(component.path), durationMs = elapsedMs(started))
      else if (!Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) check(
        s"component.${component.name}.exists", BundleCheckStatus.Fail, "Component directory exists",
        component = Some(component.name), observed = Some(resolved.toString), durationMs = elapsedMs(started))
      else {
        val files = Files.walk(resolved)
        val hasParquet = try files.iterator().asScala.exists(path =>
          Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.getFileName.toString.endsWith(".parquet")
        ) finally files.close()
        val observed = directoryHash(resolved)
        val valid = hasParquet && observed == component.sha256
        check(s"component.${component.name}.hash", if (valid) BundleCheckStatus.Pass else BundleCheckStatus.Fail,
          "Component contains Parquet data and its content hash matches the bundle manifest", Some(component.sha256),
          Some(if (hasParquet) observed else s"no Parquet files; hash=$observed"),
          component = Some(component.name), durationMs = elapsedMs(started))
      }
    }

  private def sourceHashChecks(config: AtlasConfig, manifest: BundleManifest): Seq[BundleValidationCheck] = {
    val release = manifest.release
    val establishment = Paths.get(ReleasePaths.rawDirForRelease(config.receita.rawDir, release)).getParent.resolve("manifest.json")
    val companyRoot = Paths.get(ReleasePaths.rawDirForRelease(config.receita.companyDataRawDir, release))
    val company = companyRoot.resolve("source-manifest.json")
    val companyManifest = if (Files.isRegularFile(company)) Some(ConfigFactory.parseFile(company.toFile).resolve()) else None
    Seq(
      fileHashCheck("source.establishment_manifest_hash", establishment, manifest.establishmentSourceManifestSha256),
      fileHashCheck("source.company_manifest_hash", company, manifest.companySourceManifestSha256),
      referencedHashCheck("source.tom_hash", companyRoot, companyManifest, "references.tom.path", manifest.tomSha256),
      referencedHashCheck("source.ibge_hash", companyRoot, companyManifest, "references.ibge_localities.path", manifest.ibgeSha256)
    )
  }

  private def fileHashCheck(id: String, path: Path, expected: String): BundleValidationCheck = {
    val started = System.nanoTime()
    val observed = if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) Some(CompanyDataManifestReader.sha256(path)) else None
    check(id, if (observed.contains(expected)) BundleCheckStatus.Pass else BundleCheckStatus.Fail,
      "Immutable source evidence matches the bundle manifest", Some(expected), observed.orElse(Some("missing")),
      diagnosticPath = Some(path.toString), durationMs = elapsedMs(started))
  }

  private def referencedHashCheck(id: String, root: Path, manifest: Option[Config], key: String, expected: String): BundleValidationCheck = {
    val path = manifest.filter(_.hasPath(key)).map(value => root.resolve(value.getString(key)).normalize())
    path.fold(check(id, BundleCheckStatus.Fail, "Source reference path is declared and hashable",
      Some(expected), Some("missing manifest reference"))) { value => fileHashCheck(id, value, expected) }
  }

  private def predecessorCheck(config: AtlasConfig, manifest: BundleManifest): BundleValidationCheck =
    manifest.previousBundleId match {
      case None => check("bundle.predecessor", BundleCheckStatus.Skip,
        "Rebuild seed has no predecessor generation", observed = Some("none"))
      case Some(id) =>
        val path = bundleRoot(config).resolve("generations").resolve(id).resolve("bundle-manifest.json")
        val valid = try readManifest(path).bundleId == id catch { case _: Throwable => false }
        check("bundle.predecessor", if (valid) BundleCheckStatus.Pass else BundleCheckStatus.Fail,
          "Predecessor generation exists and has readable matching metadata", Some(id),
          Some(if (valid) id else "missing or unreadable"), diagnosticPath = Some(path.toString))
    }

  private def summaryPartitionChecks(root: Path, manifest: BundleManifest): Seq[BundleValidationCheck] = {
    val components = manifest.components.map(value => value.name -> root.resolve(value.path).normalize()).toMap
    Seq("company_summaries", "establishment_summaries").flatMap { name =>
      manifest.releases.map { release =>
        val path = components.get(name).map(_.resolve(s"to_release=${release.value}"))
        val exists = path.exists(Files.isDirectory(_, LinkOption.NOFOLLOW_LINKS))
        check(s"$name.partition", if (exists) BundleCheckStatus.Pass else BundleCheckStatus.Fail,
          "Release summary partition exists", observed = Some(path.fold("component missing")(_.toString)),
          release = Some(release.value), component = Some(name))
      }
    }
  }

  private def fullChecks(spark: SparkSession, root: Path, manifest: BundleManifest): Seq[BundleValidationCheck] = {
    val checks = ArrayBuffer.empty[BundleValidationCheck]
    val paths = manifest.components.map(value => value.name -> root.resolve(value.path).normalize()).toMap
    val companies = readParquet(spark, paths("companies"), checks, "companies")
    val establishments = readParquet(spark, paths("establishments"), checks, "establishments")
    companies.foreach { frame =>
      checks += releaseCheck(frame, "companies", "release", manifest.release.value)
      checks += uniqueKeyCheck(frame, "companies", "cnpj_root")
    }
    establishments.foreach { frame =>
      checks += releaseCheck(frame, "establishments", "release", manifest.release.value)
      checks += uniqueKeyCheck(frame, "establishments", "cnpj_full")
    }
    for (companyFrame <- companies; establishmentFrame <- establishments) {
      val started = System.nanoTime()
      val missing = establishmentFrame.as("e")
        .join(companyFrame.select("cnpj_root").as("c"), col("e.cnpj_root") === col("c.cnpj_root"), "left")
        .filter(col("c.cnpj_root").isNull).count()
      checks += check("establishments.company_join_coverage",
        if (missing == 0L) BundleCheckStatus.Pass else BundleCheckStatus.Warn,
        "Establishments without an accepted company are reported; duplicate-company quarantine can explain them",
        Some("0 unmatched or a reviewed quarantine explanation"), Some(s"$missing unmatched"),
        durationMs = elapsedMs(started))
    }
    Seq("company_summaries", "establishment_summaries").foreach { name =>
      manifest.releases.foreach { release =>
        val partition = paths(name).resolve(s"to_release=${release.value}")
        readParquet(spark, partition, checks, s"$name.${release.value}").foreach { frame =>
          val countValue = frame.count()
          checks += check(s"$name.single_row", if (countValue == 1L) BundleCheckStatus.Pass else BundleCheckStatus.Fail,
            "Summary partition contains exactly one row", Some("1"), Some(countValue.toString),
            release = Some(release.value), component = Some(name))
          if (countValue == 1L) checks += historyArithmeticCheck(frame, name, release)
        }
      }
    }
    val coverage = root.resolve(s"data/_atlas/quality/receita/company-data/${manifest.release.value}/municipality_geography_coverage.json")
    checks += geographySummaryCheck(coverage)
    checks.toSeq
  }

  private def readParquet(spark: SparkSession, path: Path, checks: ArrayBuffer[BundleValidationCheck], name: String): Option[DataFrame] = {
    val started = System.nanoTime()
    try {
      val frame = spark.read.parquet(path.toString)
      checks += check(s"component.$name.readable", BundleCheckStatus.Pass, "Parquet component is readable",
        component = Some(name), durationMs = elapsedMs(started))
      Some(frame)
    } catch {
      case error: Throwable =>
        checks += check(s"component.$name.readable", BundleCheckStatus.Fail,
          s"Parquet component is unreadable: ${message(error)}", component = Some(name),
          diagnosticPath = Some(path.toString), durationMs = elapsedMs(started))
        None
    }
  }

  private def releaseCheck(frame: DataFrame, name: String, field: String, expected: String): BundleValidationCheck = {
    val started = System.nanoTime()
    val values = frame.select(field).distinct().limit(3).collect().map(row => Option(row.get(0)).fold("null")(_.toString)).sorted
    check(s"$name.release", if (values.sameElements(Array(expected))) BundleCheckStatus.Pass else BundleCheckStatus.Fail,
      "Current component contains only the bundle release", Some(expected), Some(values.mkString(",")),
      component = Some(name), durationMs = elapsedMs(started))
  }

  private def uniqueKeyCheck(frame: DataFrame, name: String, key: String): BundleValidationCheck = {
    val started = System.nanoTime()
    val invalid = frame.filter(col(key).isNull).limit(1).count() > 0 ||
      frame.groupBy(key).agg(count(lit(1)).as("rows")).filter(col("rows") > 1).limit(1).count() > 0
    check(s"$name.unique_$key", if (invalid) BundleCheckStatus.Fail else BundleCheckStatus.Pass,
      s"$key is non-null and unique", Some("no nulls or duplicates"), Some(if (invalid) "invalid keys found" else "valid"),
      component = Some(name), durationMs = elapsedMs(started))
  }

  private def historyArithmeticCheck(frame: DataFrame, name: String, release: ReleaseId): BundleValidationCheck = {
    val started = System.nanoTime()
    val row = frame.head()
    val previousIndex = frame.schema.fieldIndex("previous_record_count")
    val previous = if (row.isNullAt(previousIndex)) None else Some(row.getLong(previousIndex))
    val current = row.getAs[Long]("current_record_count")
    val inserted = row.getAs[Long]("inserted_count")
    val updated = row.getAs[Long]("updated_count")
    val removed = row.getAs[Long]("removed_count")
    val events = row.getAs[Long]("event_count")
    val eventValid = events == inserted + updated + removed
    val deltaValid = previous.forall(value => current - value == inserted - removed)
    val valid = eventValid && deltaValid
    check(s"$name.arithmetic", if (valid) BundleCheckStatus.Pass else BundleCheckStatus.Fail,
      "History counts and non-seed row delta reconcile",
      Some("event=inserted+updated+removed; current-previous=inserted-removed"),
      Some(s"previous=${previous.fold("null")(_.toString)},current=$current,inserted=$inserted,updated=$updated,removed=$removed,event=$events"),
      release = Some(release.value), component = Some(name), durationMs = elapsedMs(started))
  }

  private def geographySummaryCheck(path: Path): BundleValidationCheck = {
    val started = System.nanoTime()
    try {
      val value = ConfigFactory.parseFile(path.toFile).resolve().getLong("unresolved_codes")
      check("geography.unresolved_codes", if (value == 0L) BundleCheckStatus.Pass else BundleCheckStatus.Fail,
        "Used municipality codes have complete official geography coverage", Some("0"), Some(value.toString),
        diagnosticPath = Some(path.toString), durationMs = elapsedMs(started))
    } catch {
      case error: Throwable => check("geography.unresolved_codes", BundleCheckStatus.Fail,
        s"Geography coverage summary is missing or unreadable: ${message(error)}", Some("readable with zero unresolved codes"),
        Some("unreadable"), diagnosticPath = Some(path.toString), durationMs = elapsedMs(started))
    }
  }

  private def timed[A](checks: ArrayBuffer[BundleValidationCheck], id: String, description: String)(run: => A): A = {
    val started = System.nanoTime()
    try {
      val value = run
      checks += check(id, BundleCheckStatus.Pass, description, durationMs = elapsedMs(started))
      value
    } catch {
      case error: Throwable =>
        checks += check(id, BundleCheckStatus.Fail, s"$description: ${message(error)}", durationMs = elapsedMs(started))
        throw error
    }
  }

  private def check(
      id: String, status: BundleCheckStatus, description: String,
      expected: Option[String] = None, observed: Option[String] = None,
      release: Option[String] = None, component: Option[String] = None,
      diagnosticPath: Option[String] = None, durationMs: Long = 0L
  ): BundleValidationCheck = BundleValidationCheck(
    id, status, description, expected, observed, release, component, diagnosticPath, durationMs
  )

  private def bundleRoot(config: AtlasConfig): Path = ReleasePaths(config).atlasRoot.resolve("bundles")

  private[release] def directoryHash(path: Path): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val stream = Files.walk(path)
    try stream.iterator().asScala.filter(Files.isRegularFile(_)).toSeq.sortBy(_.toString).foreach { file =>
      digest.update(path.relativize(file).toString.getBytes(StandardCharsets.UTF_8))
      digest.update(CompanyDataManifestReader.sha256(file).getBytes(StandardCharsets.UTF_8))
    } finally stream.close()
    digest.digest().map("%02x".format(_)).mkString
  }

  private def optionalString(config: Config, path: String): Option[String] =
    if (!config.hasPath(path) || config.getIsNull(path)) None else Some(config.getString(path))
  private def optional(value: Option[String]): String = value.fold("null")(v => "\"" + escape(v) + "\"")
  private def escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
  private def elapsedMs(started: Long): Long = (System.nanoTime() - started) / 1000000L
  private def message(error: Throwable): String = Option(error.getMessage).getOrElse(error.getClass.getSimpleName)
}
