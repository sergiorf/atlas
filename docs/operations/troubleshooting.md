# Troubleshooting

## No input files

Check the configured raw directory, extraction completion, and `ATLAS_RECEITA_RAW_DIR`. The job expects extracted Estabelecimentos files beneath that directory.

## Interrupted download

Keep `.part` files and rerun the downloader so it can resume. Do not delete or rename raw artifacts merely to make a run succeed.

## Out of memory or excessive disk use

Confirm free disk space, reduce unrelated workloads, choose a suitable forked ETL heap with `./atlas ... --memory SIZE`, and inspect Spark temporary directories. For example, a machine with 16 GiB RAM may use `--memory 10G`, leaving capacity for the operating system, sbt, and Spark overhead. Do not solve memory pressure by collecting the dataset locally. Review shuffle partitions and storage changes as contract-affecting work when they alter operational guarantees.

The refresh pipeline persists its large candidate and change-event intermediates with disk-only storage. A stack trace through `InMemoryRelation`, `DefaultCachedBatchSerializer`, and `MemoryStore.putIterator` indicates an older build still using memory-backed history caches; rebuild before retrying. Ingest and refresh may share one Spark session, but the ingest intermediate is unpersisted before normalization begins.

## Refresh release is not newer than current

Atlas refuses equal or older refreshes because they would reverse or rewrite compact history. Confirm current with `./atlas status` and a `SELECT DISTINCT release` query against `establishments_current`. Use the next release for routine refresh. If history is already inconsistent, use the guarded full rebuild in the refresh runbook rather than moving Parquet directories manually.

A current table without a `release` column is legacy output. Prefer a full rebuild from known raw releases. Use `--allow-legacy-current` only after independently confirming that the candidate is newer; Atlas cannot infer the earlier release and records null `from_release` values for that migration.

## Rebuild lock or interrupted promotion

Refresh and full rebuild share `data/_atlas/locks/receita-estabelecimentos-current.lock`. Wait for the active process to finish; do not delete a lock used by a running JVM. Operating-system locks are released when a process exits.

Full rebuild writes a transaction journal only during activation. A subsequent forced rebuild restores an interrupted promotion from its timestamped backup before starting. If Atlas reports a malformed journal, stop and inspect `data/_atlas/transactions/establishments-rebuild.tsv`, the named staging directory, and the named trash directory; do not delete any of them until active current and history have been verified.

## Trash purge skips a generation

`./atlas releases purge-trash` treats policy blockers as normal diagnostics and continues inspecting other generations. A generation remains blocked when it is too young, referenced by the active rebuild journal, has an unknown timestamp or layout, contains a symbolic link, escapes the configured trash root, has malformed metadata, or still has a recovery role. Full-rebuild backups additionally require all expected active outputs and complete canonical successful bronze, silver, and history status. Fix the active publication or complete a later successful rebuild; the purge command never rewrites status metadata. Filesystem failures during an eligible deletion fail the command and require operator inspection.

Do not rename legacy trash to make it appear recognizable and do not remove `.atlas-trash-manifest.json`. Legacy full-rebuild backups can remain blocked because pre-manifest generations do not prove their replacement expectations.

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

## Failed company bundles consume disk

Use `./atlas storage usage --category staging` to measure retained failed candidates and
`./atlas storage cleanup` to inspect guarded cleanup decisions. Failed bundles are outside
`_trash`, so the lower-level `releases purge-trash` command does not inventory them. Unified
cleanup permanently deletes eligible existing trash and then quarantines eligible failed bundles;
run a separate dry run and force invocation before permanently deleting the new quarantine.
Raw data and active published bundles are never candidates.

## Corporate component calculation does not stabilize

This failure means connected-company labels were still changing after the configured propagation
allowance. It indicates a deeper company-to-company structure than the allowance supports, or an
algorithm defect; it is not an out-of-memory condition. Natural-person QSA records do not
participate in this graph.

Read the release, changed-node count, and iteration-artifact path from the exception. The retained
failed bundle contains `graph-component-labels` output for diagnosis, while atomic publication
leaves `current_bundle` unchanged. Do not edit relationship inputs or generated Parquet to force
convergence. Verify `atlas.graph.max-component-propagation-rounds` (default 128), investigate an
unexpectedly large requirement, and retry only after a reviewed configuration or implementation
change. `--memory` affects heap capacity but not the required propagation rounds.

## Unexpected output replacement

The committed write mode is `overwrite`. Verify the bronze destination and environment overrides before rerunning. Raw inputs are never an acceptable output destination.

## Quality metrics are nonzero

Bronze reports rather than rejects these conditions. Silver rejects invalid or duplicate CNPJ identifiers and reports its other completeness metrics. Confirm source completeness, inspect representative affected records, and compare with prior snapshots. Do not silently add filtering or correction semantics outside the dataset and quality contracts.
