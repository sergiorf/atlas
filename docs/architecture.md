# Architecture

The intended flow is raw public data → `apps/etl` bronze → silver → gold → future serving/indexing stores → API/UI/exports. Consumers must not query raw Receita files directly. The ETL stays a disk-based, local Spark application and does not absorb UI, API, search, billing, or AI responsibilities.
