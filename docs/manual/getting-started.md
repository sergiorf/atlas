# Getting started

Atlas processes Receita Federal CNPJ companies and establishments into local bronze and
quality-gated silver data. The company-data workflow publishes companies, establishments,
reference dimensions, municipality geography, and compact history as one atomic generation.

## Prerequisites

- JDK 17
- sbt 1.10 or newer
- Python 3 for Atlas raw acquisition
- sufficient local disk space for archives, extracted CSV, Parquet, and Spark temporary data

For an establishment-only refresh, run:

```bash
./atlas download receita estabelecimentos --release 2026-06
./atlas refresh receita estabelecimentos --release 2026-06
./atlas status
```

The default status view summarizes every recorded snapshot and expands problems for the newest
one. Use `./atlas status --release YYYY-MM` to inspect another snapshot, or
`./atlas status --verbose` when exact timestamps and generated paths are needed.

For the complete company-data bundle, run the coordinated acquisition and then the local-only
refresh:

```bash
./atlas download receita company-data --release 2026-05
./atlas refresh receita company-data --release 2026-05
./atlas releases inspect-bundle
./atlas status
```

The coordinator invokes the existing establishment acquisition with the same release and preserves
separate raw layouts and manifests. The standalone `download receita estabelecimentos` command
remains available for establishment-only operation and recovery. Refresh performs no network I/O
and publishes only after every component and quality gate succeeds. See the
[company-data runbook](../operations/company-data-pipeline.md).

The establishment refresh ingests bronze, validates a silver candidate, records release history
and a summary, and publishes latest current. Company-data refresh coordinates all components
behind the atomic bundle boundary. To exercise individual establishment stages, use the
corresponding `runMain` commands from `apps/etl`; see the
[local ETL operations guide](../operations/local-etl.md) for the complete command reference.

The committed raw-path template is `data/raw/receita/2026-06/estabelecimentos/extracted` relative to `apps/etl`. `--release YYYY-MM` replaces that dated segment, so each release reads its matching raw directory. Override raw, bronze, and silver paths with `ATLAS_RECEITA_RAW_DIR`, `ATLAS_RECEITA_BRONZE_DIR`, and `ATLAS_RECEITA_SILVER_DIR`; a custom raw path without a date segment is used unchanged.

Spark uses `spark-tmp` for shuffle spill and other local working files. This intentionally avoids `/tmp`, which WSL2 may mount as a small tmpfs. Set `ATLAS_SPARK_LOCAL_DIR` to another directory on a filesystem with sufficient free space if needed.

Generated bronze, silver, bundle, quality, report, status, and Spark-temporary data remain outside
Git. Resolve current company-data component paths with `releases inspect-bundle` rather than
guessing generation paths. See the [local ETL guide](../operations/local-etl.md) before changing
paths or memory settings.
