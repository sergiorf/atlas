# Local ETL operations

Atlas targets a local machine with 32 GB RAM and 1 TB SSD. Use JDK 17, sbt 1.10+, Scala 2.12, and Spark 3.5.

From `apps/etl`:

```bash
sbt compile
sbt test
sbt "runMain atlas.Main ingest-receita-estabelecimentos"
sbt "runMain atlas.Main normalize-receita-estabelecimentos"
```

Configuration is in `conf/application.conf`. Override raw, bronze, and silver paths with `ATLAS_RECEITA_RAW_DIR`, `ATLAS_RECEITA_BRONZE_DIR`, and `ATLAS_RECEITA_SILVER_DIR`. Spark master, shuffle partitions, CSV delimiter and encoding, and write mode are configuration-owned. For constrained runs, pass suitable sbt JVM options; a production laptop run may use `sbt -J-Xmx24G ...`.

Do not call `collect()` on large frames, convert full datasets to local collections, or mix data layers. Both jobs use disk-backed persistence and state-partitioned Parquet. Silver validates identity before writing; validation rejection leaves existing silver output untouched.
