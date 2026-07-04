package atlas.status

import com.typesafe.config.{Config, ConfigFactory}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Instant
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
    Files.write(target, json(status).getBytes(StandardCharsets.UTF_8))
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
      _.getConfigList("quality_warnings").asScala.map { warning =>
        QualityWarning(
          warning.getString("type"),
          warning.getLong("row_count"),
          warning.getString("reason"),
          warning.getString("report_path")
        )
      }.toSeq
    ).getOrElse(Seq.empty)
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
      else None
    ).flatten
    fields.map { case (key, value) => s"  ${quoted(key)}: $value" }.mkString("{\n", ",\n", "\n}\n")
  }

  private def array(values: Seq[String]): String = values.map(quoted).mkString("[", ", ", "]")
  private def warningArray(values: Seq[QualityWarning]): String = values.map { warning =>
    Seq(
      "type" -> quoted(warning.warningType),
      "row_count" -> warning.rowCount.toString,
      "reason" -> quoted(warning.reason),
      "report_path" -> quoted(warning.reportPath)
    ).map { case (key, value) => s"${quoted(key)}: $value" }.mkString("{", ", ", "}")
  }.mkString("[", ", ", "]")
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
  private val Headers = Seq(
    "source",
    "dataset",
    "snapshot",
    "layer",
    "status",
    "rows_out",
    "quarantined",
    "warning",
    "finished_at",
    "output_path"
  )
  def render(statuses: Seq[RunStatus]): String = {
    val rows = statuses.sortBy(s => (s.source, s.dataset, s.snapshot, s.layer)).map { s =>
      Seq(
        s.source,
        s.dataset,
        s.snapshot,
        s.layer,
        s.status,
        s.outputRowCount.orElse(s.rowCount).fold("-")(_.toString),
        s.quarantinedRowCount.fold("0")(_.toString),
        if (s.qualityWarnings.isEmpty) "-" else s.qualityWarnings.map(_.warningType).mkString(","),
        s.finishedAt.toString,
        s.outputPath.getOrElse("-")
      )
    }
    val widths = Headers.indices.map(i => (Headers +: rows).map(_(i).length).max)
    (Headers +: rows)
      .map(row =>
        row.indices
          .map(i => row(i).padTo(widths(i), ' ').mkString)
          .mkString("  ")
          .replaceAll("\\s+$", "")
      )
      .mkString("\n")
  }
}
