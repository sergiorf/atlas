# Getting started

Atlas ingests Receita Federal CNPJ `Estabelecimentos` CSV files into local bronze Parquet, then builds a curated silver establishment table. Both stages write JSON and Markdown quality reports.

## Prerequisites

- JDK 17
- sbt 1.10 or newer
- Python 3 for the downloader
- sufficient local disk space for archives, extracted CSV, Parquet, and Spark temporary data

From `apps/etl`, run:

```bash
sbt compile
sbt test
python scripts/download_receita.py --month 2026-06 --extract
sbt "runMain atlas.Main ingest-receita-estabelecimentos"
sbt "runMain atlas.Main normalize-receita-estabelecimentos"
```

The committed configuration reads `data/raw/receita/2026-06/estabelecimentos/extracted` relative to `apps/etl`. Override raw, bronze, and silver paths with `ATLAS_RECEITA_RAW_DIR`, `ATLAS_RECEITA_BRONZE_DIR`, and `ATLAS_RECEITA_SILVER_DIR`.

Spark uses `spark-tmp` for shuffle spill and other local working files. This intentionally avoids `/tmp`, which WSL2 may mount as a small tmpfs. Set `ATLAS_SPARK_LOCAL_DIR` to another directory on a filesystem with sufficient free space if needed.

The jobs write state-partitioned Parquet under `data/bronze/receita/estabelecimentos` and `data/silver/receita/establishments`, with quality reports beside each layer. Raw and generated files are ignored by Git. See the [local ETL guide](../operations/local-etl.md) before changing paths or memory settings.
