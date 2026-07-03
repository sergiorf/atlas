# Atlas

Atlas is a private commercial monorepo for turning Brazilian public company data into trusted, business-ready tables. The current v0.1 implements only the local Spark ETL for Receita Federal `estabelecimentos`.

## Current scope

`apps/etl` reads the raw Receita snapshot, writes bronze Parquet, and produces JSON and Markdown quality reports. API, UI, indexing, AI, sanctions, procurement, and other Receita datasets are roadmap items only.

## Layout

- `apps/etl/` - Scala/Spark ETL application and local data layers
- `docs/` - product, architecture, data-model, quality, and roadmap decisions
- `apps/etl/data/raw/receita/` - downloaded snapshots, ignored by Git

See the canonical [consolidated product plan](docs/product-plan.md) for the complete product direction and [apps/etl/README.md](apps/etl/README.md) for commands. Raw data was migrated intact to `apps/etl/data/raw/receita/2026-06`; its incomplete `.part` archive remains resumable.
