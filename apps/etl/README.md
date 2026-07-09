# Atlas ETL

Local Scala/Spark ingestion, silver normalization, and compact release-to-release history for Receita Federal CNPJ `estabelecimentos`. Bronze uses explicit string-first parsing and provenance; silver provides a curated latest establishment table plus selected field deltas.

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
./atlas ingest receita estabelecimentos
./atlas normalize receita estabelecimentos
./atlas refresh receita estabelecimentos --release 2026-07
./atlas status --json
```

For a smaller JVM, pass suitable sbt `-J-Xmx` settings; production laptop runs may use `sbt -J-Xmx24G ...`. Spark remains local and memory is not hard-coded. Spark spill files default to `spark-tmp`, avoiding a size-limited WSL `/tmp`; override this location with `ATLAS_SPARK_LOCAL_DIR` when needed.

## Input and output

The committed configuration reads `data/raw/receita/2026-06/estabelecimentos/extracted`. Override it with `ATLAS_RECEITA_RAW_DIR`. Bronze defaults to `data/bronze/receita/estabelecimentos/release=<snapshot>`; latest silver current defaults to `data/silver/receita/establishments_current`. Both are partitioned by `state`. Compact history events are written under `data/silver/receita/establishment_change_events/to_release=<snapshot>`. Override roots with `ATLAS_RECEITA_BRONZE_DIR` and `ATLAS_RECEITA_SILVER_DIR`.

Bronze attempts write the latest status to `data/_atlas/status/receita/estabelecimentos/2026-06/bronze.json`; silver uses `data/_atlas/status/receita/establishments/2026-06/silver.json`. Override the snapshot with `ATLAS_RECEITA_SNAPSHOT` and the registry root with `ATLAS_STATUS_DIR`. The status command reads these small JSON files without starting Spark and distinguishes clean success from `success_with_warnings`.

Ingestion writes release-scoped reports under `data/_atlas/reports/receita/estabelecimentos/<snapshot>/bronze`. Normalization writes reports under the sibling `silver` directory. The refresh command keeps only the latest full normalized table and stores older differences as selected field deltas to avoid keeping full historical silver copies on a 1 TB laptop.

Malformed silver candidates are quarantined beneath `data/_atlas/quality/receita/establishments/<snapshot>/malformed_rows` and excluded before uniqueness validation. Duplicate valid CNPJ identifiers are reported in the sibling `duplicate_cnpj_full` directory and reject publication before the existing silver table is replaced.

Download or resume a snapshot with:

```bash
python scripts/download_receita.py --month 2026-06 --extract
```

Raw archives, extracted CSV, Parquet, reports, status metadata, and temporary Spark files are ignored by Git. Never delete a `.part` file merely because a download was interrupted.

## Querying

DuckDB examples live in `examples/duckdb`. They demonstrate bronze inspection and preview future lead and graph questions without implementing those product layers yet. Silver is an internal ETL contract, not a public query surface.
