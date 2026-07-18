package atlas.release

import atlas.config.AtlasConfig
import java.nio.file.{Files, Path, StandardCopyOption}
import java.time.Instant
import java.time.format.DateTimeFormatter

final case class DropResult(release: ReleaseId, layer: ReleaseLayer, dryRun: Boolean, entries: Seq[PathInventory], trashRoot: Option[Path])

object ReleaseDropService {
  def plan(config: AtlasConfig, release: ReleaseId, layer: ReleaseLayer): DropResult = {
    val paths = ReleasePaths(config.copy(receita = config.receita.copy(snapshot = release.value)))
    val entries = paths.derivedPaths(layer).map { case (label, path) =>
      inspectDerived(label, path, paths)
    }
    DropResult(release, layer, dryRun = true, entries, None)
  }

  def force(config: AtlasConfig, release: ReleaseId, layer: ReleaseLayer): DropResult = {
    val planned = plan(config, release, layer)
    val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(":", "").replace(".", "")
    val paths = ReleasePaths(config.copy(receita = config.receita.copy(snapshot = release.value)))
    val trash = paths.trashRoot(timestamp)
    planned.entries.filter(_.exists).foreach { entry =>
      if (entry.protectedPath) throw new IllegalArgumentException(s"Refusing to move protected path ${entry.path}")
      Files.createDirectories(trash.resolve(entry.label).getParent)
      Files.move(entry.path, trash.resolve(entry.label), StandardCopyOption.REPLACE_EXISTING)
    }
    TrashManifest.write(trash, TrashManifest(
      "release-drop",
      Instant.now(),
      planned.entries.filter(_.exists).map(_.path.toString),
      Seq.empty,
      planned.entries.filter(_.exists).map(_.label)
    ))
    planned.copy(dryRun = false, trashRoot = Some(trash))
  }

  def render(result: DropResult): String = {
    val action = if (result.dryRun) "Dry run" else "Quarantined"
    val rows = result.entries.map { e =>
      Seq(e.label, e.path.toString, if (e.exists) "yes" else "no", e.fileCount.toString, e.sizeBytes.toString)
    }
    val table = ReleaseInventoryService.renderInspect(ReleaseInventory(result.release, result.entries)).split("\n").drop(1).mkString("\n")
    val trash = result.trashRoot.fold("")(path => s"\nTrash: $path")
    s"$action for release ${result.release.value}, layer ${result.layer.name}\n$table\nRaw files are protected and not affected.$trash"
  }

  private def inspectDerived(label: String, path: Path, paths: ReleasePaths): PathInventory = {
    val normalized = path.toAbsolutePath.normalize()
    val dataRoot = paths.dataRoot.toAbsolutePath.normalize()
    val atlasRoot = paths.atlasRoot.toAbsolutePath.normalize()
    if (!normalized.startsWith(dataRoot) && !normalized.startsWith(atlasRoot))
      throw new IllegalArgumentException(s"Refusing path outside Atlas data directories: $path")
    if (normalized.startsWith(paths.rawRoot.toAbsolutePath.normalize()))
      throw new IllegalArgumentException(s"Refusing raw path: $path")
    ReleaseInventoryService.inventory(label, path, protectedPath = false)
  }
}
