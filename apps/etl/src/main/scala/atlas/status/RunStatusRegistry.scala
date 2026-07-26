package atlas.status

import com.typesafe.config.{Config, ConfigFactory}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter
import scala.collection.JavaConverters._
import scala.util.control.NonFatal

final case class StatusReadError(path: Path, message: String)
final case class StatusScan(statuses: Seq[RunStatus], errors: Seq[StatusReadError])

object RunStatusRegistry {
  def statusPath(root: Path, status: RunStatus): Path =
    root
      .resolve(status.source)
      .resolve(status.dataset)
      .resolve(status.snapshot)
      .resolve(s"${status.layer}.json")

  def write(root: Path, status: RunStatus): Path = {
    require(
      Set("success", "success_with_warnings", "failed").contains(status.status),
      "status must be success, success_with_warnings, or failed"
    )
    val target = statusPath(root, status)
    Option(target.getParent).foreach(Files.createDirectories(_))
    val temporary =
      target.resolveSibling(s".${target.getFileName}.${java.util.UUID.randomUUID()}.tmp")
    Files.write(temporary, json(status).getBytes(StandardCharsets.UTF_8))
    try
      Files.move(
        temporary,
        target,
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING
      )
    catch {
      case _: java.nio.file.AtomicMoveNotSupportedException =>
        Files.deleteIfExists(temporary)
        throw new IllegalStateException(s"Atomic status replacement is not supported at $target")
    }
    target
  }

  def scan(root: Path): StatusScan = {
    if (!Files.isDirectory(root)) return StatusScan(Seq.empty, Seq.empty)
    val stream = Files.walk(root)
    try {
      val files = stream
        .iterator()
        .asScala
        .filter(path => Files.isRegularFile(path) && path.toString.endsWith(".json"))
        .toSeq
        .sortBy(_.toString)
      val read = files.map { path =>
        try Left(readFile(path))
        catch {
          case NonFatal(error) =>
            Right(StatusReadError(path, Option(error.getMessage).getOrElse(error.getClass.getName)))
        }
      }
      StatusScan(
        read.collect { case Left(status) => status },
        read.collect { case Right(error) => error }
      )
    } finally stream.close()
  }

  def readFile(path: Path): RunStatus = fromConfig(ConfigFactory.parseFile(path.toFile).resolve())

  def jsonArray(statuses: Seq[RunStatus]): String =
    statuses.map(json(_).trim).mkString("[\n", ",\n", "\n]")

  private def fromConfig(c: Config): RunStatus = RunStatus(
    c.getString("source"),
    c.getString("dataset"),
    c.getString("snapshot"),
    c.getString("layer"),
    c.getString("status"),
    Instant.parse(c.getString("started_at")),
    Instant.parse(c.getString("finished_at")),
    c.getDouble("duration_seconds"),
    optional(c, "row_count")(_.getLong("row_count")),
    optional(c, "input_paths")(_.getStringList("input_paths").asScala.toSeq).getOrElse(Seq.empty),
    optional(c, "output_path")(_.getString("output_path")),
    optional(c, "partition_columns")(_.getStringList("partition_columns").asScala.toSeq)
      .getOrElse(Seq.empty),
    optional(c, "schema_version")(_.getString("schema_version")),
    optional(c, "application_name")(_.getString("application_name")),
    optional(c, "job_name")(_.getString("job_name")),
    optional(c, "error_type")(_.getString("error_type")),
    optional(c, "error_message")(_.getString("error_message")),
    optional(c, "input_row_count")(_.getLong("input_row_count")),
    optional(c, "output_row_count")(_.getLong("output_row_count")),
    optional(c, "quarantined_row_count")(_.getLong("quarantined_row_count")),
    optional(c, "quality_warnings")(
      _.getConfigList("quality_warnings").asScala
        .map { warning =>
          QualityWarning(
            warning.getString("type"),
            warning.getLong("row_count"),
            warning.getString("reason"),
            warning.getString("report_path")
          )
        }
        .toSeq
    ).getOrElse(Seq.empty),
    optional(c, "previous_row_count")(_.getLong("previous_row_count")),
    optional(c, "net_row_delta")(_.getLong("net_row_delta")),
    optional(c, "inserted_row_count")(_.getLong("inserted_row_count")),
    optional(c, "updated_row_count")(_.getLong("updated_row_count")),
    optional(c, "removed_row_count")(_.getLong("removed_row_count")),
    optional(c, "file_count")(_.getLong("file_count")),
    optional(c, "byte_count")(_.getLong("byte_count")),
    optional(c, "extracted_file_count")(_.getLong("extracted_file_count"))
  )

  private def optional[A](c: Config, path: String)(read: Config => A): Option[A] =
    if (c.hasPath(path) && !c.getIsNull(path)) Some(read(c)) else None

  private[status] def json(s: RunStatus): String = {
    val fields = Seq(
      Some("source" -> quoted(s.source)),
      Some("dataset" -> quoted(s.dataset)),
      Some("snapshot" -> quoted(s.snapshot)),
      Some("layer" -> quoted(s.layer)),
      Some("status" -> quoted(s.status)),
      Some("started_at" -> quoted(s.startedAt.toString)),
      Some("finished_at" -> quoted(s.finishedAt.toString)),
      Some(
        "duration_seconds" -> BigDecimal(
          s.durationSeconds
        ).bigDecimal.stripTrailingZeros.toPlainString
      ),
      s.rowCount.map(value => "row_count" -> value.toString),
      Some("input_paths" -> array(s.inputPaths)),
      s.outputPath.map(value => "output_path" -> quoted(value)),
      Some("partition_columns" -> array(s.partitionColumns)),
      s.schemaVersion.map(value => "schema_version" -> quoted(value)),
      s.applicationName.map(value => "application_name" -> quoted(value)),
      s.jobName.map(value => "job_name" -> quoted(value)),
      s.errorType.map(value => "error_type" -> quoted(value)),
      s.errorMessage.map(value => "error_message" -> quoted(value)),
      s.inputRowCount.map(value => "input_row_count" -> value.toString),
      s.outputRowCount.map(value => "output_row_count" -> value.toString),
      s.quarantinedRowCount.map(value => "quarantined_row_count" -> value.toString),
      if (s.qualityWarnings.nonEmpty)
        Some("quality_warnings" -> warningArray(s.qualityWarnings))
      else None,
      s.previousRowCount.map(value => "previous_row_count" -> value.toString),
      s.netRowDelta.map(value => "net_row_delta" -> value.toString),
      s.insertedRowCount.map(value => "inserted_row_count" -> value.toString),
      s.updatedRowCount.map(value => "updated_row_count" -> value.toString),
      s.removedRowCount.map(value => "removed_row_count" -> value.toString),
      s.fileCount.map(value => "file_count" -> value.toString),
      s.byteCount.map(value => "byte_count" -> value.toString),
      s.extractedFileCount.map(value => "extracted_file_count" -> value.toString)
    ).flatten
    fields.map { case (key, value) => s"  ${quoted(key)}: $value" }.mkString("{\n", ",\n", "\n}\n")
  }

  private def array(values: Seq[String]): String = values.map(quoted).mkString("[", ", ", "]")
  private def warningArray(values: Seq[QualityWarning]): String = values
    .map { warning =>
      Seq(
        "type" -> quoted(warning.warningType),
        "row_count" -> warning.rowCount.toString,
        "reason" -> quoted(warning.reason),
        "report_path" -> quoted(warning.reportPath)
      ).map { case (key, value) => s"${quoted(key)}: $value" }.mkString("{", ", ", "}")
    }
    .mkString("[", ", ", "]")
  private def quoted(value: String): String = {
    val escaped = value.flatMap {
      case '"'  => "\\\""; case '\\' => "\\\\"; case '\b' => "\\b"; case '\f' => "\\f"
      case '\n' => "\\n"; case '\r'  => "\\r"; case '\t'  => "\\t"
      case c if c < ' ' => f"\\u${c.toInt}%04x"; case c => c.toString
    }
    "\"" + escaped + "\""
  }
}

object StatusTable {
  private val CompactSnapshotHeaders =
    Seq("snapshot", "publication", "stages", "warnings", "last_recorded")
  private val CompactDatasetHeaders =
    Seq("dataset", "raw", "bronze", "silver", "history", "publication")
  private val PipelineHeaders = Seq(
    "source",
    "dataset",
    "snapshot",
    "stage",
    "status",
    "rows_out",
    "raw_files",
    "delta_changes",
    "quarantined",
    "warning",
    "finished_at",
    "output_path"
  )
  private val BundleHeaders =
    Seq("source", "package", "snapshot", "status", "finished_at", "output_path", "error")

  def render(statuses: Seq[RunStatus]): String = renderVerbose(statuses)

  def renderCompact(statuses: Seq[RunStatus], release: Option[String] = None): String = {
    val overview = StatusOverview.build(statuses, release)
    val snapshotRows = overview.snapshots.map { summary =>
      Seq(
        summary.snapshot,
        summary.publicationStatus.getOrElse("not recorded"),
        renderStageCounts(summary),
        s"${summary.warningTypeCount} ${if (summary.warningTypeCount == 1) "type" else "types"}",
        compactTimestamp(summary.lastRecorded)
      )
    }
    val datasetRows = overview.datasets.map { dataset =>
      Seq(
        dataset.dataset,
        dataset.raw.getOrElse("-"),
        dataset.bronze.getOrElse("-"),
        dataset.silver.getOrElse("-"),
        dataset.history.getOrElse("-"),
        dataset.publication.getOrElse("-")
      )
    }
    val selection = release.fold(s"NEWEST RECORDED SNAPSHOT: ${overview.selectedSnapshot}")(value =>
      s"SNAPSHOT DETAIL: $value"
    )
    Seq(
      "ATLAS STATUS",
      "SNAPSHOTS\n" + renderTable(CompactSnapshotHeaders, snapshotRows),
      selection + "\n" + renderTable(CompactDatasetHeaders, datasetRows),
      renderProblems(overview.problems)
    ).mkString("\n\n")
  }

  def renderVerbose(statuses: Seq[RunStatus]): String = {
    val (bundles, pipeline) = statuses.partition(_.layer == "bundle")
    val sections = Seq(
      renderPipeline(pipeline),
      renderBundles(bundles)
    ).filter(_.nonEmpty)
    sections.mkString("\n\n")
  }

  private def renderProblems(problems: Seq[StatusProblem]): String = {
    if (problems.isEmpty) return "PROBLEMS\nnone"
    val rendered = problems
      .groupBy(problem => problem.dataset -> problem.stage)
      .toSeq
      .sortBy { case ((dataset, stage), _) => (dataset, stage) }
      .map { case ((dataset, stage), grouped) =>
        val details = grouped
          .map {
            case StatusProblem(_, _, _, Some(warning), _) =>
              s"  warning: ${warning.warningType} (${warning.rowCount} rows)"
            case StatusProblem(_, _, "warning", None, _) =>
              "  warning: recorded without details"
            case StatusProblem(_, _, _, _, error) =>
              s"  failed: ${conciseError(error)}"
          }
          .mkString("\n")
        s"$dataset/$stage\n$details"
      }
      .mkString("\n\n")
    "PROBLEMS\n" + rendered
  }

  private def renderStageCounts(summary: SnapshotSummary): String = {
    val counts = Seq(
      if (summary.successfulStages > 0) Some(s"${summary.successfulStages} ok") else None,
      if (summary.warningStages > 0) Some(s"${summary.warningStages} warn") else None,
      if (summary.failedStages > 0) Some(s"${summary.failedStages} failed") else None,
      if (summary.otherStages > 0) Some(s"${summary.otherStages} other") else None
    ).flatten
    if (counts.isEmpty) "none recorded" else counts.mkString(" / ")
  }

  private val CompactTimestamp =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm'Z'").withZone(ZoneOffset.UTC)

  private def compactTimestamp(instant: Instant): String = CompactTimestamp.format(instant)

  private def renderPipeline(statuses: Seq[RunStatus]): String = {
    if (statuses.isEmpty) return ""
    val rows = statuses
      .sortBy(s => (s.source, displayDataset(s.dataset), s.snapshot, stageOrder(s.layer), s.layer))
      .map { s =>
        Seq(
          s.source,
          displayDataset(s.dataset),
          s.snapshot,
          s.layer,
          s.status,
          s.outputRowCount.orElse(s.rowCount).fold("-")(_.toString),
          renderRawFiles(s),
          renderChanges(s),
          s.quarantinedRowCount.fold("-")(_.toString),
          if (s.qualityWarnings.isEmpty) "-"
          else s.qualityWarnings.map(_.warningType).mkString(","),
          s.finishedAt.toString,
          s.outputPath.getOrElse("-")
        )
      }
    "DATA PIPELINE\n" + renderTable(PipelineHeaders, rows)
  }

  private def renderBundles(statuses: Seq[RunStatus]): String = {
    if (statuses.isEmpty) return ""
    val rows = statuses.sortBy(s => (s.source, s.dataset, s.snapshot)).map { s =>
      Seq(
        s.source,
        displayDataset(s.dataset),
        s.snapshot,
        s.status,
        s.finishedAt.toString,
        s.outputPath.getOrElse("-"),
        conciseError(s.errorMessage)
      )
    }
    "ATOMIC PUBLICATION\n" + renderTable(BundleHeaders, rows)
  }

  private def renderTable(headers: Seq[String], rows: Seq[Seq[String]]): String = {
    val widths = headers.indices.map(i => (headers +: rows).map(_(i).length).max)
    (headers +: rows)
      .map(row =>
        row.indices
          .map(i => row(i).padTo(widths(i), ' ').mkString)
          .mkString("  ")
          .replaceAll("\\s+$", "")
      )
      .mkString("\n")
  }

  private def displayDataset(dataset: String): String = dataset match {
    case "estabelecimentos" | "establishments" | "estabelecimentos_history" => "establishments"
    case value                                                              => value
  }

  private def stageOrder(stage: String): Int = stage match {
    case "raw"     => 0
    case "bronze"  => 1
    case "silver"  => 2
    case "history" => 3
    case _         => 4
  }

  private def conciseError(error: Option[String]): String = error
    .map(_.replaceAll("\\s+", " ").trim)
    .filter(_.nonEmpty)
    .map(value => if (value.length <= 120) value else value.take(117) + "...")
    .getOrElse("-")

  private def renderRawFiles(s: RunStatus): String = s.fileCount match {
    case None => "-"
    case Some(files) =>
      val bytes = s.byteCount.fold("?")(formatBytes)
      val extracted = s.extractedFileCount.fold("")(value => s"/$value extracted")
      s"$files/$bytes$extracted"
  }

  private def formatBytes(bytes: Long): String = {
    val units = Seq("B", "KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) { value /= 1024; unit += 1 }
    if (unit == 0) s"${bytes}B" else f"$value%.1f${units(unit)}"
  }

  private def renderChanges(s: RunStatus): String = {
    val changes = Seq(
      s.insertedRowCount.map(value => s"+$value"),
      s.updatedRowCount.map(value => s"~$value"),
      s.removedRowCount.map(value => s"-$value")
    ).flatten
    val delta = s.netRowDelta.map(value => f"$value%+d")
    (delta.toSeq ++ changes).mkString("/") match {
      case ""    => "-"
      case value => value
    }
  }
}
