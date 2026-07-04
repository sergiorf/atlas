package atlas.status

import java.time.Instant

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
    errorMessage: Option[String] = None
)
