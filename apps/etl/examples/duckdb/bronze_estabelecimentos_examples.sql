-- Run from apps/etl with: duckdb < examples/duckdb/bronze_estabelecimentos_examples.sql
SELECT state, count(*) AS establishments
FROM read_parquet('data/bronze/receita/estabelecimentos/**/*.parquet', hive_partitioning = true)
GROUP BY state ORDER BY establishments DESC;

SELECT cnpj_full, trade_name, opening_date, main_cnae, state, municipality_code
FROM read_parquet('data/bronze/receita/estabelecimentos/**/*.parquet', hive_partitioning = true)
WHERE cnpj_full = '12345678000190';
