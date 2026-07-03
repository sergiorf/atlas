-- Run from the repository root: duckdb < examples/duckdb/estabelecimentos.sql
SELECT uf, count(*) AS establishments
FROM read_parquet(
  'data/parquet/estabelecimentos/**/*.parquet',
  hive_partitioning = true
)
GROUP BY uf
ORDER BY establishments DESC;
