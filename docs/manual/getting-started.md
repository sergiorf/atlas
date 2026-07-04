# Getting started

Atlas v0.1 ingests Receita Federal CNPJ `Estabelecimentos` CSV files into local bronze Parquet and writes JSON and Markdown quality reports.

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
```

The committed configuration reads `data/raw/receita/2026-06/estabelecimentos/extracted` relative to `apps/etl`. Override inputs with `ATLAS_RECEITA_RAW_DIR` and bronze output with `ATLAS_RECEITA_BRONZE_DIR`.

The job writes state-partitioned Parquet under `data/bronze/receita/estabelecimentos` and quality reports beside it. Raw and generated files are ignored by Git. See the [local ETL guide](../operations/local-etl.md) before changing paths or memory settings.
