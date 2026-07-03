-- Future gold aggregate shape; this query derives a preview from bronze.
SELECT date_trunc('month', opening_date) AS opening_month, state, main_cnae, count(*) AS company_count
FROM read_parquet('data/bronze/receita/estabelecimentos/**/*.parquet', hive_partitioning = true)
GROUP BY ALL ORDER BY opening_month DESC, company_count DESC;
