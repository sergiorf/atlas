package atlas.release

import atlas.config.AtlasConfig
import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._

final case class PathInventory(label: String, path: Path, exists: Boolean, fileCount: Long, sizeBytes: Long, protectedPath: Boolean)
final case class ReleaseInventory(release: ReleaseId, entries: Seq[PathInventory])

object ReleaseInventoryService {
  def inspect(config: AtlasConfig, release: ReleaseId): ReleaseInventory = {
    val paths = ReleasePaths(config.copy(receita = config.receita.copy(snapshot = release.value)))
    val entries = Seq(
      inventory("raw", paths.rawRoot, protectedPath = true),
      inventory("bronze", paths.bronzeRelease, protectedPath = false),
      inventory("silver_candidate", paths.silverCandidate, protectedPath = false),
      inventory("bronze_reports", paths.bronzeReports, protectedPath = false),
      inventory("silver_reports", paths.silverReports, protectedPath = false),
      inventory("history", paths.historyRelease, protectedPath = false)
    )
    ReleaseInventory(release, entries)
  }

  def list(config: AtlasConfig): Seq[ReleaseInventory] = {
    val statusRoot = java.nio.file.Paths.get(config.statusDir).resolve("receita")
    val releases =
      if (!Files.isDirectory(statusRoot)) Seq(config.receita.snapshot)
      else {
        val stream = Files.walk(statusRoot)
        try stream.iterator().asScala
          .filter(Files.isDirectory(_))
          .flatMap(path => ReleaseId.parse(path.getFileName.toString).fold(_ => None, value => Some(value.value)))
          .toSeq
          .distinct
          .sorted
        finally stream.close()
      }
    releases.map(value => inspect(config, ReleaseId.unsafe(value)))
  }

  def inventory(label: String, path: Path, protectedPath: Boolean): PathInventory = {
    if (!Files.exists(path)) PathInventory(label, path, exists = false, 0L, 0L, protectedPath)
    else {
      val stream = Files.walk(path)
      try {
        val files = stream.iterator().asScala.filter(Files.isRegularFile(_)).toSeq
        PathInventory(label, path, exists = true, files.size.toLong, files.map(p => Files.size(p)).sum, protectedPath)
      } finally stream.close()
    }
  }

  def renderList(inventories: Seq[ReleaseInventory]): String =
    if (inventories.isEmpty) "No Atlas releases found."
    else {
      val rows = inventories.map { inventory =>
        val layers = inventory.entries.filter(e => e.exists && !e.protectedPath).map(_.label).mkString(",")
        val raw = inventory.entries.find(_.label == "raw").exists(_.exists)
        Seq(inventory.release.value, if (layers.isEmpty) "-" else layers, if (raw) "present protected" else "missing")
      }
      render(Seq("release", "derived", "raw"), rows)
    }

  def renderInspect(inventory: ReleaseInventory): String = {
    val rows = inventory.entries.map { e =>
      Seq(e.label, e.path.toString, if (e.exists) "yes" else "no", e.fileCount.toString, e.sizeBytes.toString, if (e.protectedPath) "protected" else "derived")
    }
    s"Atlas release ${inventory.release.value}\n" + render(Seq("layer", "path", "exists", "files", "bytes", "policy"), rows)
  }

  private def render(headers: Seq[String], rows: Seq[Seq[String]]): String = {
    val widths = headers.indices.map(i => (headers +: rows).map(_(i).length).max)
    (headers +: rows).map(row => row.indices.map(i => row(i).padTo(widths(i), ' ').mkString).mkString("  ").replaceAll("\\s+$", "")).mkString("\n")
  }
}
