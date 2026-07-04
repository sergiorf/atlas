# Troubleshooting

## No input files

Check the configured raw directory, extraction completion, and `ATLAS_RECEITA_RAW_DIR`. The job expects extracted Estabelecimentos files beneath that directory.

## Interrupted download

Keep `.part` files and rerun the downloader so it can resume. Do not delete or rename raw artifacts merely to make a run succeed.

## Out of memory or excessive disk use

Confirm free disk space, reduce unrelated workloads, choose suitable sbt `-J-Xmx` settings, and inspect Spark temporary directories. Do not solve memory pressure by collecting the dataset locally. Review shuffle partitions and storage changes as contract-affecting work when they alter operational guarantees.

## Unexpected null dates

Only eight-digit `yyyyMMdd` values parse. Blanks and malformed values become null and contribute to applicable quality metrics.

## Unexpected output replacement

The committed write mode is `overwrite`. Verify the bronze destination and environment overrides before rerunning. Raw inputs are never an acceptable output destination.

## Quality metrics are nonzero

Bronze reports rather than rejects these conditions. Silver rejects invalid or duplicate CNPJ identifiers and reports its other completeness metrics. Confirm source completeness, inspect representative affected records, and compare with prior snapshots. Do not silently add filtering or correction semantics outside the dataset and quality contracts.
