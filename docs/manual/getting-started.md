# Getting started

Atlas ingests Receita Federal CNPJ `Estabelecimentos` CSV files into local bronze Parquet, then builds a curated silver establishment table. Both stages write JSON and Markdown quality reports.

## Prerequisites

- JDK 17
- sbt 1.10 or newer
- Python 3 for Atlas raw acquisition
- sufficient local disk space for archives, extracted CSV, Parquet, and Spark temporary data

From `apps/etl`, run:

```bash
sbt compile
sbt test
./atlas download receita estabelecimentos --release 2026-06
sbt "runMain atlas.Main ingest-receita-estabelecimentos"
sbt "runMain atlas.Main normalize-receita-estabelecimentos"
```

The committed raw-path template is `data/raw/receita/2026-06/estabelecimentos/extracted` relative to `apps/etl`. `--release YYYY-MM` replaces that dated segment, so each release reads its matching raw directory. Override raw, bronze, and silver paths with `ATLAS_RECEITA_RAW_DIR`, `ATLAS_RECEITA_BRONZE_DIR`, and `ATLAS_RECEITA_SILVER_DIR`; a custom raw path without a date segment is used unchanged.

Spark uses `spark-tmp` for shuffle spill and other local working files. This intentionally avoids `/tmp`, which WSL2 may mount as a small tmpfs. Set `ATLAS_SPARK_LOCAL_DIR` to another directory on a filesystem with sufficient free space if needed.

The jobs write state-partitioned bronze Parquet under `data/bronze/receita/estabelecimentos`, latest normalized silver under `data/silver/receita/establishments_current`, compact change events under `data/silver/receita/establishment_change_events`, and quality reports under `data/_atlas/reports`. Raw and generated files are ignored by Git. See the [local ETL guide](../operations/local-etl.md) before changing paths or memory settings.
