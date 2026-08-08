# Atlas ETL

This module contains the local Scala 2.12 and Spark 3.5 pipelines that acquire, normalize,
quality-check, publish, and export Atlas company data.

Use the repository-root wrapper for routine work:

```bash
./atlas compile
./atlas test
./atlas help
```

Canonical guidance lives in the repository documentation:

- [Build and test Atlas](../../docs/development/building.md)
- [CLI reference](../../docs/operations/cli-reference.md)
- [Architecture](../../docs/architecture.md)
- [Datasets](../../docs/manual/datasets.md)
- [Company-data runbook](../../docs/operations/company-data-pipeline.md)

Raw inputs, Parquet, reports, exports, status metadata, and Spark working data are generated local
artifacts. They must not be committed or manually modified.
