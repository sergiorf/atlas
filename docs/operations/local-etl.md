# Local ETL operations

Atlas targets a local machine with 32 GB RAM and 1 TB SSD. Use JDK 17, sbt 1.10+, Scala 2.12, and Spark 3.5.

From `apps/etl`:

```bash
sbt compile
sbt test
sbt "runMain atlas.Main ingest-receita-estabelecimentos"
sbt "runMain atlas.Main normalize-receita-estabelecimentos"
sbt "runMain atlas.Main status"
```

From the repository root, the same local operations are available through the short CLI:

```bash
./atlas help
./atlas version
./atlas download receita company-data --release 2026-05
./atlas download receita estabelecimentos --release 2026-07
./atlas ingest receita estabelecimentos
./atlas normalize receita estabelecimentos
./atlas refresh receita estabelecimentos --release 2026-07
./atlas releases rebuild-company-data --from-release 2026-05 --to-release 2026-07
./atlas releases inspect-bundle
./atlas refresh receita estabelecimentos --release 2026-07 --memory 10G
./atlas releases rebuild-establishments --from-release 2026-05 --to-release 2026-07
./atlas status
./atlas status --release 2026-07
./atlas status --verbose
./atlas status --json
./atlas storage usage
```

Configuration is in `conf/application.conf`. Override raw, bronze, and silver paths with `ATLAS_RECEITA_RAW_DIR`, `ATLAS_RECEITA_BRONZE_DIR`, and `ATLAS_RECEITA_SILVER_DIR`. `ATLAS_RECEITA_SNAPSHOT` identifies the operator-selected release internally; the root CLI exposes the same value as `--release YYYY-MM`. When the configured raw directory contains a `YYYY-MM` path segment, Atlas replaces that segment with the selected release, so the default layout reads `data/raw/receita/<release>/estabelecimentos/extracted`. A custom raw directory without a date segment is used unchanged. Confirm the resolved input path in the bronze status or quality report after every run. `ATLAS_STATUS_DIR` overrides the default `data/_atlas/status` root. Spark master, shuffle partitions, local directory, CSV delimiter and encoding, and write mode are configuration-owned. For constrained runs, `--memory SIZE` sets the maximum heap of the forked ETL JVM without enlarging the sbt launcher; sizes use JVM notation such as `10G` or `8192M`.

Spark local storage defaults to `spark-tmp` relative to `apps/etl`. Spark uses this directory for shuffle spill, cached blocks, and other temporary working data, so keep it on a filesystem with ample free space. In WSL2, do not point it at `/tmp`: that path may be a tmpfs of only a few gigabytes even when the WSL root filesystem has hundreds of gigabytes free. Override the default with `ATLAS_SPARK_LOCAL_DIR=/home/<user>/spark-tmp` or set `atlas.spark.local-dir` in a custom HOCON file.

Keep all CNPJ components and full identifiers as strings; numeric and alphanumeric keys share the same uniqueness domain, and leading zeros are significant. Silver quality reports include `alphanumeric_cnpj_count` for cutover monitoring.

## Acquire the company-data source bundle

Before any company-data bronze ingestion, acquire and verify one explicitly selected release:

```bash
./atlas download receita company-data --release 2026-05
./atlas status
```

The command downloads or resumes `Empresas` and the six Receita reference archive groups, then
captures the official TOM municipality CSV and IBGE Localities municipality hierarchy. It streams
every archive member through the source-manifest checks and writes
`data/raw/receita/<release>/company-data/source-manifest.json`. A successful run records
`receita / company-data / <release> / raw`; a failed verification records failure and does not
authorize bronze ingestion. Rerunning preserves completed archives and reference captures.
IBGE responses may be stored as publisher-served gzip bytes; the manifest records that encoding
and validation decodes it without rewriting the capture.

This command does not extract archives, write bronze, modify the existing establishment raw tree,
or publish company tables. The separate `download receita estabelecimentos` command remains the
supported input workflow for the existing establishment pipeline.

After both source groups are available, follow the [company-data and atomic silver bundle
runbook](company-data-pipeline.md). Its rebuild command is dry-run by default, builds releases in
chronological order with `--force`, and publishes only after the complete candidate passes.

Do not call `collect()` on large frames, convert full datasets to local collections, or mix data layers. Both jobs use disk-backed persistence and state-partitioned Parquet. Silver validates structure first, writes malformed rows to `data/_atlas/quality/receita/establishments/<snapshot>/malformed_rows`, and checks duplicate keys only among valid candidates. A warning prints the quarantine count and path. Conflicting valid duplicates are written to the sibling `duplicate_cnpj_full` path and reject publication before the silver writer is invoked.

Raw acquisition, bronze, and silver jobs record their latest attempts in the local [status registry](../manual/status_registry.md). Use `./atlas status` for the compact release overview, `./atlas status --release YYYY-MM` for one snapshot, `./atlas status --verbose` for exact paths and timestamps, or `./atlas status --json` for automation. The equivalent sbt entry point is `sbt "runMain atlas.Main status"`. A failed status is diagnostic only: investigate and rerun the job, which replaces the record for the same source, dataset, snapshot, and layer. Do not edit status files to claim a successful run. Quality and status outputs are generated local artifacts and must not be committed.

## Release lifecycle management

Before cleanup, inventory the configured storage roots:

```bash
./atlas storage usage
./atlas storage usage --top 30
./atlas storage usage --category raw
./atlas storage usage --release 2026-07
./atlas storage usage --json
./atlas storage cleanup
./atlas storage cleanup --older-than-days 7 --force
```

The command is read-only. It reports apparent bytes, file counts, protection policy, exact path,
and the guarded next step for raw, bronze, silver, bundle, staging, work, trash, quality, report,
metadata, Spark-temporary, and unclassified locations. It streams the scan without following
symbolic links. Scan errors remain visible, and unknown paths are never described as safe to
delete. Release filtering is path-attribution based, so files without a release-shaped path are
not assigned to a release.

Compare its totals with `du` when diagnosing filesystem pressure. WSL's Windows-visible
`ext4.vhdx` can remain large after Linux files are removed; compacting that virtual disk is outside
Atlas and is separate from dataset cleanup.

`storage cleanup` is the normal cleanup entry point. Its dry run combines existing trash with
failed company bundle candidates. With `--force`, eligible existing trash is permanently deleted
first and eligible failed candidates are then atomically quarantined. Newly quarantined candidates
are deliberately retained until a separate invocation:

```bash
./atlas storage cleanup --older-than-days 0
./atlas storage cleanup --older-than-days 0 --force
```

Review the zero-day dry run carefully. The command still blocks malformed, symbolic, active,
status-referenced, transaction-referenced, or unidentified candidates. Moving a candidate into
trash does not reclaim bytes; only its later permanent deletion does. Raw inputs and active bundle
generations are never candidates. `--json` is inspection-only and cannot be combined with
`--force`.

Raw Receita files under `data/raw` are protected. Derived bronze, silver work tables, reports, history partitions, and release-summary partitions are rebuildable. Use dry-run first:

```bash
./atlas releases list
./atlas releases inspect --release 2026-07
./atlas releases drop-derived --release 2026-07 --layer bronze --dry-run
./atlas releases drop-derived --release 2026-07 --layer bronze --force
./atlas releases drop-derived --release 2026-07 --layer all-derived --dry-run
./atlas releases drop-stale-derived --dry-run
./atlas releases drop-stale-derived --force
./atlas releases purge-trash
./atlas releases purge-trash --older-than-days 7 --force
```

`drop-derived --force` moves derived paths to `data/_atlas/_trash/...` instead of deleting raw files. Atlas refuses invalid release ids and paths outside the configured ETL data directories.
`drop-stale-derived --force` quarantines known legacy derived paths that are no longer part of the current contract, such as the old `data/silver/receita/establishments` table and its old sibling quality reports. It does not touch raw files, `data/silver/receita/establishments_current`, compact history events, or release summaries.

`purge-trash` inventories each timestamped quarantine generation and explains its operation type, UTC age, bytes, eligibility, and blockers. Dry-run is the default. `--force` permanently deletes only eligible generations and keeps blocked or too-young generations; the default recovery window is seven full 24-hour periods from the timestamp directory. `--older-than-days N` accepts a non-negative override. Atlas takes the establishment publication lock and repeats the safety inspection before deleting, never follows symbolic links, prunes only empty timestamp directories, and reports deleted and skipped byte totals. Raw paths are outside the inventory and are never considered.

New quarantines contain an internal `.atlas-trash-manifest.json` recording their recovery role, creation time, original paths, replacement expectations, and labels. Atlas recognizes older release drops, stale-derived quarantines, and failed rebuilds by conservative layouts. Legacy full-rebuild backups without replacement expectations remain blocked.

## Change history storage

`./atlas refresh receita estabelecimentos --release YYYY-MM` ingests a release, normalizes it, compares it with the latest silver current table, writes compact selected field deltas and a release summary, and publishes the new latest current table. The first release seeds the current table and does not emit one insert event per establishment. Atlas stores only selected old/new field values in history, not full previous records, to fit the local 1 TB laptop constraint. It writes one compact summary per release, including seed and zero-change releases.

Refresh accepts only a release newer than current. Equal or older releases fail without changing current or history. `./atlas normalize receita estabelecimentos --release YYYY-MM` writes only the release-scoped candidate beneath `data/_atlas/work`; it cannot bypass publication ordering.

For a complete raw-to-history recreation, use `releases rebuild-establishments` as documented in the [refresh runbook](refresh-runbook.md). It stages a complete replacement, quarantines every active generated establishment output after validation, and preserves raw files. `sbt clean` is unrelated to dataset cleanup.
