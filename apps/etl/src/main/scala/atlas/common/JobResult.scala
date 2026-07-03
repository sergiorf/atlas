package atlas.common

import java.time.Instant
final case class JobResult(datasetName: String, inputPath: String, outputPath: String, rowCount: Long, invalidCnpjLengthCount: Long, nullCnpjRootCount: Long, nullOpeningDateCount: Long, nullMainCnaeCount: Long, runTimestamp: Instant)
