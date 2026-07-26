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

final case class FailedBundleCleanupCandidate(
    bundleId: String,
    release: Option[String],
    path: Path,
    observedAt: Option[Instant],
    age: Option[Duration],
    bytes: Long,
    files: Long,
    eligible: Boolean,
    blockingReasons: Seq[String]
)

final case class StorageCleanupResult(
    dryRun: Boolean,
    olderThanDays: Int,
    trash: TrashPurgeResult,
    failedBundles: Seq[FailedBundleCleanupCandidate],
    quarantinedBytes: Long
)

object StorageCleanupService {
  val ContractVersion = 1
  private val BundlePattern =
    """^([0-9]{4}-[0-9]{2})-([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$""".r

  def inspect(
      config: AtlasConfig,
      olderThanDays: Int = 7,
      now: Instant = Instant.now()
  ): StorageCleanupResult = {
    require(olderThanDays >= 0, "--older-than-days must be non-negative")
    val failed = inspectFailedBundles(config, olderThanDays, now)
    StorageCleanupResult(
      dryRun = true,
      olderThanDays,
      TrashPurgeService.inspect(config, olderThanDays, now),
      failed,
      quarantinedBytes = 0L
    )
  }

  def force(
      config: AtlasConfig,
      olderThanDays: Int = 7,
      now: Instant = Instant.now()
  ): StorageCleanupResult =
    PublicationLock.withCompanyBundleLock(config) {
      // Purge first: anything quarantined below is deliberately retained until a later invocation.
      val purged = TrashPurgeService.force(config, olderThanDays, now)
      val checked = inspectFailedBundles(config, olderThanDays, now)
      val eligible = checked.filter(_.eligible)
      eligible.foreach(candidate => quarantine(config, candidate, now))
      StorageCleanupResult(
        dryRun = false,
        olderThanDays,
        purged,
        checked,
        quarantinedBytes = eligible.map(_.bytes).sum
      )
    }

  def render(result: StorageCleanupResult): String = {
    val trashRows = result.trash.generations.map { generation =>
      Seq(
        "trash",
        generation.path.toString,
        generation.age.fold("unknown")(age => age.toHours.toString + "h"),
        human(generation.bytes),
        "delete",
        if (generation.eligible) {
          if (result.dryRun) "eligible" else "deleted"
        } else "blocked: " + generation.blockingReasons.mkString("; ")
      )
    }
    val failedRows = result.failedBundles.map { candidate =>
      Seq(
        "failed-bundle",
        candidate.path.toString,
        candidate.age.fold("unknown")(age => age.toHours.toString + "h"),
        human(candidate.bytes),
        "quarantine",
        if (candidate.eligible) {
          if (result.dryRun) "eligible" else "quarantined"
        } else "blocked: " + candidate.blockingReasons.mkString("; ")
      )
    }
    val rows = (trashRows ++ failedRows).sortBy(row => (row.head, row(1)))
    val heading =
      if (result.dryRun) "ATLAS STORAGE CLEANUP — DRY RUN" else "ATLAS STORAGE CLEANUP — COMPLETE"
    val tableText =
      if (rows.isEmpty) "No cleanup candidates found."
      else table(Seq("location", "candidate", "age", "size", "action", "decision"), rows)
    val eligibleDelete = result.trash.generations.filter(_.eligible).map(_.bytes).sum
    val eligibleQuarantine = result.failedBundles.filter(_.eligible).map(_.bytes).sum
    val blocked = result.trash.generations.filterNot(_.eligible).map(_.bytes).sum +
      result.failedBundles.filterNot(_.eligible).map(_.bytes).sum
    s"$heading\nRecovery window: ${result.olderThanDays} days\n\n$tableText\n\n" +
      s"Eligible for permanent deletion: ${human(eligibleDelete)}\n" +
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
        generation.path,
        None,
        generation.timestamp,
        generation.age,
        generation.bytes,
        None,
        "delete",
        generation.eligible,
        generation.blockingReasons
      )
    }
    val failed = result.failedBundles.map { candidate =>
      candidateJson(
        "failed_bundle",
        candidate.path,
        candidate.release,
        candidate.observedAt,
        candidate.age,
        candidate.bytes,
        Some(candidate.files),
        "quarantine",
        candidate.eligible,
        candidate.blockingReasons
      )
    }
    val eligibleDelete = result.trash.generations.filter(_.eligible).map(_.bytes).sum
    val eligibleQuarantine = result.failedBundles.filter(_.eligible).map(_.bytes).sum
    s"""{"contract_version":$ContractVersion,"dry_run":${result.dryRun},"older_than_days":${result.olderThanDays},"candidates":[${(trash ++ failed)
        .mkString(
          ","
        )}],"eligible_delete_bytes":$eligibleDelete,"eligible_quarantine_bytes":$eligibleQuarantine}"""
  }

  private def inspectFailedBundles(
      config: AtlasConfig,
      olderThanDays: Int,
      now: Instant
  ): Seq[FailedBundleCleanupCandidate] = {
    val root = ReleasePaths(config).atlasRoot.resolve("bundles/failed")
    val activeScan = RunStatusRegistry.scan(Paths.get(config.statusDir))
    val activeErrors =
      activeScan.errors.map(error => s"malformed active status metadata: ${error.path}")
    val activeReferences = activeScan.statuses.flatMap(status =>
      status.inputPaths ++ status.outputPath.toSeq ++ status.qualityWarnings.map(_.reportPath)
    )
    val currentPointer = ReleasePaths(config).atlasRoot.resolve("bundles/current_bundle.json")
    val currentReference: Either[String, Option[String]] =
      if (!Files.isRegularFile(currentPointer, LinkOption.NOFOLLOW_LINKS)) Right(None)
      else
        try
          Right(
            Some(
              ConfigFactory.parseFile(currentPointer.toFile).resolve().getString("bundle_id")
            )
          )
        catch {
          case NonFatal(error) =>
            Left(
              s"malformed current bundle pointer: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}"
            )
        }
    val transactionBodies =
      transactionFiles(config).map(path => Files.readString(path)).mkString("\n")

    children(root)
      .map { path =>
        val reasons = ArrayBuffer.empty[String]
        val bundleId = path.getFileName.toString
        val release = bundleId match {
          case BundlePattern(value, _) => Some(value)
          case _ =>
            reasons += "unrecognized failed bundle identifier"
            None
        }
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
          reasons += "candidate is not a directory"
        reasons ++= activeErrors
        currentReference.left.foreach(reasons += _)
        val measurement = measure(root, path)
        reasons ++= measurement._3
        val statusRoot = path.resolve("data/_atlas/status")
        val internal = RunStatusRegistry.scan(statusRoot)
        internal.errors
          .foreach(error => reasons += s"malformed failed candidate status: ${error.path}")
        val manifestTime = manifestCreatedAt(path)
        manifestTime._2.foreach(reasons += _)
        val observedAt =
          (manifestTime._1.toSeq ++ internal.statuses.map(_.finishedAt)).sorted.lastOption
        if (observedAt.isEmpty) reasons += "no trustworthy failure timestamp"
        val age = observedAt.map(Duration.between(_, now))
        if (age.exists(_.isNegative)) reasons += "failure timestamp is in the future"
        if (age.exists(_.compareTo(Duration.ofDays(olderThanDays.toLong)) < 0))
          reasons += s"younger than $olderThanDays days"
        if (currentReference.exists(_.contains(bundleId)))
          reasons += "referenced by current bundle pointer"
        if (activeReferences.exists(reference => references(reference, path)))
          reasons += "referenced by active status metadata"
        if (transactionBodies.contains(bundleId) || transactionBodies.contains(path.toString))
          reasons += "referenced by active transaction journal"
        FailedBundleCleanupCandidate(
          bundleId,
          release,
          path,
          observedAt,
          age,
          measurement._1,
          measurement._2,
          reasons.isEmpty,
          reasons.distinct.toSeq
        )
      }
      .sortBy(_.path.toString)
  }

  private def quarantine(
      config: AtlasConfig,
      candidate: FailedBundleCleanupCandidate,
      now: Instant
  ): Unit = {
    val timestamp = DateTimeFormatter.ISO_INSTANT.format(now).replace(":", "").replace(".", "")
    val operation = s"failed-company-bundle-${candidate.bundleId}"
    val destination =
      ReleasePaths(config).atlasRoot.resolve("_trash").resolve(timestamp).resolve(operation)
    if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
      throw new IllegalStateException(s"Cleanup destination already exists: $destination")
    Files.createDirectories(destination.getParent)
    try Files.move(candidate.path, destination, StandardCopyOption.ATOMIC_MOVE)
    catch {
      case _: AtomicMoveNotSupportedException =>
        throw new IllegalStateException(
          s"Atomic quarantine move is not supported from ${candidate.path} to $destination"
        )
    }
    rewriteInternalStatuses(candidate.path, destination)
    TrashManifest.write(
      destination,
      TrashManifest(
        "failed-company-bundle",
        now,
        Seq(candidate.path.toString),
        Seq.empty,
        Seq(candidate.bundleId) ++ candidate.release.toSeq
      )
    )
  }

  private def rewriteInternalStatuses(original: Path, destination: Path): Unit = {
    val statusRoot = destination.resolve("data/_atlas/status")
    val scan = RunStatusRegistry.scan(statusRoot)
    if (scan.errors.nonEmpty)
      throw new IllegalStateException(
        s"Quarantined failed bundle has unreadable status metadata: ${scan.errors.map(_.path).mkString(", ")}"
      )
    def moved(value: String): String = {
      val path = Paths.get(value).normalize()
      if (path.startsWith(original.normalize()))
        destination.resolve(original.normalize().relativize(path)).toString
      else value
    }
    scan.statuses.foreach { status =>
      RunStatusRegistry.write(
        statusRoot,
        status.copy(
          inputPaths = status.inputPaths.map(moved),
          outputPath = status.outputPath.map(moved),
          qualityWarnings = status.qualityWarnings.map(warning =>
            warning.copy(reportPath = moved(warning.reportPath))
          )
        )
      )
    }
  }

  private def measure(root: Path, path: Path): (Long, Long, Seq[String]) = {
    var bytes = 0L
    var files = 0L
    val reasons = ArrayBuffer.empty[String]
    val normalizedRoot = root.toAbsolutePath.normalize()
    if (!path.toAbsolutePath.normalize().startsWith(normalizedRoot))
      reasons += "path escapes failed bundle root"
    if (Files.isSymbolicLink(path)) reasons += s"symbolic link present: $path"
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS))
      Files.walkFileTree(
        path,
        new SimpleFileVisitor[Path] {
          override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
            if (Files.isSymbolicLink(dir)) {
              reasons += s"symbolic link present: $dir"
              FileVisitResult.SKIP_SUBTREE
            } else FileVisitResult.CONTINUE
          override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
            if (Files.isSymbolicLink(file)) reasons += s"symbolic link present: $file"
            else if (attrs.isRegularFile) {
              bytes += attrs.size()
              files += 1
            }
            FileVisitResult.CONTINUE
          }
          override def visitFileFailed(file: Path, error: java.io.IOException): FileVisitResult = {
            reasons += s"unable to inspect $file: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}"
            FileVisitResult.CONTINUE
          }
        }
      )
    (bytes, files, reasons.toSeq)
  }

  private def manifestCreatedAt(path: Path): (Option[Instant], Option[String]) = {
    val manifest = path.resolve("bundle-manifest.json")
    if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) None -> None
    else
      try
        Some(
          Instant.parse(ConfigFactory.parseFile(manifest.toFile).resolve().getString("created_at"))
        ) -> None
      catch {
        case NonFatal(error) =>
          None -> Some(
            s"malformed failed bundle manifest: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}"
          )
      }
  }

  private def transactionFiles(config: AtlasConfig): Seq[Path] = {
    val root = ReleasePaths(config).atlasRoot.resolve("transactions")
    children(root).filter(Files.isRegularFile(_, LinkOption.NOFOLLOW_LINKS))
  }

  private def references(value: String, candidate: Path): Boolean = {
    val normalizedCandidate = candidate.toAbsolutePath.normalize()
    try Paths.get(value).toAbsolutePath.normalize().startsWith(normalizedCandidate)
    catch { case NonFatal(_) => value.contains(candidate.getFileName.toString) }
  }

  private def children(root: Path): Seq[Path] =
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) Seq.empty
    else {
      val stream = Files.list(root)
      try stream.iterator().asScala.toVector
      finally stream.close()
    }

  private def human(bytes: Long): String = {
    val units = Seq("B", "KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) {
      value /= 1024
      unit += 1
    }
    if (unit == 0) s"$bytes B" else f"$value%.1f ${units(unit)}"
  }

  private def candidateJson(
      kind: String,
      path: Path,
      release: Option[String],
      observedAt: Option[Instant],
      age: Option[Duration],
      bytes: Long,
      files: Option[Long],
      action: String,
      eligible: Boolean,
      reasons: Seq[String]
  ): String = {
    val reasonJson = reasons.map(value => s""""${escape(value)}"""").mkString(",")
    s"""{"kind":"$kind","path":"${escape(path.toString)}","release":${optional(
        release
      )},"observed_at":${optional(observedAt.map(_.toString))},"age_hours":${age.fold("null")(
        _.toHours.toString
      )},"bytes":$bytes,"files":${files.fold("null")(
        _.toString
      )},"proposed_action":"$action","eligible":$eligible,"blocking_reasons":[$reasonJson]}"""
  }

  private def optional(value: Option[String]): String =
    value.fold("null")(item => s""""${escape(item)}"""")

  private def escape(value: String): String =
    value.flatMap {
      case '"'  => "\\\""
      case '\\' => "\\\\"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c    => c.toString
    }

  private def table(headers: Seq[String], rows: Seq[Seq[String]]): String = {
    val widths = headers.indices.map(index => (headers +: rows).map(_(index).length).max)
    (headers +: rows)
      .map(row =>
        row.indices
          .map(index => row(index).padTo(widths(index), ' ').mkString)
          .mkString("  ")
          .replaceAll("\\s+$", "")
      )
      .mkString("\n")
  }
}
