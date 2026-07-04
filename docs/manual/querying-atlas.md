# Querying Atlas

Atlas v0.1 has no public API, search index, UI, or supported query service. Query the generated bronze Parquet locally for inspection only.

DuckDB examples under `apps/etl/examples/duckdb/` demonstrate bronze inspection and preview future lead and graph questions. Preview queries do not define production silver, gold, ranking, filtering, or public-query contracts.

Do not query raw Receita CSV directly for product behavior. Future consumer surfaces must use explicit gold or serving contracts derived reproducibly from trusted pipeline stages.
