# Local ETL operations

Atlas targets a local machine with 32 GB RAM and 1 TB SSD. Use JDK 17, sbt 1.10+, Scala 2.12, and Spark 3.5.

From `apps/etl`:

```bash
sbt compile
sbt test
sbt "runMain atlas.Main ingest-receita-estabelecimentos"
sbt "runMain atlas.Main normalize-receita-estabelecimentos"
sbt "runMain atlas.Main status"
```

Configuration is in `conf/application.conf`. Override raw, bronze, and silver paths with `ATLAS_RECEITA_RAW_DIR`, `ATLAS_RECEITA_BRONZE_DIR`, and `ATLAS_RECEITA_SILVER_DIR`. `ATLAS_RECEITA_SNAPSHOT` identifies status records independently of path parsing, and `ATLAS_STATUS_DIR` overrides their default `data/_atlas/status` root. Spark master, shuffle partitions, local directory, CSV delimiter and encoding, and write mode are configuration-owned. For constrained runs, pass suitable sbt JVM options; a production laptop run may use `sbt -J-Xmx24G ...`.

Spark local storage defaults to `spark-tmp` relative to `apps/etl`. Spark uses this directory for shuffle spill, cached blocks, and other temporary working data, so keep it on a filesystem with ample free space. In WSL2, do not point it at `/tmp`: that path may be a tmpfs of only a few gigabytes even when the WSL root filesystem has hundreds of gigabytes free. Override the default with `ATLAS_SPARK_LOCAL_DIR=/home/<user>/spark-tmp` or set `atlas.spark.local-dir` in a custom HOCON file.

Do not call `collect()` on large frames, convert full datasets to local collections, or mix data layers. Both jobs use disk-backed persistence and state-partitioned Parquet. Silver validates structure first, writes malformed rows to `data/_atlas/quality/receita/establishments/<snapshot>/malformed_rows`, and checks duplicate keys only among valid candidates. A warning prints the quarantine count and path. Conflicting valid duplicates are written to the sibling `duplicate_cnpj_full` path and reject publication before the silver writer is invoked.

Bronze and silver jobs record their latest attempts in the local [status registry](../manual/status_registry.md). Inspect them with `sbt "runMain atlas.Main status"`. A failed status is diagnostic only: investigate and rerun the job, which replaces the record for the same source, dataset, snapshot, and layer. Do not edit status files to claim a successful run. Quality and status outputs are generated local artifacts and must not be committed.
