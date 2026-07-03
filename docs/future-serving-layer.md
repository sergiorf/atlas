# Future serving layer

A future `apps/indexer` will load gold Parquet into an appropriate serving store such as PostgreSQL, DuckDB, Meilisearch, ClickHouse, or OpenSearch. That choice follows measured query needs. APIs and UI query those derived stores; they never query raw Receita CSV directly.
