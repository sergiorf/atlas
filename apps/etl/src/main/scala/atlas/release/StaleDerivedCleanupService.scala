package atlas.release

import atlas.config.AtlasConfig
import java.nio.file.{Files, Path, StandardCopyOption}
import java.time.Instant
import java.time.format.DateTimeFormatter

final case class StaleDerivedCleanupResult(dryRun: Boolean, entries: Seq[PathInventory], trashRoot: Option[Path])

object StaleDerivedCleanupService {
  def plan(config: AtlasConfig): StaleDerivedCleanupResult = {
    val paths = ReleasePaths(config)
    val entries = stalePaths(paths).map { case (label, path) =>
      inspectStale(label, path, paths)
    }
    StaleDerivedCleanupResult(dryRun = true, entries, None)
  }

  def force(config: AtlasConfig): StaleDerivedCleanupResult = {
    val planned = plan(config)
    val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(":", "").replace(".", "")
    val paths = ReleasePaths(config)
    val trash = paths.atlasRoot.resolve("_trash").resolve(timestamp).resolve("stale-derived")
    planned.entries.filter(_.exists).foreach { entry =>
      if (entry.protectedPath) throw new IllegalArgumentException(s"Refusing to move protected path ${entry.path}")
      val destination = trash.resolve(entry.label)
      Files.createDirectories(destination.getParent)
      Files.move(entry.path, destination, StandardCopyOption.REPLACE_EXISTING)
    }
    planned.copy(dryRun = false, trashRoot = Some(trash))
  }

  def render(result: StaleDerivedCleanupResult): String = {
    val action = if (result.dryRun) "Dry run" else "Quarantined"
    val rows = result.entries.map { e =>
      Seq(e.label, e.path.toString, if (e.exists) "yes" else "no", e.fileCount.toString, e.sizeBytes.toString, "stale-derived")
    }
    val table = render(Seq("layer", "path", "exists", "files", "bytes", "policy"), rows)
    val trash = result.trashRoot.fold("")(path => s"\nTrash: $path")
    s"$action for stale derived layers\n$table\nRaw files, current silver, and history are protected and not affected.$trash"
  }

  private def stalePaths(paths: ReleasePaths): Seq[(String, Path)] = {
    val silverRoot = java.nio.file.Paths.get(paths.config.receita.silverDir)
    Seq(
      "legacy_silver_establishments" -> silverRoot.resolve("establishments"),
      "legacy_silver_quality_report_md" -> silverRoot.resolve("establishments_quality_report.md"),
      "legacy_silver_quality_report_json" -> silverRoot.resolve("establishments_quality_report.json")
    )
  }

  private def inspectStale(label: String, path: Path, paths: ReleasePaths): PathInventory = {
    val normalized = path.toAbsolutePath.normalize()
    val silverRoot = java.nio.file.Paths.get(paths.config.receita.silverDir).toAbsolutePath.normalize()
    if (!normalized.startsWith(silverRoot))
      throw new IllegalArgumentException(s"Refusing stale path outside configured silver directory: $path")
    if (normalized == paths.silverCurrent.toAbsolutePath.normalize())
      throw new IllegalArgumentException(s"Refusing current silver path: $path")
    if (normalized.startsWith(paths.historyRoot.toAbsolutePath.normalize()))
      throw new IllegalArgumentException(s"Refusing history path: $path")
    ReleaseInventoryService.inventory(label, path, protectedPath = false)
  }

  private def render(headers: Seq[String], rows: Seq[Seq[String]]): String = {
    val widths = headers.indices.map(i => (headers +: rows).map(_(i).length).max)
    (headers +: rows).map(row => row.indices.map(i => row(i).padTo(widths(i), ' ').mkString).mkString("  ").replaceAll("\\s+$", "")).mkString("\n")
  }
}
