# Local ETL operations

Atlas v0.1 targets a local machine with 32 GB RAM and 1 TB SSD. Use JDK 17, sbt 1.10+, Scala 2.12, and Spark 3.5.

From `apps/etl`:

```bash
sbt compile
sbt test
sbt "runMain atlas.Main ingest-receita-estabelecimentos"
```

Configuration is in `conf/application.conf`. Override raw and bronze paths with `ATLAS_RECEITA_RAW_DIR` and `ATLAS_RECEITA_BRONZE_DIR`. Spark master, shuffle partitions, CSV delimiter and encoding, and write mode are configuration-owned. For constrained runs, pass suitable sbt JVM options; a production laptop run may use `sbt -J-Xmx24G ...`.

Do not call `collect()` on large frames, convert full datasets to local collections, or mix data layers. The ingestion persists transformed data with `DISK_ONLY`, writes state-partitioned Parquet, and then emits quality reports.
