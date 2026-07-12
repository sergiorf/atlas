# Troubleshooting

## No input files

Check the configured raw directory, extraction completion, and `ATLAS_RECEITA_RAW_DIR`. The job expects extracted Estabelecimentos files beneath that directory.

## Interrupted download

Keep `.part` files and rerun the downloader so it can resume. Do not delete or rename raw artifacts merely to make a run succeed.

## Out of memory or excessive disk use

Confirm free disk space, reduce unrelated workloads, choose suitable sbt `-J-Xmx` settings, and inspect Spark temporary directories. Do not solve memory pressure by collecting the dataset locally. Review shuffle partitions and storage changes as contract-affecting work when they alter operational guarantees.

The refresh pipeline persists its large candidate and change-event intermediates with disk-only storage. A stack trace through `InMemoryRelation`, `DefaultCachedBatchSerializer`, and `MemoryStore.putIterator` indicates an older build still using memory-backed history caches; rebuild before retrying. Ingest and refresh may share one Spark session, but the ingest intermediate is unpersisted before normalization begins.

## No space left on device in Spark DiskStore

If the stack trace contains `java.io.IOException: No space left on device` and `org.apache.spark.storage.DiskStore.put`, check the filesystem that contains the configured Spark local directory, not only the WSL root filesystem. WSL2 may mount `/tmp` as a tmpfs with roughly 8 GB, which can fill during Receita shuffle or persistence even while the root filesystem has ample capacity.

Atlas defaults `atlas.spark.local-dir` to `spark-tmp` relative to `apps/etl`. Remove stale files there only after confirming that no Spark job is running, or select another directory on a large filesystem:

```bash
mkdir -p /home/<user>/spark-tmp
ATLAS_SPARK_LOCAL_DIR=/home/<user>/spark-tmp sbt "runMain atlas.Main ingest-receita-estabelecimentos"
```

Use `df -h /tmp spark-tmp /home/<user>/spark-tmp` to compare capacity. Changing this directory moves only rebuildable Spark working files; it does not move or modify raw, bronze, or silver data.
## Unexpected null dates

Only eight-digit `yyyyMMdd` values parse. Blanks and malformed values become null and contribute to applicable quality metrics.

## Unexpected output replacement

The committed write mode is `overwrite`. Verify the bronze destination and environment overrides before rerunning. Raw inputs are never an acceptable output destination.

## Quality metrics are nonzero

Bronze reports rather than rejects these conditions. Silver rejects invalid or duplicate CNPJ identifiers and reports its other completeness metrics. Confirm source completeness, inspect representative affected records, and compare with prior snapshots. Do not silently add filtering or correction semantics outside the dataset and quality contracts.
