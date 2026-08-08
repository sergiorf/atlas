package atlas.release

import atlas.config.AtlasConfig
import atlas.status.RunStatusRegistry
import java.nio.file.{Files, LinkOption, Path, Paths}
import java.time.Instant
import scala.collection.JavaConverters._

final case class TrashReconciliationCandidate(
    path: Path,
    replacements: Seq[Path],
    eligible: Boolean,
    blockingReasons: Seq[String]
)

final case class TrashReconciliationResult(
    dryRun: Boolean,
    candidates: Seq[TrashReconciliationCandidate],
    manifestsWritten: Int
)

object TrashReconciliationService {
  def inspect(config: AtlasConfig): TrashReconciliationResult =
    TrashReconciliationResult(dryRun = true, candidates(config), 0)

  def force(config: AtlasConfig): TrashReconciliationResult =
    PublicationLock.withCompanyBundleLock(config) {
      PublicationLock.withEstablishmentsLock(config) {
        val checked = candidates(config)
        checked.filter(_.eligible).foreach { candidate =>
          val timestamp = candidate.path.getParent.getFileName.toString
          TrashManifest.write(
            candidate.path,
            TrashManifest(
              "full-rebuild-backup",
              parseTimestamp(timestamp),
              children(candidate.path).map(_.toString),
              candidate.replacements.map(_.toString),
              children(candidate.path).map(_.getFileName.toString)
            )
          )
        }
        TrashReconciliationResult(dryRun = false, checked, checked.count(_.eligible))
      }
    }

  def render(result: TrashReconciliationResult): String = {
    val heading = if (result.dryRun) "Trash reconciliation dry run" else "Trash reconciliation complete"
    val rows = result.candidates.map { candidate =>
      val decision =
        if (candidate.eligible) { if (result.dryRun) "eligible" else "manifest written" }
        else "blocked: " + candidate.blockingReasons.mkString("; ")
      s"${candidate.path}  $decision"
    }
    s"$heading\n${if (rows.isEmpty) "No legacy full-rebuild trash found." else rows.mkString("\n")}\n" +
      s"Manifests written: ${result.manifestsWritten}. No data was deleted."
  }

  private def candidates(config: AtlasConfig): Seq[TrashReconciliationCandidate] = {
    val paths = ReleasePaths(config)
    val trash = paths.atlasRoot.resolve("_trash")
    val scan = RunStatusRegistry.scan(Paths.get(config.statusDir))
    children(trash).flatMap(timestamp => children(timestamp).filter(_.getFileName.toString == "full-establishments-rebuild")).map { candidate =>
      val reasons = scala.collection.mutable.ArrayBuffer.empty[String]
      val quarantinedAt = parseTimestamp(candidate.getParent.getFileName.toString)
      if (Files.exists(candidate.resolve(TrashManifest.FileName), LinkOption.NOFOLLOW_LINKS))
        reasons += "trash manifest already exists"
      if (scan.errors.nonEmpty) reasons += "active status metadata is malformed"
      val successfulLayers = scan.statuses
        .filter(status => status.status == "success" || status.status == "success_with_warnings")
        .filter(status => status.finishedAt.isAfter(quarantinedAt))
        .map(_.layer)
        .toSet
      Seq("bronze", "silver", "history").filterNot(successfulLayers).foreach(layer => reasons += s"missing canonical successful $layer status")
      val backupChildren = children(candidate)
      val replacements = backupChildren.flatMap(path => replacement(config, path.getFileName.toString))
      backupChildren.filter(path => replacement(config, path.getFileName.toString).isEmpty)
        .foreach(path => reasons += s"unknown legacy backup label: ${path.getFileName}")
      if (replacements.isEmpty) reasons += "legacy backup contains no recognized outputs"
      replacements.filterNot(Files.exists(_, LinkOption.NOFOLLOW_LINKS)).foreach(path => reasons += s"required active output is missing: $path")
      if (containsSymbolicLink(candidate)) reasons += "symbolic link present in legacy backup"
      val journal = paths.atlasRoot.resolve("transactions/establishments-rebuild.tsv")
      if (Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) reasons += "active establishment rebuild journal exists"
      TrashReconciliationCandidate(candidate, replacements, reasons.isEmpty, reasons.distinct.toSeq)
    }.sortBy(_.path.toString)
  }

  private def replacement(config: AtlasConfig, label: String): Option[Path] = {
    val paths = ReleasePaths(config)
    label match {
      case "bronze" => Some(Paths.get(config.receita.bronzeDir).resolve("estabelecimentos"))
      case "silver-current" => Some(paths.silverCurrent)
      case "history" => Some(paths.historyRoot)
      case "summaries" => Some(paths.summaryRoot)
      case "reports" => Some(paths.atlasRoot.resolve("reports/receita/estabelecimentos"))
      case "quality" => Some(paths.atlasRoot.resolve("quality/receita/establishments"))
      case "work" => Some(paths.atlasRoot.resolve("work/receita/estabelecimentos"))
      case "status-bronze" => Some(Paths.get(config.statusDir).resolve("receita/estabelecimentos"))
      case "status-silver" => Some(Paths.get(config.statusDir).resolve("receita/establishments"))
      case "status-history" => Some(Paths.get(config.statusDir).resolve("receita/estabelecimentos_history"))
      case _ => None
    }
  }

  private def containsSymbolicLink(root: Path): Boolean = {
    val stream = Files.walk(root)
    try stream.iterator().asScala.exists(Files.isSymbolicLink(_)) finally stream.close()
  }

  private def parseTimestamp(value: String): Instant =
    try {
      val pattern = "^([0-9]{4}-[0-9]{2}-[0-9]{2})T([0-9]{2})([0-9]{2})([0-9]{2})([0-9]*)Z$".r
      value match {
        case pattern(date, hour, minute, second, fraction) =>
          Instant.parse(s"${date}T$hour:$minute:$second${if (fraction.isEmpty) "" else "." + fraction}Z")
        case _ => Instant.EPOCH
      }
    } catch { case _: Throwable => Instant.EPOCH }

  private def children(root: Path): Seq[Path] =
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) Seq.empty
    else { val stream = Files.list(root); try stream.iterator().asScala.toVector finally stream.close() }
}
