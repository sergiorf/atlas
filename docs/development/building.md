# Build and test Atlas

Atlas is a local Scala 2.12 and Spark 3.5 ETL application. Use the repository-root `./atlas`
wrapper for routine development; it delegates build commands to sbt in `apps/etl` and pipeline
commands to `atlas.Main`.

## Prerequisites

- JDK 17
- sbt 1.10 or newer
- Python 3 for acquisition scripts and their tests
- Git
- sufficient local disk for archives, extracted CSV, Parquet, and Spark spill
- optional DuckDB for local data inspection

Check the installed tools:

```bash
java -version
sbt --version
python3 --version
./atlas version
```

## Compile and test

From the repository root:

```bash
./atlas compile
./atlas test
```

The equivalent commands from `apps/etl` are:

```bash
sbt compile
sbt test
```

Run one Scala suite while developing:

```bash
cd apps/etl
sbt "testOnly atlas.receita.CompanyProductsPipelineTest"
```

Run the acquisition-script tests without downloading public data:

```bash
cd apps/etl
python3 -m unittest discover -s scripts -p 'test_*.py'
```

Tests use small fixtures and must not require the complete Receita dataset.

## Clean build outputs

```bash
./atlas clean
```

This runs `sbt clean`. It removes build outputs only; it does not delete raw inputs, generated
Parquet, reports, bundle generations, exports, or Spark working data. Use the guarded
[storage commands](../operations/cli-reference.md#storage-and-cleanup) for data lifecycle work.

## Configuration

Defaults live in `apps/etl/conf/application.conf`; CNAE product taxonomy lives in
`apps/etl/conf/cnae-groups.conf`. The most commonly used overrides are:

| Variable | Purpose |
| --- | --- |
| `ATLAS_RECEITA_RAW_DIR` | Establishment raw-input root |
| `ATLAS_RECEITA_COMPANY_DATA_RAW_DIR` | Company-data raw-input root |
| `ATLAS_RECEITA_BRONZE_DIR` | Bronze output root |
| `ATLAS_RECEITA_SILVER_DIR` | Silver output root |
| `ATLAS_RECEITA_SNAPSHOT` | Operator-selected `YYYY-MM` release |
| `ATLAS_STATUS_DIR` | Run-status registry root |
| `ATLAS_SPARK_LOCAL_DIR` | Spark spill and local working directory |

Use `--config PATH` with pipeline commands to load an alternate HOCON file and `--memory SIZE` to
set the forked ETL JVM heap for one command:

```bash
./atlas refresh receita company-data --release 2026-07 --config conf/application.conf --memory 10G
```

Do not commit environment files, credentials, downloaded source data, generated data, reports, or
temporary Spark files.

## Local resource behavior

Atlas targets a local machine with approximately 32 GB RAM and a 1 TB SSD. Spark working data
defaults to `apps/etl/spark-tmp`. On WSL2, keep it off a small `/tmp` tmpfs; set
`ATLAS_SPARK_LOCAL_DIR` to a directory on the Linux filesystem with sufficient free space.

Inspect storage before a large refresh:

```bash
./atlas storage usage --top 30
```

## Development workflow

Before changing published data behavior, read the [feature development workflow](../feature_development_workflow.md),
the relevant [dataset and schema contracts](../index.md#implemented-data-contracts), and the
[unified plan](../atlas_unified_plan.md). Raw inputs are immutable and must never be edited or
deleted as part of a code change.

For command syntax and operational examples, use the [CLI reference](../operations/cli-reference.md).
For failures during builds or local runs, use [troubleshooting](../operations/troubleshooting.md).
