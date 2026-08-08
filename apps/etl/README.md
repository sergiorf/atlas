# Atlas ETL

Local Scala/Spark ingestion, normalization, compact history, and atomic silver publication for
Receita Federal CNPJ companies and establishments. The company-data workflow also versions the six
Receita reference dimensions and exact TOM-to-IBGE municipality geography.

See the repository [getting-started manual](../../docs/manual/getting-started.md), [Receita dataset specification](../../docs/specs/datasets/receita-cnpj.md), [status registry manual](../../docs/manual/status_registry.md), and [local operations guide](../../docs/operations/local-etl.md) for the supported contract and operational details.

## Requirements and commands

Use JDK 17 and sbt 1.10+. From `apps/etl`:

```bash
sbt compile
sbt test
sbt "runMain atlas.Main ingest-receita-estabelecimentos"
sbt "runMain atlas.Main normalize-receita-estabelecimentos"
sbt "runMain atlas.Main refresh-receita-estabelecimentos --release 2026-07"
sbt "runMain atlas.Main status"
```

From the repository root, prefer the short wrapper for day-to-day use:

```bash
./atlas download receita estabelecimentos --release 2026-07
./atlas ingest receita estabelecimentos
./atlas normalize receita estabelecimentos
./atlas refresh receita estabelecimentos --release 2026-07
./atlas releases rebuild-establishments --from-release 2026-05 --to-release 2026-07
./atlas releases rebuild-company-data --from-release 2026-05 --to-release 2026-07
./atlas releases inspect-bundle
./atlas releases validate-bundle --full
./atlas releases drop-stale-derived --dry-run
./atlas storage usage
./atlas storage cleanup
./atlas status --json
```

For a smaller forked ETL JVM, use the root wrapper's `--memory SIZE` option, such as `--memory 10G`. Spark remains local and memory is not hard-coded. Spark spill files default to `spark-tmp`, avoiding a size-limited WSL `/tmp`; override this location with `ATLAS_SPARK_LOCAL_DIR` when needed.

## Input and output

The committed raw-path template is `data/raw/receita/2026-06/estabelecimentos/extracted`. Selecting another release replaces its `YYYY-MM` segment, so `--release 2026-05` reads `data/raw/receita/2026-05/estabelecimentos/extracted`. Override it with `ATLAS_RECEITA_RAW_DIR`; a custom path without a date segment is used unchanged. Bronze defaults to `data/bronze/receita/estabelecimentos/release=<snapshot>`; standalone normalization writes `data/_atlas/work/receita/estabelecimentos/release=<snapshot>/silver_candidate`; latest silver current defaults to `data/silver/receita/establishments_current`. These tables are partitioned by `state`. Compact history events are written under `data/silver/receita/establishment_change_events/to_release=<snapshot>`. Override roots with `ATLAS_RECEITA_BRONZE_DIR` and `ATLAS_RECEITA_SILVER_DIR`.

Bronze attempts write the latest status to `data/_atlas/status/receita/estabelecimentos/2026-06/bronze.json`; silver uses `data/_atlas/status/receita/establishments/2026-06/silver.json`. Override the snapshot with `ATLAS_RECEITA_SNAPSHOT` and the registry root with `ATLAS_STATUS_DIR`. The status command reads these small JSON files without starting Spark and distinguishes clean success from `success_with_warnings`.

Company-data raw input defaults to `data/raw/receita/<snapshot>/company-data` and can be overridden
with `ATLAS_RECEITA_COMPANY_DATA_RAW_DIR`. Published bundle generations live beneath
`data/_atlas/bundles/generations`; `current_bundle.json` selects the only active generation.

Ingestion writes release-scoped reports under `data/_atlas/reports/receita/estabelecimentos/<snapshot>/bronze`. Normalization writes reports under the sibling `silver` directory. The refresh command keeps only the latest full normalized table, stores older differences as selected field deltas, and writes one analytical summary per published release. It does not keep full historical silver copies.

Malformed silver candidates are quarantined beneath `data/_atlas/quality/receita/establishments/<snapshot>/malformed_rows` and excluded before uniqueness validation. Duplicate valid CNPJ identifiers are reported in the sibling `duplicate_cnpj_full` directory and reject publication before the existing silver table is replaced.

Legacy derived outputs from earlier local runs, such as `data/silver/receita/establishments` and old sibling `establishments_quality_report.*` files, can be quarantined with `./atlas releases drop-stale-derived --force`. The command leaves raw files, `establishments_current`, and compact history events intact.

Unified storage cleanup is a dry run unless `--force` is supplied. It inspects existing trash and
failed or inactive bundle candidates, old bronze releases, and completed work. It uses configured
retention counts and recovery windows and never inventories raw data as a deletion candidate. A
first forced invocation quarantines eligible live candidates; only a later invocation can
permanently delete them. Use `./atlas storage reconcile-trash` for guarded legacy-manifest repair
and `./atlas storage reclaim --prepare-wsl` before the separate Windows VHD compaction helper.

Routine refresh only advances current to a newer release. Standalone normalization writes a release-scoped candidate. To recreate the entire active establishment dataset from protected raw releases, preview and then force `./atlas releases rebuild-establishments --from-release YYYY-MM --to-release YYYY-MM`; see the repository refresh runbook for validation and recovery steps.

Download, resume, extract, and record raw pipeline status with:

```bash
./atlas download receita estabelecimentos --release 2026-06
```

Use `--latest` to discover the latest published month, or `--no-extract` when preserving archives
without preparing Spark input. The underlying `python scripts/download_receita.py` command remains
available for low-level recovery and accepts the equivalent `--month` and `--extract` options.

Raw archives, extracted CSV, Parquet, reports, status metadata, and temporary Spark files are ignored by Git. Never delete a `.part` file merely because a download was interrupted.

## Querying

DuckDB examples live in `examples/duckdb`. They demonstrate bronze inspection and preview future lead and graph questions without implementing those product layers yet. Silver is an internal ETL contract, not a public query surface.
