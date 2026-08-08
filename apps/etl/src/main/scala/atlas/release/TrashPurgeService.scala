package atlas.release

import atlas.config.AtlasConfig
import atlas.status.RunStatusRegistry
import java.nio.file.{Files, LinkOption, Path, Paths}
import java.time.{Duration, Instant}
import scala.collection.JavaConverters._
import scala.util.control.NonFatal

final case class TrashGeneration(
    path: Path,
    timestamp: Option[Instant],
    operationType: String,
    age: Option[Duration],
    bytes: Long,
    eligible: Boolean,
    blockingReasons: Seq[String]
)

final case class TrashPurgeResult(
    dryRun: Boolean,
    olderThanDays: Int,
    generations: Seq[TrashGeneration],
    deletedBytes: Long,
    skippedBytes: Long
)

object TrashPurgeService {
  private val TimestampPattern =
    "^([0-9]{4}-[0-9]{2}-[0-9]{2})T([0-9]{2})([0-9]{2})([0-9]{2})([0-9]*)Z$".r
  private val KnownOperations =
    Set(
      "release-drop",
      "stale-derived",
      "failed-rebuild",
      "full-rebuild-backup",
      "failed-company-bundle",
      "failed-bundle",
      "inactive-bundle",
      "bronze",
      "work"
    )

  def inspect(
      config: AtlasConfig,
      olderThanDays: Int = 7,
      now: Instant = Instant.now()
  ): TrashPurgeResult = {
    require(olderThanDays >= 0, "--older-than-days must be non-negative")
    val paths = ReleasePaths(config)
    val trashRoot = paths.atlasRoot.resolve("_trash")
    val generations = operationDirectories(trashRoot).map { case (timestampDir, operationDir) =>
      inspectGeneration(config, trashRoot, timestampDir, operationDir, olderThanDays, now)
    }
    TrashPurgeResult(dryRun = true, olderThanDays, generations, 0L, generations.map(_.bytes).sum)
  }

  def force(
      config: AtlasConfig,
      olderThanDays: Int = 7,
      now: Instant = Instant.now()
  ): TrashPurgeResult =
    PublicationLock.withEstablishmentsLock(config) {
      forceUnlocked(config, olderThanDays, now)
    }

  private[release] def forceUnlocked(
      config: AtlasConfig,
      olderThanDays: Int,
      now: Instant
  ): TrashPurgeResult = {
    val checked = inspect(config, olderThanDays, now)
    checked.generations.filter(_.eligible).foreach(generation => deleteTree(generation.path))
    pruneEmptyTimestampDirectories(ReleasePaths(config).atlasRoot.resolve("_trash"))
    val deleted = checked.generations.filter(_.eligible).map(_.bytes).sum
    val skipped = checked.generations.filterNot(_.eligible).map(_.bytes).sum
    checked.copy(dryRun = false, deletedBytes = deleted, skippedBytes = skipped)
  }

  def render(result: TrashPurgeResult): String = {
    val heading = if (result.dryRun) "Trash purge dry run" else "Trash purge complete"
    val rows = result.generations.map { generation =>
      Seq(
        generation.timestamp.fold("unknown")(_.toString),
        generation.operationType,
        generation.age.fold("unknown")(age => age.toHours.toString + "h"),
        generation.bytes.toString,
        if (generation.eligible) (if (result.dryRun) "eligible" else "deleted") else "skipped",
        if (generation.blockingReasons.isEmpty) "-" else generation.blockingReasons.mkString("; ")
      )
    }
    val headers = Seq("timestamp", "operation", "age", "bytes", "decision", "reason")
    val table = if (rows.isEmpty) "No trash generations found." else renderTable(headers, rows)
    s"$heading (older than ${result.olderThanDays} days)\n$table\n" +
      s"Deleted bytes: ${result.deletedBytes}\nSkipped bytes: ${result.skippedBytes}\nRaw data was not considered or touched."
  }

  private def inspectGeneration(
      config: AtlasConfig,
      trashRoot: Path,
      timestampDir: Path,
      operationDir: Path,
      olderThanDays: Int,
      now: Instant
  ): TrashGeneration = {
    val timestamp = parseTimestamp(timestampDir.getFileName.toString)
    val age = timestamp.map(Duration.between(_, now))
    val manifestResult = readOrInfer(operationDir, timestamp)
    val operationType = manifestResult.fold(_ => "unknown", _.operationType)
    val reasons = scala.collection.mutable.ArrayBuffer.empty[String]
    manifestResult.left.foreach(reasons += _)
    if (!KnownOperations(operationType)) reasons += s"unknown operation type '$operationType'"
    if (timestamp.isEmpty) reasons += "unknown timestamp directory"
    if (age.exists(_.isNegative)) reasons += "timestamp is in the future"
    if (age.exists(_.compareTo(Duration.ofDays(olderThanDays.toLong)) < 0))
      reasons += s"younger than $olderThanDays days"
    validateTree(trashRoot, operationDir).foreach(reasons += _)
    journalReference(config, operationDir).foreach(reasons += _)
    if (operationType == "full-rebuild-backup") {
      manifestResult.foreach(manifest =>
        validateFullRebuild(config, manifest).foreach(reasons += _)
      )
    }
    val inspectedBytes = treeBytes(operationDir)
    if (inspectedBytes.isEmpty) reasons += "unable to inspect generation bytes"
    val bytes = inspectedBytes.getOrElse(0L)
    TrashGeneration(
      operationDir,
      timestamp,
      operationType,
      age,
      bytes,
      reasons.isEmpty,
      reasons.distinct.toSeq
    )
  }

  private def operationDirectories(trashRoot: Path): Seq[(Path, Path)] = {
    if (!Files.isDirectory(trashRoot, LinkOption.NOFOLLOW_LINKS)) return Seq.empty
    list(trashRoot)
      .flatMap { timestampDir =>
        if (!Files.isDirectory(timestampDir, LinkOption.NOFOLLOW_LINKS))
          Seq(timestampDir -> timestampDir)
        else list(timestampDir).map(timestampDir -> _)
      }
      .toVector
      .sortBy(_._2.toString)
  }

  private def readOrInfer(root: Path, timestamp: Option[Instant]): Either[String, TrashManifest] = {
    val manifest = root.resolve(TrashManifest.FileName)
    if (Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
      try Right(TrashManifest.read(root))
      catch {
        case NonFatal(error) =>
          Left(
            s"malformed trash manifest: ${Option(error.getMessage).getOrElse(error.getClass.getName)}"
          )
      }
    } else {
      val name = root.getFileName.toString
      val operation =
        if (name == "stale-derived") Some("stale-derived")
        else if (name == "failed-establishments-rebuild") Some("failed-rebuild")
        else if (name == "full-establishments-rebuild") Some("full-rebuild-backup")
        else if (name.startsWith("release=")) Some("release-drop")
        else None
      operation
        .map(value =>
          TrashManifest(
            value,
            timestamp.getOrElse(Instant.EPOCH),
            Seq.empty,
            Seq.empty,
            list(root).map(_.getFileName.toString)
          )
        )
        .toRight("unknown trash directory structure")
    }
  }

  private def validateTree(trashRoot: Path, root: Path): Seq[String] = {
    val normalizedTrash = trashRoot.toAbsolutePath.normalize()
    val normalizedRoot = root.toAbsolutePath.normalize()
    val reasons = scala.collection.mutable.ArrayBuffer.empty[String]
    if (!normalizedRoot.startsWith(normalizedTrash) || normalizedRoot == normalizedTrash)
      reasons += "path escapes configured trash root"
    if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
      val stream = Files.walk(root)
      try
        stream.iterator().asScala.foreach { path =>
          if (Files.isSymbolicLink(path)) reasons += s"symbolic link present: $path"
          if (!path.toAbsolutePath.normalize().startsWith(normalizedTrash))
            reasons += s"path escapes configured trash root: $path"
        }
      finally stream.close()
    }
    reasons.toSeq
  }

  private def journalReference(config: AtlasConfig, generation: Path): Option[String] = {
    val journal = ReleasePaths(config).atlasRoot.resolve("transactions/establishments-rebuild.tsv")
    if (!Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS)) None
    else {
      val target = generation.toAbsolutePath.normalize().toString
      val references = Files.readAllLines(journal).asScala.exists { line =>
        line
          .split("\t", -1)
          .exists(field => {
            try Paths.get(field).toAbsolutePath.normalize().startsWith(Paths.get(target))
            catch { case NonFatal(_) => false }
          })
      }
      if (references) Some("referenced by active rebuild transaction journal") else None
    }
  }

  private def validateFullRebuild(config: AtlasConfig, manifest: TrashManifest): Seq[String] = {
    val reasons = scala.collection.mutable.ArrayBuffer.empty[String]
    val replacements = manifest.replacementPaths.map(Paths.get(_))
    if (replacements.isEmpty)
      reasons += "legacy full-rebuild backup has no replacement expectations"
    replacements
      .filterNot(path => Files.exists(path, LinkOption.NOFOLLOW_LINKS))
      .foreach(path => reasons += s"required active output is missing: $path")
    val scan = RunStatusRegistry.scan(Paths.get(config.statusDir))
    scan.errors.foreach(error => reasons += s"malformed active status metadata: ${error.path}")
    val statuses = scan.statuses
    val forbidden = statuses
      .flatMap(status =>
        status.inputPaths ++ status.outputPath.toSeq ++ status.qualityWarnings.map(_.reportPath)
      )
      .filter(path => path.contains("_trash") || path.contains("rebuild-staging"))
    if (forbidden.nonEmpty) reasons += "active status metadata references trash or rebuild staging"
    val successfulLayers = statuses
      .filter(status => status.status == "success" || status.status == "success_with_warnings")
      .map(_.layer)
      .toSet
    Seq("bronze", "silver", "history")
      .filterNot(successfulLayers)
      .foreach(layer => reasons += s"missing canonical successful $layer status")
    reasons.toSeq
  }

  private def parseTimestamp(value: String): Option[Instant] = value match {
    case TimestampPattern(date, hour, minute, second, fraction) =>
      val decimal = if (fraction.isEmpty) "" else "." + fraction
      try Some(Instant.parse(s"${date}T$hour:$minute:$second${decimal}Z"))
      catch { case NonFatal(_) => None }
    case _ => None
  }

  private def treeBytes(root: Path): Option[Long] = try {
    val stream = Files.walk(root)
    try
      Some(
        stream
          .iterator()
          .asScala
          .filter(path => Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
          .map(Files.size(_))
          .sum
      )
    finally stream.close()
  } catch { case NonFatal(_) => None }

  private def deleteTree(root: Path): Unit = {
    if (Files.isSymbolicLink(root))
      throw new IllegalStateException(s"Refusing to delete symbolic link $root")
    val stream = Files.walk(root)
    try stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete(_))
    finally stream.close()
  }

  private def pruneEmptyTimestampDirectories(trashRoot: Path): Unit = list(trashRoot).foreach {
    path =>
      if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && list(path).isEmpty)
        Files.delete(path)
  }

  private def list(root: Path): Seq[Path] = {
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) Seq.empty
    else {
      val stream = Files.list(root);
      try stream.iterator().asScala.toVector
      finally stream.close()
    }
  }

  private def renderTable(headers: Seq[String], rows: Seq[Seq[String]]): String = {
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
