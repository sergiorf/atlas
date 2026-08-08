package atlas.release

import atlas.config.AtlasConfig
import java.io.IOException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, Files, LinkOption, Path, Paths, SimpleFileVisitor}
import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

final case class StorageLocation(
    category: String,
    path: Path,
    bytes: Long,
    files: Long,
    policy: String,
    action: String,
    errors: Seq[String]
)

final case class StorageFilesystem(path: Path, totalBytes: Long, usedBytes: Long, freeBytes: Long)

final case class StorageUsage(
    contractVersion: Int,
    filesystems: Seq[StorageFilesystem],
    locations: Seq[StorageLocation],
    release: Option[String],
    category: Option[String]
)

object StorageUsageService {
  val ContractVersion = 1
  val Categories: Set[String] = Set(
    "raw",
    "bronze",
    "silver",
    "bundles",
    "staging",
    "work",
    "trash",
    "quality",
    "reports",
    "metadata",
    "spark",
    "unclassified"
  )

  private final case class Target(category: String, path: Path, policy: String, action: String)

  def inspect(
      config: AtlasConfig,
      category: Option[String] = None,
      release: Option[String] = None
  ): StorageUsage = {
    category.foreach(value =>
      require(
        Categories(value),
        s"Unknown storage category '$value'. Expected one of: ${Categories.toSeq.sorted.mkString(", ")}"
      )
    )
    val paths = ReleasePaths(config)
    val targets =
      partitionedTargets(paths.dataRoot, paths.atlasRoot, Paths.get(config.spark.localDir))
        .filter(target => category.forall(_ == target.category))
    val locations = targets
      .map(target => scan(target, release))
      .filter(location => location.bytes > 0 || location.errors.nonEmpty)
    val filesystems = filesystemRoots(Seq(paths.dataRoot, Paths.get(config.spark.localDir)))
      .flatMap(filesystem)
      .groupBy(fs => (fs.totalBytes, fs.usedBytes, fs.freeBytes))
      .values
      .map(_.head)
      .toSeq
    StorageUsage(
      ContractVersion,
      filesystems,
      locations.sortBy(location => (-location.bytes, location.path.toString)),
      release,
      category
    )
  }

  def render(result: StorageUsage, top: Int = 20): String = {
    val filesystemRows = result.filesystems.map(fs =>
      Seq(fs.path.toString, human(fs.totalBytes), human(fs.usedBytes), human(fs.freeBytes))
    )
    val locationRows = result.locations
      .take(top)
      .map(location =>
        Seq(
          location.category,
          human(location.bytes),
          location.files.toString,
          location.policy,
          location.path.toString,
          if (location.errors.isEmpty) location.action
          else s"${location.action}; ${location.errors.size} scan error(s)"
        )
      )
    val filters =
      Seq(result.category.map("category=" + _), result.release.map("release=" + _)).flatten
    val suffix = if (filters.isEmpty) "" else s" (${filters.mkString(", ")})"
    val total = result.locations.map(_.bytes).sum
    s"ATLAS STORAGE USAGE$suffix\n\nFILESYSTEMS\n" +
      table(Seq("path", "total", "used", "free"), filesystemRows) +
      "\n\nLOCATIONS\n" +
      (if (locationRows.isEmpty) "No matching Atlas storage found."
       else table(Seq("category", "size", "files", "policy", "path", "next_step"), locationRows)) +
      s"\n\nTOTAL MATCHING: ${human(total)} (${result.locations.map(_.files).sum} files)" +
      (if (result.locations.size > top)
         s"\nShowing $top of ${result.locations.size} locations; use --top N to change the limit."
       else "")
  }

  def json(result: StorageUsage): String = {
    val filesystems = result.filesystems
      .map { fs =>
        s"""{"path":"${escape(
            fs.path.toString
          )}","total_bytes":${fs.totalBytes},"used_bytes":${fs.usedBytes},"free_bytes":${fs.freeBytes}}"""
      }
      .mkString(",")
    val locations = result.locations
      .map { location =>
        val errors = location.errors.map(error => s""""${escape(error)}"""").mkString(",")
        s"""{"category":"${escape(location.category)}","path":"${escape(
            location.path.toString
          )}","bytes":${location.bytes},"files":${location.files},"policy":"${escape(
            location.policy
          )}","action":"${escape(location.action)}","errors":[$errors]}"""
      }
      .mkString(",")
    s"""{"contract_version":${result.contractVersion},"release":${optional(
        result.release
      )},"category":${optional(
        result.category
      )},"filesystems":[$filesystems],"locations":[$locations]}"""
  }

  private def partitionedTargets(dataRoot: Path, atlasRoot: Path, sparkRoot: Path): Seq[Target] = {
    val data = children(dataRoot).filterNot(path => same(path, atlasRoot)).map { path =>
      path.getFileName.toString match {
        case "raw"    => Target("raw", path, "protected_raw", "inspect only")
        case "bronze" => Target("bronze", path, "derived_rebuildable", "storage cleanup")
        case "silver" =>
          Target("silver", path, "protected_active_or_history", "inspect release ownership")
        case _ => Target("unclassified", path, "unknown", "inspect manually")
      }
    }
    val atlas = children(atlasRoot).flatMap { path =>
      path.getFileName.toString match {
        case "bundles" =>
          children(path).map { bundlePath =>
            bundlePath.getFileName.toString match {
              case "generations" =>
                Target(
                  "bundles",
                  bundlePath,
                  "protected_active_or_retained",
                  "storage cleanup"
                )
              case "staging" | "failed" =>
                Target(
                  "staging",
                  bundlePath,
                  "recoverable_or_abandoned",
                  "inspect bundle transaction state"
                )
              case name if name.startsWith("current_bundle.json") =>
                Target("metadata", bundlePath, "protected_metadata", "do not delete manually")
              case _ => Target("unclassified", bundlePath, "unknown", "inspect manually")
            }
          }
        case "rebuild-staging" =>
          Seq(Target("staging", path, "recoverable_or_abandoned", "inspect transaction state"))
        case "work" =>
          Seq(Target("work", path, "derived_rebuildable", "storage cleanup"))
        case "_trash" => Seq(Target("trash", path, "quarantined", "releases purge-trash"))
        case "quality" =>
          Seq(Target("quality", path, "derived_rebuildable", "inspect release ownership"))
        case "reports" =>
          Seq(Target("reports", path, "derived_rebuildable", "releases drop-derived"))
        case "status" | "transactions" | "locks" =>
          Seq(Target("metadata", path, "protected_metadata", "do not delete manually"))
        case _ => Seq(Target("unclassified", path, "unknown", "inspect manually"))
      }
    }
    val existing = data ++ atlas
    val spark =
      if (
        existing.exists(target => nested(sparkRoot, target.path) || nested(target.path, sparkRoot))
      ) Seq.empty
      else Seq(Target("spark", sparkRoot, "temporary", "remove only when no Spark job is running"))
    deduplicate(existing ++ spark)
  }

  private def scan(target: Target, release: Option[String]): StorageLocation = {
    if (!Files.exists(target.path, LinkOption.NOFOLLOW_LINKS))
      return StorageLocation(
        target.category,
        target.path,
        0L,
        0L,
        target.policy,
        target.action,
        Seq.empty
      )
    var bytes = 0L
    var files = 0L
    val errors = ArrayBuffer.empty[String]
    Files.walkFileTree(
      target.path,
      new SimpleFileVisitor[Path] {
        override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
          if (Files.isSymbolicLink(dir)) {
            errors += s"symbolic link not followed: $dir"
            FileVisitResult.SKIP_SUBTREE
          } else FileVisitResult.CONTINUE

        override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
          if (matchesRelease(file, release) && attrs.isRegularFile) {
            bytes += attrs.size()
            files += 1
          }
          FileVisitResult.CONTINUE
        }

        override def visitFileFailed(file: Path, error: IOException): FileVisitResult = {
          errors += s"$file: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}"
          FileVisitResult.CONTINUE
        }
      }
    )
    StorageLocation(
      target.category,
      target.path,
      bytes,
      files,
      target.policy,
      target.action,
      errors.toSeq
    )
  }

  private def matchesRelease(path: Path, release: Option[String]): Boolean = release.forall {
    value =>
      path.iterator().asScala.exists { component =>
        val name = component.toString
        name == value || name == s"release=$value" || name == s"to_release=$value" || name
          .startsWith(value + "-")
      }
  }

  private def children(root: Path): Seq[Path] =
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) Seq.empty
    else {
      val stream = Files.list(root)
      try stream.iterator().asScala.toVector
      finally stream.close()
    }

  private def filesystem(path: Path): Option[StorageFilesystem] =
    nearestExisting(path).map { existing =>
      val store = Files.getFileStore(existing)
      val total = store.getTotalSpace
      val free = store.getUsableSpace
      StorageFilesystem(
        existing.toAbsolutePath.normalize(),
        total,
        total - store.getUnallocatedSpace,
        free
      )
    }

  private def filesystemRoots(paths: Seq[Path]): Seq[Path] =
    paths.map(_.toAbsolutePath.normalize()).distinct

  private def nearestExisting(path: Path): Option[Path] =
    Iterator
      .iterate(Option(path.toAbsolutePath.normalize()))(_.flatMap(value => Option(value.getParent)))
      .takeWhile(_.nonEmpty)
      .flatten
      .find(Files.exists(_))

  private def deduplicate(targets: Seq[Target]): Seq[Target] =
    targets.groupBy(target => target.path.toAbsolutePath.normalize()).values.map(_.head).toSeq

  private def nested(path: Path, root: Path): Boolean =
    path.toAbsolutePath.normalize().startsWith(root.toAbsolutePath.normalize())

  private def same(left: Path, right: Path): Boolean =
    left.toAbsolutePath.normalize() == right.toAbsolutePath.normalize()

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
