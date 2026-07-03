# Atlas ETL v0.1

Local Scala/Spark ingestion for Receita Federal CNPJ `estabelecimentos`. It uses explicit string-first parsing, normalizes CNPJ components, adds provenance metadata, writes state-partitioned bronze Parquet, and emits quality reports.

## Requirements and commands

Use JDK 17 and sbt 1.10+. From `apps/etl`:

```bash
sbt compile
sbt test
sbt "runMain atlas.Main ingest-receita-estabelecimentos"
```

For a smaller JVM, pass suitable sbt `-J-Xmx` settings; production laptop runs may use `sbt -J-Xmx24G ...`. Spark remains local and memory is not hard-coded.

## Input and output

The committed configuration reads `data/raw/receita/2026-06/estabelecimentos/extracted`. Override it with `ATLAS_RECEITA_RAW_DIR`. Output defaults to `data/bronze/receita/estabelecimentos`, partitioned by `state`.

The same run writes:

- `data/bronze/receita/estabelecimentos_quality_report.json`
- `data/bronze/receita/estabelecimentos_quality_report.md`

Download or resume a snapshot with:

```bash
python scripts/download_receita.py --month 2026-06 --extract
```

Raw archives, extracted CSV, Parquet, reports, and temporary Spark files are ignored by Git. Never delete a `.part` file merely because a download was interrupted.

## Querying

DuckDB examples live in `examples/duckdb`. They demonstrate bronze inspection and preview future lead and graph questions without implementing those product layers yet.
