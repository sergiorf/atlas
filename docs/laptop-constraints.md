# Laptop constraints

The target is a 32 GB RAM, 1 TB SSD laptop. Spark runs locally. Jobs must avoid `collect()` and conversion of large DataFrames to Scala collections, persist major stages to disk, process file groups incrementally, avoid unnecessary full shuffles, and keep temporary extraction cleanup possible. Memory settings remain operator-configurable.
