package atlas.status

import java.time.Instant

final case class QualityWarning(
    warningType: String,
    rowCount: Long,
    reason: String,
    reportPath: String
)

final case class RunStatus(
    source: String,
    dataset: String,
    snapshot: String,
    layer: String,
    status: String,
    startedAt: Instant,
    finishedAt: Instant,
    durationSeconds: Double,
    rowCount: Option[Long],
    inputPaths: Seq[String],
    outputPath: Option[String],
    partitionColumns: Seq[String],
    schemaVersion: Option[String],
    applicationName: Option[String],
    jobName: Option[String],
    errorType: Option[String] = None,
    errorMessage: Option[String] = None,
    inputRowCount: Option[Long] = None,
    outputRowCount: Option[Long] = None,
    quarantinedRowCount: Option[Long] = None,
    qualityWarnings: Seq[QualityWarning] = Seq.empty,
    previousRowCount: Option[Long] = None,
    netRowDelta: Option[Long] = None,
    insertedRowCount: Option[Long] = None,
    updatedRowCount: Option[Long] = None,
    removedRowCount: Option[Long] = None,
    fileCount: Option[Long] = None,
    byteCount: Option[Long] = None,
    extractedFileCount: Option[Long] = None
)
