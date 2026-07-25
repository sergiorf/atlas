package atlas.release

import atlas.config.AtlasConfig
import atlas.history.{CompanyHistoryJob, EstablishmentHistoryJob}
import atlas.receita.{CompanyDataManifestReader, CompanyDataPaths, CompanyDataPipeline, ReceitaIngestJob}
import atlas.status.{RunStatus, RunStatusRegistry}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.time.Instant
import java.util.UUID
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.{coalesce, col, count, lit, sum, when}
import org.apache.spark.storage.StorageLevel
import scala.collection.JavaConverters._

final case class CompanyBundlePlan(
    fromRelease: ReleaseId,
    toRelease: ReleaseId,
    releases: Seq[ReleaseId],
    usableBytes: Long,
    dryRun: Boolean = true,
    publishedBundleId: Option[String] = None
)

final case class BundleInspection(
    bundleId: String,
    release: String,
    path: Path,
    current: Boolean,
    manifest: String
)

object CompanyBundleService {
  private val ManifestVersion = 1

  def plan(config: AtlasConfig, from: ReleaseId, to: ReleaseId): CompanyBundlePlan = {
    require(from <= to, s"from-release $from must not be newer than to-release $to")
    val releases = months(from, to)
    releases.foreach { release =>
      val releaseConfig = withRelease(config, release)
      CompanyDataManifestReader.readAndValidate(releaseConfig)
      val raw = Paths.get(ReleasePaths.rawDirForRelease(config.receita.rawDir, release))
      if (!Files.isDirectory(raw)) throw new IllegalArgumentException(s"Missing raw establishment input for $release: $raw")
      validateEstablishmentManifest(raw, release)
    }
    val store = Files.getFileStore(nearestExisting(bundleRoot(config)))
    CompanyBundlePlan(from, to, releases, store.getUsableSpace)
  }

  def rebuild(spark: SparkSession, config: AtlasConfig, plan: CompanyBundlePlan): CompanyBundlePlan =
    PublicationLock.withCompanyBundleLock(config) {
      val bundleId = s"${plan.toRelease.value}-${UUID.randomUUID()}"
      val staging = stagingRoot(config).resolve(bundleId)
      val stagedConfig = staged(config, staging)
      try {
        plan.releases.foreach(release => buildRelease(spark, withRelease(stagedConfig, release), bundleId))
        validateBundle(spark, withRelease(stagedConfig, plan.toRelease), plan.releases)
        writeManifest(withRelease(stagedConfig, plan.toRelease), staging, bundleId, plan.releases, None)
        publish(config, staging, bundleId, plan.toRelease, generation =>
          activateStatuses(config, staging, generation, bundleId, plan.toRelease))
        plan.copy(dryRun = false, publishedBundleId = Some(bundleId))
      } catch {
        case error: Throwable =>
          val failed = retainFailedCandidate(config, staging, bundleId, error)
          recordBundleFailure(config, plan.toRelease, error, failed)
          throw error
      }
    }

  def refresh(spark: SparkSession, config: AtlasConfig, release: ReleaseId): CompanyBundlePlan =
    PublicationLock.withCompanyBundleLock(config) {
      val current = inspect(config, None).getOrElse(throw new IllegalStateException("No current bundle; run chronological rebuild first"))
      val currentRelease = ReleaseId.unsafe(current.release)
      if (release <= currentRelease) throw new IllegalArgumentException(s"Candidate $release must be newer than current $currentRelease")
      val checked = plan(config, release, release)
      val bundleId = s"${release.value}-${UUID.randomUUID()}"
      val staging = stagingRoot(config).resolve(bundleId)
      try {
        copyTree(current.path.resolve("data"), staging.resolve("data"))
        val stagedConfig = staged(config, staging)
        buildRelease(spark, withRelease(stagedConfig, release), bundleId)
        validateBundle(spark, withRelease(stagedConfig, release), Seq(release))
        writeManifest(withRelease(stagedConfig, release), staging, bundleId, Seq(release), Some(current.bundleId))
        publish(config, staging, bundleId, release, generation =>
          activateStatuses(config, staging, generation, bundleId, release))
        checked.copy(dryRun = false, publishedBundleId = Some(bundleId))
      } catch {
        case error: Throwable =>
          val failed = retainFailedCandidate(config, staging, bundleId, error)
          recordBundleFailure(config, release, error, failed)
          throw error
      }
    }

  def inspect(config: AtlasConfig, release: Option[ReleaseId]): Option[BundleInspection] = {
    val currentPointer = bundleRoot(config).resolve("current_bundle.json")
    val currentId = if (Files.isRegularFile(currentPointer)) Some(field(Files.readString(currentPointer), "bundle_id")) else None
    val generations = bundleRoot(config).resolve("generations")
    if (!Files.isDirectory(generations)) return None
    val stream = Files.list(generations)
    try {
      stream.iterator().asScala.filter(Files.isDirectory(_)).flatMap { path =>
        val manifestPath = path.resolve("bundle-manifest.json")
        if (!Files.isRegularFile(manifestPath)) None
        else {
          val body = Files.readString(manifestPath)
          val id = field(body, "bundle_id")
          val value = field(body, "release")
          Some(BundleInspection(id, value, path, currentId.contains(id), body))
        }
      }.filter { value =>
        release match {
          case Some(selected) => selected.value == value.release
          case None => currentId.contains(value.bundleId)
        }
      }.toSeq.sortBy(value => (value.release, value.bundleId)).lastOption
    } finally stream.close()
  }

  def render(plan: CompanyBundlePlan): String = {
    val verb = if (plan.dryRun) "Dry run" else "Published"
    s"$verb for Receita company-data ${plan.fromRelease} through ${plan.toRelease}\n" +
      s"Releases: ${plan.releases.mkString(", ")}\nUsable filesystem bytes: ${plan.usableBytes}" +
      plan.publishedBundleId.fold("")(id => s"\nBundle: $id") +
      "\nRaw archives and captures are verified and never modified."
  }

  def render(inspection: BundleInspection): String =
    s"bundle_id=${inspection.bundleId}\nrelease=${inspection.release}\ncurrent=${inspection.current}\npath=${inspection.path}\n${inspection.manifest}"

  private def buildRelease(spark: SparkSession, config: AtlasConfig, bundleId: String): Unit = {
    ReceitaIngestJob.run(spark, config)
    EstablishmentHistoryJob.refresh(spark, config)
    val companyBuild = CompanyDataPipeline.build(spark, config)
    val history = try CompanyHistoryJob.refresh(spark, config, bundleId, companyBuild.quality)
    catch {
      case error: Throwable =>
        val failedAt = Instant.now()
        RunStatusRegistry.write(Paths.get(config.statusDir), RunStatus(
          "receita", "companies", config.receita.snapshot, "history", "failed",
          failedAt, failedAt, 0.0, None,
          Seq(CompanyDataPaths.silverCompanyCandidate(config).toString), None, Seq.empty, Some("1"),
          Some(config.spark.appName), Some("refresh-receita-company-data"),
          Some(error.getClass.getName), Option(error.getMessage)
        ))
        throw error
    }
    val now = Instant.now()
    RunStatusRegistry.write(Paths.get(config.statusDir), RunStatus(
      "receita", "companies", config.receita.snapshot, "history", "success", now, now, 0.0,
      Some(history.eventCount), Seq(CompanyDataPaths.silverCompanyCandidate(config).toString),
      Some(CompanyHistoryJob.eventRelease(config).toString), Seq.empty, Some("1"),
      Some(config.spark.appName), Some("refresh-receita-company-data"),
      inputRowCount = Some(history.currentRows), outputRowCount = Some(history.eventCount),
      previousRowCount = history.previousRows,
      netRowDelta = history.previousRows.map(history.currentRows - _),
      insertedRowCount = Some(history.inserted), updatedRowCount = Some(history.updated),
      removedRowCount = Some(history.removed)
    ))
    deleteTree(ReleasePaths(config).atlasRoot.resolve("work"))
  }

  private def validateEstablishmentManifest(extracted: Path, release: ReleaseId): Unit = {
    val datasetRoot = Option(extracted.getParent).getOrElse(extracted)
    val manifest = datasetRoot.resolve("manifest.json")
    if (!Files.isRegularFile(manifest)) throw new IllegalArgumentException(s"Missing establishment manifest for $release: $manifest")
    val parsed = com.typesafe.config.ConfigFactory.parseFile(manifest.toFile).resolve()
    if (!parsed.hasPath("month") || parsed.getString("month") != release.value)
      throw new IllegalArgumentException(s"Establishment manifest does not declare release $release: $manifest")
    if (!parsed.hasPath("dataset") || parsed.getString("dataset") != "estabelecimentos")
      throw new IllegalArgumentException(s"Unexpected establishment dataset in $manifest")
    val files = parsed.getConfig("files").root().keySet().asScala
    if (files.isEmpty) throw new IllegalArgumentException(s"Establishment manifest has no archives: $manifest")
    files.foreach { name =>
      val entry = parsed.getConfig("files").root().get(name).asInstanceOf[com.typesafe.config.ConfigObject].toConfig
      if (entry.getString("status") != "complete" || !entry.getBoolean("extracted"))
        throw new IllegalArgumentException(s"Establishment archive is not complete and extracted: $name")
      val archive = datasetRoot.resolve("archives").resolve(name)
      if (!Files.isRegularFile(archive) || Files.size(archive) != entry.getLong("bytes"))
        throw new IllegalArgumentException(s"Establishment archive size does not match its manifest: $archive")
    }
  }

  private[atlas] def validateBundle(spark: SparkSession, config: AtlasConfig, releases: Seq[ReleaseId]): Unit = {
    val companies = spark.read.parquet(CompanyDataPaths.silverCompanies(config).toString)
    val establishments = spark.read.parquet(ReleasePaths(config).silverCurrent.toString)
    val companyReleases = companies.select("release").distinct().collect().map(_.getString(0)).toSeq
    val establishmentReleases = establishments.select("release").distinct().collect().map(_.getString(0)).toSeq
    if (companyReleases != Seq(config.receita.snapshot) || establishmentReleases != Seq(config.receita.snapshot))
      throw new IllegalStateException("Candidate company and establishment current tables do not share the target release")
    if (companies.groupBy("cnpj_root").count().filter(col("count") > 1).limit(1).count() > 0)
      throw new IllegalStateException("Candidate companies contain duplicate cnpj_root")
    if (establishments.groupBy("cnpj_full").count().filter(col("count") > 1).limit(1).count() > 0)
      throw new IllegalStateException("Candidate establishments contain duplicate cnpj_full")
    validateGeographyCoverage(establishments, spark.read.parquet(CompanyDataPaths.geography(config).toString), config)
    releases.foreach { release =>
      val releaseConfig = withRelease(config, release)
      val companySummary = CompanyHistoryJob.summaryRelease(releaseConfig)
      val establishmentSummary = ReleasePaths(releaseConfig).summaryRelease
      if (!Files.exists(companySummary) || !Files.exists(establishmentSummary))
        throw new IllegalStateException(s"Missing release summary for $release")
    }
  }

  private[atlas] def validateGeographyCoverage(
      establishments: org.apache.spark.sql.DataFrame,
      geography: org.apache.spark.sql.DataFrame,
      config: AtlasConfig
  ): Unit = {
    val coverage = establishments.filter(col("municipality_code").isNotNull)
      .join(
        geography.select(
          col("receita_municipality_code"),
          col("state_abbreviation").as("geography_state"),
          col("mapping_source")
        ),
        col("municipality_code") === col("receita_municipality_code"),
        "left"
      )
      .withColumn("coverage_status",
        when(col("receita_municipality_code").isNull, lit("unresolved"))
          .when(col("state").isNotNull && col("state") =!= col("geography_state"), lit("state_conflict"))
          .otherwise(lit("resolved")))
      .withColumn("mapping_source", coalesce(col("mapping_source"), lit("unresolved")))
      .groupBy("municipality_code", "state", "coverage_status", "mapping_source")
      .agg(count(lit(1)).as("establishment_count"))
      .persist(StorageLevel.DISK_ONLY)
    try {
      coverage.write.mode("overwrite").parquet(CompanyDataPaths.geographyCoverage(config).toString)
      val metrics = coverage.agg(
        sum("establishment_count").as("used_establishment_rows"),
        sum(when(col("coverage_status") === "unresolved", col("establishment_count")).otherwise(lit(0L)))
          .as("unresolved_establishment_rows"),
        sum(when(col("coverage_status") === "state_conflict", col("establishment_count")).otherwise(lit(0L)))
          .as("state_conflict_establishment_rows"),
        sum(when(col("mapping_source") === "verified_override", col("establishment_count")).otherwise(lit(0L)))
          .as("override_establishment_rows"),
        sum(when(col("mapping_source") === "carried_forward", col("establishment_count")).otherwise(lit(0L)))
          .as("carried_forward_establishment_rows"),
        sum(when(col("mapping_source") === "current_tom", col("establishment_count")).otherwise(lit(0L)))
          .as("current_tom_establishment_rows")
      ).head()
      val unresolved = coverage.filter(col("coverage_status") === "unresolved")
      val unresolvedCodeCount = unresolved.select("municipality_code").distinct().count()
      val stateConflictCodeCount = coverage.filter(col("coverage_status") === "state_conflict")
        .select("municipality_code").distinct().count()
      val examples = unresolved.orderBy(col("establishment_count").desc, col("municipality_code"))
        .limit(20).collect().map { row =>
          val code = escape(row.getAs[String]("municipality_code"))
          val state = Option(row.getAs[String]("state"))
            .fold("null")(value => "\"" + escape(value) + "\"")
          s"""{"municipality_code":"$code","state":$state,"establishment_count":${row.getAs[Long]("establishment_count")}}"""
        }.mkString(",")
      val body =
        s"""{"release":"${escape(config.receita.snapshot)}","used_establishment_rows":${metrics.getAs[Long]("used_establishment_rows")},"current_tom_establishment_rows":${metrics.getAs[Long]("current_tom_establishment_rows")},"override_establishment_rows":${metrics.getAs[Long]("override_establishment_rows")},"carried_forward_establishment_rows":${metrics.getAs[Long]("carried_forward_establishment_rows")},"unresolved_codes":$unresolvedCodeCount,"unresolved_establishment_rows":${metrics.getAs[Long]("unresolved_establishment_rows")},"state_conflict_codes":$stateConflictCodeCount,"state_conflict_establishment_rows":${metrics.getAs[Long]("state_conflict_establishment_rows")},"unresolved_examples":[$examples]}"""
      Files.createDirectories(CompanyDataPaths.geographyCoverageSummary(config).getParent)
      Files.writeString(CompanyDataPaths.geographyCoverageSummary(config), body + "\n", StandardCharsets.UTF_8)
      if (unresolvedCodeCount > 0)
        throw new IllegalStateException(
          s"Candidate geography has $unresolvedCodeCount unresolved municipality code(s) affecting " +
            s"${metrics.getAs[Long]("unresolved_establishment_rows")} establishment(s); examples=[$examples]; " +
            s"report=${CompanyDataPaths.geographyCoverage(config)}"
        )
      if (stateConflictCodeCount > 0)
        throw new IllegalStateException(
          s"Candidate geography has $stateConflictCodeCount municipality code(s) with establishment-state conflicts affecting " +
            s"${metrics.getAs[Long]("state_conflict_establishment_rows")} establishment(s); " +
            s"report=${CompanyDataPaths.geographyCoverage(config)}"
        )
    } finally coverage.unpersist()
  }

  private def writeManifest(
      config: AtlasConfig,
      staging: Path,
      bundleId: String,
      releases: Seq[ReleaseId],
      previous: Option[String]
  ): Unit = {
    val components = Seq(
      "companies" -> CompanyDataPaths.silverCompanies(config),
      "establishments" -> ReleasePaths(config).silverCurrent,
      "company_history" -> CompanyHistoryJob.eventRoot(config),
      "establishment_history" -> ReleasePaths(config).historyRoot,
      "company_summaries" -> CompanyHistoryJob.summaryRoot(config),
      "establishment_summaries" -> ReleasePaths(config).summaryRoot,
      "municipality_geography" -> CompanyDataPaths.geography(config)
    ) ++ atlas.receita.CompanyDataSchemas.referenceGroups.map(name => name -> CompanyDataPaths.silverReference(config, name))
    val componentJson = components.map { case (name, path) =>
      val relative = staging.relativize(path).toString.replace('\\', '/')
      s"""{"name":"${escape(name)}","path":"${escape(relative)}","sha256":"${directoryHash(path)}"}"""
    }.mkString(",")
    val rawManifest = CompanyDataManifestReader.readAndValidate(config)
    val establishmentManifest = Paths.get(ReleasePaths.rawDirForRelease(config.receita.rawDir, ReleaseId.unsafe(config.receita.snapshot))).getParent.resolve("manifest.json")
    val establishmentManifestHash = CompanyDataManifestReader.sha256(establishmentManifest)
    val previousJson = previous.fold("null")(id => "\"" + escape(id) + "\"")
    val releasesJson = releases.map(value => "\"" + value.toString + "\"").mkString(",")
    val body = s"""{"manifest_version":$ManifestVersion,"bundle_id":"${escape(bundleId)}","release":"${config.receita.snapshot}","created_at":"${Instant.now()}","producer_version":"development","previous_bundle_id":$previousJson,"releases":[$releasesJson],"establishment_source_manifest_sha256":"$establishmentManifestHash","company_source_manifest_sha256":"${rawManifest.manifestHash}","tom_sha256":"${rawManifest.tomHash}","ibge_sha256":"${rawManifest.ibgeHash}","components":[$componentJson]}"""
    Files.writeString(staging.resolve("bundle-manifest.json"), body + "\n", StandardCharsets.UTF_8)
  }

  private[release] def publish(
      config: AtlasConfig,
      staging: Path,
      bundleId: String,
      release: ReleaseId,
      afterSwitch: Path => Unit = _ => ()
  ): Unit = {
    val generations = bundleRoot(config).resolve("generations")
    Files.createDirectories(generations)
    val generation = generations.resolve(bundleId)
    move(staging, generation)
    val pointer = bundleRoot(config).resolve("current_bundle.json")
    val previous = if (Files.isRegularFile(pointer)) Some(Files.readAllBytes(pointer)) else None
    val temporary = bundleRoot(config).resolve(s"current_bundle.json.${UUID.randomUUID()}.tmp")
    Files.writeString(temporary, s"""{"bundle_id":"${escape(bundleId)}","release":"$release"}
""", StandardCharsets.UTF_8)
    try Files.move(temporary, pointer, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    catch { case _: java.nio.file.AtomicMoveNotSupportedException =>
      throw new IllegalStateException(s"Atomic bundle pointer replacement is not supported at $pointer")
    }
    try {
      val observed = inspect(config, None).getOrElse(throw new IllegalStateException("Published bundle pointer is unreadable"))
      if (observed.bundleId != bundleId) throw new IllegalStateException("Published bundle pointer failed read-after-switch")
      afterSwitch(generation)
    } catch {
      case error: Throwable =>
        val rollback = bundleRoot(config).resolve(s"current_bundle.json.${UUID.randomUUID()}.rollback")
        try {
          previous match {
            case Some(bytes) =>
              Files.write(rollback, bytes)
              Files.move(rollback, pointer, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            case None => Files.deleteIfExists(pointer)
          }
        } catch { case rollbackError: Throwable => error.addSuppressed(rollbackError) }
        throw error
    }
  }

  private def bundleStatus(config: AtlasConfig, bundleId: String, release: ReleaseId): RunStatus = {
    val now = Instant.now()
    val generation = bundleRoot(config).resolve("generations").resolve(bundleId)
    RunStatus(
      "receita", "company-data", release.value, "bundle", "success", now, now, 0.0, None,
      Seq(
        Paths.get(ReleasePaths.rawDirForRelease(config.receita.rawDir, release)).toString,
        CompanyDataPaths.rawRoot(withRelease(config, release)).toString
      ),
      Some(generation.toString), Seq.empty, Some(ManifestVersion.toString), Some(config.spark.appName),
      Some("refresh-receita-company-data")
    )
  }

  private def activateStatuses(
      config: AtlasConfig,
      staging: Path,
      generation: Path,
      bundleId: String,
      release: ReleaseId
  ): Unit = {
    val stagedRoot = generation.resolve("data/_atlas/status")
    val scan = RunStatusRegistry.scan(stagedRoot)
    if (scan.errors.nonEmpty)
      throw new IllegalStateException(s"Candidate component status is unreadable: ${scan.errors.head.path}: ${scan.errors.head.message}")
    val canonical = scan.statuses.map(status => canonicalizeStatus(status, staging, generation)) :+
      bundleStatus(config, bundleId, release)
    val root = Paths.get(config.statusDir)
    val previous = canonical.map(status => {
      val path = RunStatusRegistry.statusPath(root, status)
      path -> (if (Files.isRegularFile(path)) Some(Files.readAllBytes(path)) else None)
    }).toMap
    try canonical.foreach(RunStatusRegistry.write(root, _))
    catch {
      case error: Throwable =>
        previous.foreach {
          case (path, Some(bytes)) =>
            try Files.write(path, bytes) catch { case restore: Throwable => error.addSuppressed(restore) }
          case (path, None) =>
            try Files.deleteIfExists(path) catch { case restore: Throwable => error.addSuppressed(restore) }
        }
        throw error
    }
  }

  private def canonicalizeStatus(status: RunStatus, staging: Path, generation: Path): RunStatus = {
    def path(value: String): String =
      if (Paths.get(value).normalize().startsWith(staging.normalize()))
        generation.resolve(staging.normalize().relativize(Paths.get(value).normalize())).toString
      else value
    status.copy(
      inputPaths = status.inputPaths.map(path),
      outputPath = status.outputPath.map(path),
      qualityWarnings = status.qualityWarnings.map(warning => warning.copy(reportPath = path(warning.reportPath)))
    )
  }

  private def retainFailedCandidate(
      config: AtlasConfig,
      staging: Path,
      bundleId: String,
      error: Throwable
  ): Option[Path] = {
    if (!Files.exists(staging)) return None
    val failed = bundleRoot(config).resolve("failed").resolve(bundleId)
    try {
      Files.createDirectories(failed.getParent)
      move(staging, failed)
      val statusRoot = failed.resolve("data/_atlas/status")
      val scan = RunStatusRegistry.scan(statusRoot)
      scan.errors.foreach(readError =>
        error.addSuppressed(new IllegalStateException(
          s"Failed candidate status is unreadable: ${readError.path}: ${readError.message}"
        )))
      scan.statuses.foreach(status =>
        RunStatusRegistry.write(statusRoot, canonicalizeStatus(status, staging, failed)))
      Some(failed)
    } catch {
      case cleanup: Throwable =>
        error.addSuppressed(cleanup)
        if (Files.isDirectory(failed)) Some(failed) else None
    }
  }

  private def recordBundleFailure(
      config: AtlasConfig,
      release: ReleaseId,
      error: Throwable,
      diagnosticPath: Option[Path]
  ): Unit = {
    val now = Instant.now()
    try RunStatusRegistry.write(Paths.get(config.statusDir), RunStatus(
      "receita", "company-data", release.value, "bundle", "failed", now, now, 0.0, None,
      Seq(
        Paths.get(ReleasePaths.rawDirForRelease(config.receita.rawDir, release)).toString,
        CompanyDataPaths.rawRoot(withRelease(config, release)).toString
      ),
      diagnosticPath.map(_.toString), Seq.empty, Some(ManifestVersion.toString), Some(config.spark.appName),
      Some("refresh-receita-company-data"), Some(error.getClass.getName), Option(error.getMessage)
    )) catch { case statusError: Throwable => error.addSuppressed(statusError) }
  }

  private def staged(config: AtlasConfig, staging: Path): AtlasConfig = config.copy(
    receita = config.receita.copy(
      bronzeDir = staging.resolve("data/bronze/receita").toString,
      silverDir = staging.resolve("data/silver/receita").toString
    ),
    statusDir = staging.resolve("data/_atlas/status").toString
  )

  private def withRelease(config: AtlasConfig, release: ReleaseId): AtlasConfig = config.copy(receita = config.receita.copy(snapshot = release.value))
  private def bundleRoot(config: AtlasConfig): Path = ReleasePaths(config).atlasRoot.resolve("bundles")
  private def stagingRoot(config: AtlasConfig): Path = bundleRoot(config).resolve("staging")

  private def directoryHash(path: Path): String = {
    if (!Files.exists(path)) return java.security.MessageDigest.getInstance("SHA-256").digest(Array.emptyByteArray).map("%02x".format(_)).mkString
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val stream = Files.walk(path)
    try stream.iterator().asScala.filter(Files.isRegularFile(_)).toSeq.sortBy(_.toString).foreach { file =>
      digest.update(path.relativize(file).toString.getBytes(StandardCharsets.UTF_8))
      digest.update(CompanyDataManifestReader.sha256(file).getBytes(StandardCharsets.UTF_8))
    } finally stream.close()
    digest.digest().map("%02x".format(_)).mkString
  }

  private def field(json: String, name: String): String = {
    val pattern = ("\\\"" + name + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").r
    pattern.findFirstMatchIn(json).map(_.group(1)).getOrElse(throw new IllegalStateException(s"Missing $name in bundle metadata"))
  }

  private def copyTree(source: Path, target: Path): Unit = {
    val stream = Files.walk(source)
    try stream.iterator().asScala.foreach { path =>
      val destination = target.resolve(source.relativize(path))
      if (Files.isDirectory(path)) Files.createDirectories(destination)
      else Files.copy(path, destination)
    } finally stream.close()
  }

  private def deleteTree(path: Path): Unit = if (Files.exists(path)) {
    val stream = Files.walk(path)
    try stream.iterator().asScala.toSeq.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
    finally stream.close()
  }

  private def move(source: Path, target: Path): Unit = {
    Files.createDirectories(target.getParent)
    try Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    catch { case _: java.nio.file.AtomicMoveNotSupportedException =>
      throw new IllegalStateException(s"Atomic generation promotion is not supported from $source to $target")
    }
  }

  private def months(from: ReleaseId, to: ReleaseId): Seq[ReleaseId] = {
    val start = java.time.YearMonth.parse(from.value)
    val end = java.time.YearMonth.parse(to.value)
    Iterator.iterate(start)(_.plusMonths(1)).takeWhile(!_.isAfter(end)).map(value => ReleaseId.unsafe(value.toString)).toSeq
  }

  private def nearestExisting(path: Path): Path = Iterator.iterate(path)(_.getParent).takeWhile(_ != null).find(Files.exists(_)).get
  private def escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}
