package atlas.release

import atlas.config.AtlasConfig
import atlas.status.RunStatusRegistry
import com.typesafe.config.ConfigFactory
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{
  AtomicMoveNotSupportedException,
  FileVisitResult,
  Files,
  LinkOption,
  Path,
  Paths,
  SimpleFileVisitor,
  StandardCopyOption
}
import java.time.{Duration, Instant}
import java.time.format.DateTimeFormatter
import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal

final case class StorageCleanupPolicy(
    olderThanDays: Int,
    workOlderThanDays: Int,
    retainBundles: Int,
    retainBronzeReleases: Int,
    includedKinds: Set[String]
) {
  require(olderThanDays >= 0, "--older-than-days must be non-negative")
  require(workOlderThanDays >= 0, "--work-older-than-days must be non-negative")
  require(retainBundles >= 2, "--retain-bundles must be at least 2")
  require(retainBronzeReleases >= 0, "--retain-bronze-releases must be non-negative")
  require(includedKinds.subsetOf(StorageCleanupPolicy.Kinds), "unknown cleanup candidate kind")
}

object StorageCleanupPolicy {
  val Kinds: Set[String] = Set("trash", "failed-bundles", "inactive-bundles", "bronze", "work")
  def defaults(config: AtlasConfig): StorageCleanupPolicy = StorageCleanupPolicy(
    config.storageCleanup.olderThanDays,
    config.storageCleanup.workOlderThanDays,
    config.storageCleanup.retainBundles,
    config.storageCleanup.retainBronzeReleases,
    Kinds
  )
}

final case class CleanupCandidate(
    kind: String,
    id: String,
    release: Option[String],
    path: Path,
    observedAt: Option[Instant],
    age: Option[Duration],
    bytes: Long,
    files: Long,
    eligible: Boolean,
    blockingReasonCodes: Seq[String],
    blockingReasons: Seq[String]
)

final case class StorageCleanupResult(
    dryRun: Boolean,
    policy: StorageCleanupPolicy,
    trash: TrashPurgeResult,
    candidates: Seq[CleanupCandidate],
    quarantinedBytes: Long
) {
  def olderThanDays: Int = policy.olderThanDays
  def failedBundles: Seq[CleanupCandidate] = candidates.filter(_.kind == "failed_bundle")
}

object StorageCleanupService {
  val ContractVersion = 2
  private val BundlePattern =
    """^([0-9]{4}-[0-9]{2})-([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$""".r
  private val ReleaseDirectoryPattern = "^release=([0-9]{4}-[0-9]{2})$".r

  def inspect(
      config: AtlasConfig,
      olderThanDays: Int = 7,
      now: Instant = Instant.now()
  ): StorageCleanupResult =
    inspect(config, StorageCleanupPolicy.defaults(config).copy(olderThanDays = olderThanDays), now)

  def inspect(
      config: AtlasConfig,
      policy: StorageCleanupPolicy,
      now: Instant
  ): StorageCleanupResult = {
    val candidates = inspectLiveCandidates(config, policy, now)
    val trash =
      if (policy.includedKinds("trash")) TrashPurgeService.inspect(config, policy.olderThanDays, now)
      else TrashPurgeResult(dryRun = true, policy.olderThanDays, Seq.empty, 0L, 0L)
    StorageCleanupResult(dryRun = true, policy, trash, candidates, quarantinedBytes = 0L)
  }

  def force(
      config: AtlasConfig,
      olderThanDays: Int = 7,
      now: Instant = Instant.now()
  ): StorageCleanupResult =
    force(config, StorageCleanupPolicy.defaults(config).copy(olderThanDays = olderThanDays), now)

  def force(
      config: AtlasConfig,
      policy: StorageCleanupPolicy,
      now: Instant
  ): StorageCleanupResult =
    PublicationLock.withCompanyBundleLock(config) {
      PublicationLock.withEstablishmentsLock(config) {
        // Purge first so this invocation never permanently deletes newly quarantined data.
        val purged =
          if (policy.includedKinds("trash"))
            TrashPurgeService.forceUnlocked(config, policy.olderThanDays, now)
          else TrashPurgeResult(dryRun = false, policy.olderThanDays, Seq.empty, 0L, 0L)
        val checked = inspectLiveCandidates(config, policy, now)
        val eligible = checked.filter(_.eligible)
        eligible.foreach(candidate => quarantine(config, candidate, now))
        StorageCleanupResult(
          dryRun = false,
          policy,
          purged,
          checked,
          quarantinedBytes = eligible.map(_.bytes).sum
        )
      }
    }

  def render(result: StorageCleanupResult): String = {
    val trashRows = result.trash.generations.map { generation =>
      Seq(
        "trash",
        generation.path.toString,
        generation.age.fold("unknown")(_.toHours.toString + "h"),
        human(generation.bytes),
        "delete",
        decision(result.dryRun, generation.eligible, "deleted", generation.blockingReasons)
      )
    }
    val liveRows = result.candidates.map { candidate =>
      Seq(
        candidate.kind.replace('_', '-'),
        candidate.path.toString,
        candidate.age.fold("unknown")(_.toHours.toString + "h"),
        human(candidate.bytes),
        "quarantine",
        decision(result.dryRun, candidate.eligible, "quarantined", candidate.blockingReasons)
      )
    }
    val rows = (trashRows ++ liveRows).sortBy(row => (row.head, row(1)))
    val heading =
      if (result.dryRun) "ATLAS STORAGE CLEANUP — DRY RUN" else "ATLAS STORAGE CLEANUP — COMPLETE"
    val tableText =
      if (rows.isEmpty) "No cleanup candidates found."
      else table(Seq("location", "candidate", "age", "size", "action", "decision"), rows)
    val eligibleDelete = result.trash.generations.filter(_.eligible).map(_.bytes).sum
    val eligibleQuarantine = result.candidates.filter(_.eligible).map(_.bytes).sum
    val blocked = result.trash.generations.filterNot(_.eligible).map(_.bytes).sum +
      result.candidates.filterNot(_.eligible).map(_.bytes).sum
    s"$heading\nRecovery window: ${result.policy.olderThanDays} days; " +
      s"retain bundles: ${result.policy.retainBundles}; retain bronze releases: ${result.policy.retainBronzeReleases}\n\n" +
      s"$tableText\n\nEligible for permanent deletion: ${human(eligibleDelete)}\n" +
      s"Eligible for quarantine: ${human(eligibleQuarantine)}\n" +
      s"Blocked or protected: ${human(blocked)}\n" +
      s"Deleted now: ${human(result.trash.deletedBytes)}\n" +
      s"Quarantined now: ${human(result.quarantinedBytes)}\n" +
      "Raw data was not considered or touched."
  }

  def json(result: StorageCleanupResult): String = {
    val trash = result.trash.generations.map { generation =>
      candidateJson(
        "trash",
        generation.path.getFileName.toString,
        generation.path,
        None,
        generation.timestamp,
        generation.age,
        generation.bytes,
        None,
        "delete",
        generation.eligible,
        Seq.empty,
        generation.blockingReasons
      )
    }
    val live = result.candidates.map { candidate =>
      candidateJson(
        candidate.kind,
        candidate.id,
        candidate.path,
        candidate.release,
        candidate.observedAt,
        candidate.age,
        candidate.bytes,
        Some(candidate.files),
        "quarantine",
        candidate.eligible,
        candidate.blockingReasonCodes,
        candidate.blockingReasons
      )
    }
    val eligibleDelete = result.trash.generations.filter(_.eligible).map(_.bytes).sum
    val eligibleQuarantine = result.candidates.filter(_.eligible).map(_.bytes).sum
    s"""{"contract_version":$ContractVersion,"dry_run":${result.dryRun},"older_than_days":${result.policy.olderThanDays},"work_older_than_days":${result.policy.workOlderThanDays},"retain_bundles":${result.policy.retainBundles},"retain_bronze_releases":${result.policy.retainBronzeReleases},"included_kinds":[${result.policy.includedKinds.toSeq.sorted.map(quoted).mkString(",")}],"candidates":[${(trash ++ live).mkString(",")}],"eligible_delete_bytes":$eligibleDelete,"eligible_quarantine_bytes":$eligibleQuarantine} """.trim
  }

  private def inspectLiveCandidates(
      config: AtlasConfig,
      policy: StorageCleanupPolicy,
      now: Instant
  ): Seq[CleanupCandidate] = {
    val selected = ArrayBuffer.empty[CleanupCandidate]
    if (policy.includedKinds("failed-bundles")) selected ++= inspectFailedBundles(config, policy, now)
    if (policy.includedKinds("inactive-bundles")) selected ++= inspectBundleGenerations(config, policy, now)
    if (policy.includedKinds("bronze")) selected ++= inspectBronze(config, policy, now)
    if (policy.includedKinds("work")) selected ++= inspectWork(config, policy, now)
    selected.toSeq.sortBy(candidate => (candidate.kind, candidate.path.toString))
  }

  private def inspectFailedBundles(
      config: AtlasConfig,
      policy: StorageCleanupPolicy,
      now: Instant
  ): Seq[CleanupCandidate] = {
    val root = ReleasePaths(config).atlasRoot.resolve("bundles/failed")
    val context = referenceContext(config)
    children(root).map { path =>
      val reasons = ArrayBuffer.empty[(String, String)]
      val bundleId = path.getFileName.toString
      val release = bundleId match {
        case BundlePattern(value, _) => Some(value)
        case _ => add(reasons, "unrecognized_identifier", "unrecognized failed bundle identifier"); None
      }
      reasons ++= context.errors
      val measurement = measure(root, path)
      reasons ++= measurement._3
      val internal = RunStatusRegistry.scan(path.resolve("data/_atlas/status"))
      internal.errors.foreach(error => add(reasons, "malformed_status", s"malformed failed candidate status: ${error.path}"))
      val manifestPath = path.resolve("bundle-manifest.json")
      val manifestTime =
        if (!Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) None
        else try Some(Instant.parse(ConfigFactory.parseFile(manifestPath.toFile).resolve().getString("created_at")))
        catch {
          case NonFatal(error) =>
            add(reasons, "malformed_manifest", s"malformed failed bundle manifest: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}")
            None
        }
      val observedAt = (manifestTime.toSeq ++ internal.statuses.map(_.finishedAt)).sorted.lastOption
      ageReasons(reasons, observedAt, policy.olderThanDays, now)
      if (context.currentBundleId.contains(bundleId)) add(reasons, "current_bundle", "referenced by current bundle pointer")
      if (context.allReferences.exists(reference => references(reference, path)))
        add(reasons, "active_status", "referenced by active status metadata")
      if (context.transactionBodies.exists(body => body.contains(bundleId) || body.contains(path.toString)))
        add(reasons, "active_transaction", "referenced by active transaction journal")
      candidate("failed_bundle", bundleId, release, path, observedAt, measurement, reasons, now)
    }
  }

  private def inspectBundleGenerations(
      config: AtlasConfig,
      policy: StorageCleanupPolicy,
      now: Instant
  ): Seq[CleanupCandidate] = {
    val root = ReleasePaths(config).atlasRoot.resolve("bundles/generations")
    val context = referenceContext(config)
    val manifests = children(root).map { path =>
      val file = path.resolve("bundle-manifest.json")
      val parsed = try {
        val c = ConfigFactory.parseFile(file.toFile).resolve()
        Right((c.getString("bundle_id"), c.getString("release"), Instant.parse(c.getString("created_at")), optional(c, "previous_bundle_id")))
      } catch { case NonFatal(error) => Left(Option(error.getMessage).getOrElse(error.getClass.getSimpleName)) }
      path -> parsed
    }
    val byId = manifests.collect { case (path, Right((id, release, created, previous))) => id -> (path, release, created, previous) }.toMap
    val retained = ArrayBuffer.empty[String]
    var cursor = context.currentBundleId
    while (cursor.nonEmpty && retained.size < policy.retainBundles) {
      val id = cursor.get
      retained += id
      cursor = byId.get(id).flatMap(_._4)
    }
    manifests.collect { case (_, Right((id, _, created, _))) => id -> created }
      .sortBy(_._2)(Ordering[Instant].reverse)
      .map(_._1)
      .filterNot(retained.contains)
      .take(policy.retainBundles - retained.size)
      .foreach(retained += _)
    manifests.map { case (path, parsed) =>
      val reasons = ArrayBuffer.empty[(String, String)]
      reasons ++= context.errors
      val measurement = measure(root, path)
      reasons ++= measurement._3
      val (id, release, observedAt) = parsed match {
        case Left(error) =>
          add(reasons, "malformed_manifest", s"malformed bundle manifest: $error")
          (path.getFileName.toString, None, None)
        case Right((id, release, created, _)) =>
          if (id != path.getFileName.toString)
            add(reasons, "identity_mismatch", "bundle manifest identity does not match directory")
          (id, Some(release), Some(created))
      }
      ageReasons(reasons, observedAt, policy.olderThanDays, now)
      if (context.currentBundleId.contains(id)) add(reasons, "current_bundle", "selected by current bundle pointer")
      else if (retained.contains(id)) add(reasons, "retained_predecessor", "retained current-bundle predecessor")
      if (context.activeReferences.exists(reference => references(reference, path)))
        add(reasons, "active_status", "referenced by active status metadata")
      if (context.transactionBodies.exists(body => body.contains(id) || body.contains(path.toString)))
        add(reasons, "active_transaction", "referenced by active transaction journal")
      candidate("inactive_bundle", id, release, path, observedAt, measurement, reasons, now)
    }
  }

  private def inspectBronze(
      config: AtlasConfig,
      policy: StorageCleanupPolicy,
      now: Instant
  ): Seq[CleanupCandidate] = {
    val root = Paths.get(config.receita.bronzeDir).resolve("estabelecimentos")
    val releases = children(root).flatMap(path => path.getFileName.toString match {
      case ReleaseDirectoryPattern(release) => Some(release -> path)
      case _ => None
    }).sortBy(_._1)
    val retained = releases.takeRight(policy.retainBronzeReleases).map(_._1).toSet
    val context = referenceContext(config)
    releases.map { case (release, path) =>
      val reasons = ArrayBuffer.empty[(String, String)]
      val measurement = measure(root, path)
      reasons ++= measurement._3
      val statuses = context.statuses.filter(status => status.snapshot == release && status.layer == "bronze")
      val observedAt = statuses.map(_.finishedAt).sorted.lastOption.orElse(lastModified(path))
      ageReasons(reasons, observedAt, policy.olderThanDays, now)
      if (retained(release)) add(reasons, "retention_count", "retained by newest-bronze release count")
      if (!statuses.exists(status => status.status == "success" || status.status == "success_with_warnings"))
        add(reasons, "missing_success_status", "missing successful bronze status")
      val releaseConfig = config.copy(receita = config.receita.copy(snapshot = release))
      if (!Files.isDirectory(ReleasePaths(releaseConfig).rawRoot, LinkOption.NOFOLLOW_LINKS))
        add(reasons, "missing_raw_rebuild_input", "protected raw rebuild input is missing")
      if (context.transactionBodies.exists(_.contains(path.toString)))
        add(reasons, "active_transaction", "referenced by active transaction journal")
      candidate("bronze", release, Some(release), path, observedAt, measurement, reasons, now)
    }
  }

  private def inspectWork(
      config: AtlasConfig,
      policy: StorageCleanupPolicy,
      now: Instant
  ): Seq[CleanupCandidate] = {
    val root = ReleasePaths(config).atlasRoot.resolve("work/receita/estabelecimentos")
    val context = referenceContext(config)
    children(root).map { path =>
      val reasons = ArrayBuffer.empty[(String, String)]
      val release = path.getFileName.toString match {
        case ReleaseDirectoryPattern(value) => Some(value)
        case _ => add(reasons, "unknown_layout", "unknown work directory layout"); None
      }
      val candidatePath = path.resolve("silver_candidate")
      if (!Files.isDirectory(candidatePath, LinkOption.NOFOLLOW_LINKS))
        add(reasons, "unknown_layout", "expected silver_candidate directory is missing")
      val measurement = measure(root, path)
      reasons ++= measurement._3
      val statuses = release.toSeq.flatMap(value =>
        context.statuses.filter(status => status.snapshot == value && status.layer == "silver")
      )
      val successful = statuses.filter(status =>
        status.status == "success" || status.status == "success_with_warnings"
      )
      val manifest =
        if (!Files.isRegularFile(path.resolve(WorkManifest.FileName), LinkOption.NOFOLLOW_LINKS)) None
        else
          try Some(WorkManifest.read(path))
          catch {
            case NonFatal(error) =>
              add(
                reasons,
                "malformed_manifest",
                s"malformed work manifest: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}"
              )
              None
          }
      manifest.foreach { value =>
        if (!release.contains(value.release))
          add(reasons, "identity_mismatch", "work manifest release does not match directory")
        if (
          Paths.get(value.outputPath).toAbsolutePath.normalize() != candidatePath.toAbsolutePath
            .normalize()
        )
          add(reasons, "identity_mismatch", "work manifest output does not match candidate path")
      }
      val observedAt = manifest
        .map(_.completedAt)
        .orElse(successful.map(_.finishedAt).sorted.lastOption)
        .orElse(lastModified(path))
      ageReasons(reasons, observedAt, policy.workOlderThanDays, now)
      if (successful.isEmpty) add(reasons, "missing_success_status", "missing successful silver status")
      if (context.transactionBodies.exists(body => body.contains(path.toString)))
        add(reasons, "active_transaction", "referenced by active transaction journal")
      candidate("work", release.getOrElse(path.getFileName.toString), release, path, observedAt, measurement, reasons, now)
    }
  }

  private final case class ReferenceContext(
      currentBundleId: Option[String],
      statuses: Seq[atlas.status.RunStatus],
      allReferences: Seq[String],
      activeReferences: Seq[String],
      transactionBodies: Seq[String],
      errors: Seq[(String, String)]
  )

  private def referenceContext(config: AtlasConfig): ReferenceContext = {
    val scan = RunStatusRegistry.scan(Paths.get(config.statusDir))
    val pointer = ReleasePaths(config).atlasRoot.resolve("bundles/current_bundle.json")
    val pointerResult =
      if (!Files.isRegularFile(pointer, LinkOption.NOFOLLOW_LINKS)) Right(None)
      else try Right(Some(ConfigFactory.parseFile(pointer.toFile).resolve().getString("bundle_id")))
      catch { case NonFatal(error) => Left(Option(error.getMessage).getOrElse(error.getClass.getSimpleName)) }
    val errors = ArrayBuffer.empty[(String, String)]
    scan.errors.foreach(error => add(errors, "malformed_status", s"malformed active status metadata: ${error.path}"))
    pointerResult.left.foreach(error => add(errors, "malformed_pointer", s"malformed current bundle pointer: $error"))
    val allReferences = scan.statuses.flatMap(status => status.inputPaths ++ status.outputPath.toSeq ++ status.qualityWarnings.map(_.reportPath))
    val references = scan.statuses
      .filterNot(status => status.status == "success" || status.status == "success_with_warnings" || status.status == "failed")
      .flatMap(status => status.inputPaths ++ status.outputPath.toSeq ++ status.qualityWarnings.map(_.reportPath))
    ReferenceContext(pointerResult.getOrElse(None), scan.statuses, allReferences, references, transactionFiles(config).map(Files.readString(_)), errors.toSeq)
  }

  private def quarantine(config: AtlasConfig, candidate: CleanupCandidate, now: Instant): Unit = {
    val timestamp = DateTimeFormatter.ISO_INSTANT.format(now).replace(":", "").replace(".", "")
    val operationPrefix = if (candidate.kind == "failed_bundle") "failed-company-bundle" else candidate.kind.replace('_', '-')
    val operation = s"$operationPrefix-${candidate.id}"
    val destination = ReleasePaths(config).atlasRoot.resolve("_trash").resolve(timestamp).resolve(operation)
    if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
      throw new IllegalStateException(s"Cleanup destination already exists: $destination")
    Files.createDirectories(destination.getParent)
    try Files.move(candidate.path, destination, StandardCopyOption.ATOMIC_MOVE)
    catch {
      case _: AtomicMoveNotSupportedException =>
        throw new IllegalStateException(s"Atomic quarantine move is not supported from ${candidate.path} to $destination")
    }
    if (candidate.kind == "failed_bundle") rewriteInternalStatuses(candidate.path, destination)
    TrashManifest.write(
      destination,
      TrashManifest(operationPrefix, now, Seq(candidate.path.toString), Seq.empty, Seq(candidate.id) ++ candidate.release.toSeq)
    )
  }

  private def rewriteInternalStatuses(original: Path, destination: Path): Unit = {
    val statusRoot = destination.resolve("data/_atlas/status")
    val scan = RunStatusRegistry.scan(statusRoot)
    if (scan.errors.nonEmpty)
      throw new IllegalStateException(s"Quarantined failed bundle has unreadable status metadata: ${scan.errors.map(_.path).mkString(", ")}")
    def moved(value: String): String = {
      val path = Paths.get(value).normalize()
      if (path.startsWith(original.normalize())) destination.resolve(original.normalize().relativize(path)).toString else value
    }
    scan.statuses.foreach { status =>
      RunStatusRegistry.write(statusRoot, status.copy(
        inputPaths = status.inputPaths.map(moved),
        outputPath = status.outputPath.map(moved),
        qualityWarnings = status.qualityWarnings.map(warning => warning.copy(reportPath = moved(warning.reportPath)))
      ))
    }
  }

  private def transactionFiles(config: AtlasConfig): Seq[Path] =
    children(ReleasePaths(config).atlasRoot.resolve("transactions")).filter(Files.isRegularFile(_, LinkOption.NOFOLLOW_LINKS))

  private def candidate(
      kind: String,
      id: String,
      release: Option[String],
      path: Path,
      observedAt: Option[Instant],
      measurement: (Long, Long, Seq[(String, String)]),
      reasons: Seq[(String, String)],
      now: Instant
  ): CleanupCandidate = CleanupCandidate(
    kind,
    id,
    release,
    path,
    observedAt,
    observedAt.map(Duration.between(_, now)),
    measurement._1,
    measurement._2,
    reasons.isEmpty,
    reasons.map(_._1).distinct,
    reasons.map(_._2).distinct
  )

  private def ageReasons(reasons: ArrayBuffer[(String, String)], observedAt: Option[Instant], days: Int, now: Instant): Unit = {
    if (observedAt.isEmpty) add(reasons, "unknown_age", "no trustworthy candidate timestamp")
    observedAt.foreach { timestamp =>
      val age = Duration.between(timestamp, now)
      if (age.isNegative) add(reasons, "future_timestamp", "candidate timestamp is in the future")
      else if (age.compareTo(Duration.ofDays(days.toLong)) < 0)
        add(reasons, "younger_than_retention", s"younger than $days days")
    }
  }

  private def measure(root: Path, path: Path): (Long, Long, Seq[(String, String)]) = {
    var bytes = 0L
    var files = 0L
    val reasons = ArrayBuffer.empty[(String, String)]
    val normalizedRoot = root.toAbsolutePath.normalize()
    if (!path.toAbsolutePath.normalize().startsWith(normalizedRoot)) add(reasons, "path_escape", "path escapes cleanup root")
    if (Files.isSymbolicLink(path)) add(reasons, "symbolic_link", s"symbolic link present: $path")
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.walkFileTree(path, new SimpleFileVisitor[Path] {
      override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
        if (Files.isSymbolicLink(dir)) { add(reasons, "symbolic_link", s"symbolic link present: $dir"); FileVisitResult.SKIP_SUBTREE }
        else FileVisitResult.CONTINUE
      override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
        if (Files.isSymbolicLink(file)) add(reasons, "symbolic_link", s"symbolic link present: $file")
        else if (attrs.isRegularFile) { bytes += attrs.size(); files += 1 }
        FileVisitResult.CONTINUE
      }
      override def visitFileFailed(file: Path, error: java.io.IOException): FileVisitResult = {
        add(reasons, "scan_error", s"unable to inspect $file: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}")
        FileVisitResult.CONTINUE
      }
    })
    (bytes, files, reasons.toSeq)
  }

  private def manifestField(path: Path, field: String): Option[String] =
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) None
    else try Some(ConfigFactory.parseFile(path.toFile).resolve().getString(field)) catch { case NonFatal(_) => None }

  private def optional(config: com.typesafe.config.Config, field: String): Option[String] =
    if (!config.hasPath(field) || config.getIsNull(field)) None else Some(config.getString(field))

  private def parseInstant(value: String): Option[Instant] = try Some(Instant.parse(value)) catch { case NonFatal(_) => None }
  private def lastModified(path: Path): Option[Instant] = try Some(Files.getLastModifiedTime(path).toInstant) catch { case NonFatal(_) => None }
  private def references(value: String, candidate: Path): Boolean = try Paths.get(value).toAbsolutePath.normalize().startsWith(candidate.toAbsolutePath.normalize()) catch { case NonFatal(_) => value.contains(candidate.getFileName.toString) }
  private def children(root: Path): Seq[Path] = if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) Seq.empty else { val stream = Files.list(root); try stream.iterator().asScala.toVector finally stream.close() }
  private def add(reasons: ArrayBuffer[(String, String)], code: String, message: String): Unit = reasons += code -> message
  private def quoted(value: String): String = "\"" + escape(value) + "\""
  private def decision(dryRun: Boolean, eligible: Boolean, completed: String, reasons: Seq[String]): String = if (eligible) { if (dryRun) "eligible" else completed } else "blocked: " + reasons.mkString("; ")

  private def candidateJson(kind: String, id: String, path: Path, release: Option[String], observedAt: Option[Instant], age: Option[Duration], bytes: Long, files: Option[Long], action: String, eligible: Boolean, codes: Seq[String], reasons: Seq[String]): String = {
    s"""{"kind":"${escape(kind)}","id":"${escape(id)}","path":"${escape(path.toString)}","release":${optionalJson(release)},"observed_at":${optionalJson(observedAt.map(_.toString))},"age_hours":${age.fold("null")(_.toHours.toString)},"bytes":$bytes,"files":${files.fold("null")(_.toString)},"proposed_action":"$action","eligible":$eligible,"blocking_reason_codes":[${codes.map(quoted).mkString(",")}],"blocking_reasons":[${reasons.map(quoted).mkString(",")}] }"""
  }
  private def optionalJson(value: Option[String]): String = value.fold("null")(quoted)
  private def escape(value: String): String = value.flatMap { case '"' => "\\\""; case '\\' => "\\\\"; case '\n' => "\\n"; case '\r' => "\\r"; case '\t' => "\\t"; case c => c.toString }
  private def human(bytes: Long): String = { val units = Seq("B", "KiB", "MiB", "GiB", "TiB"); var value = bytes.toDouble; var unit = 0; while (value >= 1024 && unit < units.size - 1) { value /= 1024; unit += 1 }; if (unit == 0) s"$bytes B" else f"$value%.1f ${units(unit)}" }
  private def table(headers: Seq[String], rows: Seq[Seq[String]]): String = { val widths = headers.indices.map(index => (headers +: rows).map(_(index).length).max); (headers +: rows).map(row => row.indices.map(index => row(index).padTo(widths(index), ' ').mkString).mkString("  ").replaceAll("\\s+$", "")).mkString("\n") }
}
