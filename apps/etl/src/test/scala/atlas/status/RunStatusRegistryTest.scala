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
