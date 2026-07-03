# atlas-etl

`atlas-etl` is a local Scala/Spark pipeline for Brazil's public Receita Federal CNPJ dataset. The first MVP ingests extracted, semicolon-delimited **Estabelecimentos** CSV files, applies the official positional layout, normalizes common values, and writes Parquet partitioned by state (`uf`). The output is suitable for DuckDB, Spark SQL, and a later serving layer.

The project deliberately starts small: downloads, orchestration, dbt, cloud infrastructure, and a web application are outside the first version.

## Prerequisites

- JDK 17
- sbt 1.10+
- Python 3.10+ for the downloader (standard library only)
- Enough disk space for the Receita archives and extracted files
- DuckDB (optional, for querying the output)

Spark runs in local mode and is resolved by sbt; a separate Spark installation is not required.

## Project layout

```text
conf/                  HOCON runtime configuration
src/main/scala/atlas/  CLI, ingestion, schemas, transforms, and writers
src/test/scala/atlas/  ScalaTest unit tests
examples/duckdb/       Example queries over generated Parquet
scripts/                Restartable Receita download utility
data/raw/              Local extracted Receita files (git-ignored)
data/parquet/          Generated tables (git-ignored)
```

## Download the source data

The downloader discovers the newest Receita monthly snapshot when no month is supplied. It stores archives and extracted files under a reproducible `YYYY-MM` directory:

```bash
python scripts/download_receita.py --extract
```

To download a specific snapshot, use `-Month`:

```bash
python scripts/download_receita.py --month 2026-01 --extract
```

Interrupted downloads remain as `.part` files and resume on the next invocation. Completed files are checked against the remote byte size and skipped. Metadata is written to `data/raw/YYYY-MM/estabelecimentos/manifest.json`.

The utility reads Receita's official public WebDAV share. Its public share token is built in; `RECEITA_SHARE_TOKEN` can override it if Receita rotates the link.

## Configure and run

Edit [`conf/application.conf`](conf/application.conf) for the downloaded month, or set `ATLAS_ETL_INPUT_GLOB` to the extracted-file glob printed by the downloader. CSV input uses `;` and `ISO-8859-1`; output is written to `data/parquet/estabelecimentos`.

```bash
sbt compile
sbt "run --config conf/application.conf"
```

For a quick validation, sample mode limits the number of input rows before transformation:

```bash
sbt "run --config conf/application.conf --sample"
```

Run checks and formatting with:

```bash
sbt test
sbt scalafmtCheckAll
```

The input files must not have a header. All Receita identifiers and codes are initially read as strings, preserving leading zeroes. Dates in `yyyyMMdd` format become SQL dates; blank or invalid date values become null. Existing output is overwritten according to the configured write mode.

## Output schema

The Parquet table contains the official Estabelecimentos columns in normalized `snake_case`, plus `cnpj`, assembled from `cnpj_basico + cnpj_ordem + cnpj_dv`. Strings are trimmed and blanks become null. Date fields are typed as dates. Output is partitioned by `uf` for a simple, useful first layout.

## Query with DuckDB

Run [`examples/duckdb/estabelecimentos.sql`](examples/duckdb/estabelecimentos.sql), or use:

```sql
SELECT uf, count(*) AS establishments
FROM read_parquet('data/parquet/estabelecimentos/**/*.parquet', hive_partitioning = true)
GROUP BY uf
ORDER BY establishments DESC;
```

## Tests

Tests use a small local Spark session and in-memory rows; no Receita download is required. They cover official schema positions, trimming and null conversion, date parsing, and full CNPJ construction.

## Future roadmap

Next file-specific modules can add Empresas, Socios, Simples, and reference tables without coupling them to Estabelecimentos. Once the Parquet layer is stable, dbt may be introduced for SQL models, documentation, data-quality tests, and analytical marts.
