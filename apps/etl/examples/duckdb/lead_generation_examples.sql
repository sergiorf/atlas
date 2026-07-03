-- Preview of a future lead query. Municipality names arrive in v0.2.
SELECT cnpj_full, trade_name, opening_date, main_cnae, municipality_code, state
FROM read_parquet('data/bronze/receita/estabelecimentos/**/*.parquet', hive_partitioning = true)
WHERE registration_status_code = '02' AND state = 'PE'
  AND main_cnae IN ('6201501', '6202300', '6203100', '6204000', '6209100')
ORDER BY opening_date DESC LIMIT 100;
