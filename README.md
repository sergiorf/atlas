# Atlas

Atlas is a private commercial monorepo for turning Brazilian public company data into trusted, business-ready tables. The current implementation ingests Receita Federal `estabelecimentos` to bronze and normalizes them into the first v0.2 silver contract.

Atlas uses a medallion-style raw -> bronze -> silver -> gold -> serving/index data flow. See [Data layers](docs/manual/data_layers.md) for each layer contract and current Receita examples.

## Current scope

`apps/etl` reads the raw Receita snapshot, writes bronze Parquet, builds a curated silver establishment table, produces JSON and Markdown quality reports, and records bronze run status as local JSON metadata. Municipality enrichment, CNAE business groups, lead exports, API, UI, indexing, AI, sanctions, procurement, and other Receita datasets remain roadmap items.

## Layout

- `apps/etl/` - Scala/Spark ETL application and local data layers
- `docs/` - product direction, data contracts, user manual, operations, and roadmap decisions
- `apps/etl/data/raw/receita/` - downloaded snapshots, ignored by Git

Start with the [documentation index](docs/index.md), see the canonical [Atlas unified plan](docs/atlas_unified_plan.md) for product direction, read the [status registry manual](docs/manual/status_registry.md), and use [apps/etl/README.md](apps/etl/README.md) for commands. Raw data was migrated intact to `apps/etl/data/raw/receita/2026-06`; its incomplete `.part` archive remains resumable.
