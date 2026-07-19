package atlas.receita

import java.time.Instant

final case class SilverQualityReport(
    datasetName: String,
    inputPath: String,
    outputPath: String,
    rowCount: Long,
    validRowCount: Long,
    malformedRowCount: Long,
    invalidCnpjCount: Long,
    alphanumericCnpjCount: Long,
    duplicateKeyCount: Long,
    duplicateRowCount: Long,
    nullOpeningDateCount: Long,
    invalidMainCnaeCount: Long,
    malformedSecondaryCnaeTokenCount: Long,
    invalidStateCount: Long,
    nullMunicipalityCodeCount: Long,
    accepted: Boolean,
    runTimestamp: Instant
)
