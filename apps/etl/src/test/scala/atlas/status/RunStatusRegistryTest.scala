package atlas.status

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import org.scalatest.funsuite.AnyFunSuite

class RunStatusRegistryTest extends AnyFunSuite {
  private val started = Instant.parse("2026-07-04T10:00:00Z")
  private val finished = Instant.parse("2026-07-04T10:00:02.500Z")

  test("writes success JSON at the contract path and creates parents") {
    val root = Files.createTempDirectory("atlas-status")
    val status = sample("success", Some(42L))

    val path = RunStatusRegistry.write(root, status)
    val content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

    assert(path === root.resolve("receita/estabelecimentos/2026-06/bronze.json"))
    assert(content.contains("\"status\": \"success\""))
    assert(content.contains("\"row_count\": 42"))
    assert(content.contains("\"partition_columns\": [\"state\"]"))
    assert(RunStatusRegistry.readFile(path) === status)
  }

  test("writes failure details with escaped JSON strings") {
    val root = Files.createTempDirectory("atlas-status-failed")
    val status = sample("failed", None).copy(
      errorType = Some("java.lang.IllegalStateException"),
      errorMessage = Some("bad \"record\"\nnext line")
    )

    val path = RunStatusRegistry.write(root, status)
    val content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

    assert(content.contains("\"error_type\": \"java.lang.IllegalStateException\""))
    assert(content.contains("bad \\\"record\\\"\\nnext line"))
    assert(!content.contains("\"row_count\""))
    assert(RunStatusRegistry.readFile(path) === status)
  }

  test("scan keeps valid statuses and reports malformed files") {
    val root = Files.createTempDirectory("atlas-status-scan")
    RunStatusRegistry.write(root, sample("success", Some(42L)))
    val malformed = root.resolve("receita/other/2026-06/bronze.json")
    Files.createDirectories(malformed.getParent)
    Files.write(malformed, "not json".getBytes(StandardCharsets.UTF_8))

    val scan = RunStatusRegistry.scan(root)

    assert(scan.statuses.map(_.dataset) === Seq("estabelecimentos"))
    assert(scan.errors.map(_.path) === Seq(malformed))
    assert(StatusTable.render(scan.statuses).contains("rows_out"))
    assert(StatusTable.render(scan.statuses).contains("42"))
  }

  test("round trips warning status fields and renders quarantined rows distinctly") {
    val root = Files.createTempDirectory("atlas-status-warning")
    val status = sample("success_with_warnings", Some(9L)).copy(
      layer = "silver",
      dataset = "establishments",
      inputRowCount = Some(10L),
      outputRowCount = Some(9L),
      quarantinedRowCount = Some(1L),
      qualityWarnings = Seq(
        QualityWarning("malformed_rows", 1L, "Invalid registration_status_code", "quality/malformed_rows")
      )
    )

    val path = RunStatusRegistry.write(root, status)
    assert(RunStatusRegistry.readFile(path) === status)
    val table = StatusTable.render(Seq(status))
    assert(table.contains("success_with_warnings"))
    assert(table.contains("quarantined"))
    assert(table.contains("malformed_rows"))
  }

  test("round trips additive release metrics and keeps older JSON readable") {
    val root = Files.createTempDirectory("atlas-status-release-metrics")
    val status = sample("success", Some(3L)).copy(
      previousRowCount = Some(10L), netRowDelta = Some(1L),
      insertedRowCount = Some(2L), updatedRowCount = Some(3L), removedRowCount = Some(1L)
    )
    val path = RunStatusRegistry.write(root, status)
    assert(RunStatusRegistry.readFile(path) === status)
    assert(StatusTable.render(Seq(status)).contains("+1/+2/~3/-1"))

    val older = sample("success", Some(3L))
    val olderPath = RunStatusRegistry.write(root, older.copy(snapshot = "2026-05"))
    assert(RunStatusRegistry.readFile(olderPath).previousRowCount.isEmpty)
  }

  test("round trips raw download metrics and renders them without treating files as rows") {
    val root = Files.createTempDirectory("atlas-status-raw")
    val status = sample("success", None).copy(
      layer = "raw",
      outputPath = Some("data/raw/receita/2026-06/estabelecimentos"),
      fileCount = Some(10L),
      byteCount = Some(1572864L),
      extractedFileCount = Some(10L)
    )

    val path = RunStatusRegistry.write(root, status)
    assert(RunStatusRegistry.readFile(path) === status)
    assert(StatusTable.render(Seq(status)).contains("10/1.5MiB/10 extracted"))
  }

  test("human status separates pipeline and publication and normalizes establishment labels") {
    val pipeline = sample("success", Some(0L)).copy(
      dataset = "estabelecimentos_history",
      layer = "history",
      outputRowCount = Some(0L),
      quarantinedRowCount = None
    )
    val bundle = sample("failed", None).copy(
      dataset = "company-data",
      layer = "bundle",
      outputPath = None,
      errorMessage = Some("quality gate failed")
    )

    val table = StatusTable.render(Seq(bundle, pipeline))

    assert(table.contains("DATA PIPELINE"))
    assert(table.contains("ATOMIC PUBLICATION"))
    assert(table.contains("source"))
    assert(table.contains("package"))
    assert(table.contains("receita"))
    assert(table.contains("establishments"))
    assert(!table.contains("estabelecimentos_history"))
    assert(table.contains("quality gate failed"))
    val pipelineLine = table.split("\n").find(line =>
      line.contains("establishments") && line.contains("history")
    ).get
    assert(pipelineLine.contains("  0"))
    assert(pipelineLine.contains("  -"))
    val bundleSection = table.substring(table.indexOf("ATOMIC PUBLICATION"))
    assert(!bundleSection.contains("quarantined"))
    assert(!bundleSection.contains("rows_out"))
  }

  test("human status orders known pipeline stages and shortens publication errors") {
    val stages = Seq("history", "silver", "raw", "bronze").map(layer =>
      sample("success", Some(1L)).copy(layer = layer)
    )
    val longError = ("publication failed " * 20).trim
    val bundle = sample("failed", None).copy(
      dataset = "company-data", layer = "bundle", errorMessage = Some(longError)
    )

    val table = StatusTable.render(stages :+ bundle)
    val pipeline = table.substring(0, table.indexOf("ATOMIC PUBLICATION"))

    val renderedStages = pipeline.split("\n").filter(_.startsWith("receita")).map(_.split("\\s+")(3)).toSeq
    assert(renderedStages === Seq("raw", "bronze", "silver", "history"))
    assert(!table.contains(longError))
    assert(table.contains("..."))
  }

  private def sample(status: String, rowCount: Option[Long]): RunStatus = RunStatus(
    source = "receita",
    dataset = "estabelecimentos",
    snapshot = "2026-06",
    layer = "bronze",
    status = status,
    startedAt = started,
    finishedAt = finished,
    durationSeconds = 2.5,
    rowCount = rowCount,
    inputPaths = Seq("data/raw/receita/2026-06/estabelecimentos/extracted/*"),
    outputPath = Some("data/bronze/receita/estabelecimentos"),
    partitionColumns = Seq("state"),
    schemaVersion = Some("1"),
    applicationName = Some("atlas-etl"),
    jobName = Some("ingest-receita-estabelecimentos")
  )
}
