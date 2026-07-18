# Atlas

Atlas is a private commercial monorepo for turning Brazilian public company data into trusted, business-ready tables. The current implementation ingests Receita Federal `estabelecimentos` to bronze, normalizes them into the first v0.2 silver contract, and can maintain a compact local change history between monthly releases.

Atlas uses a medallion-style raw -> bronze -> silver -> gold -> serving/index data flow. See [Data layers](docs/manual/data_layers.md) for each layer contract and current Receita examples.

## Current scope

`apps/etl` reads the raw Receita snapshot, writes release-scoped bronze Parquet, builds the latest curated silver establishment table, produces JSON and Markdown quality reports, records run status as local JSON metadata, and stores selected field-level history deltas. Municipality enrichment, CNAE business groups, lead exports, API, UI, indexing, AI, sanctions, procurement, and other Receita datasets remain roadmap items.

## Atlas local CLI

From the repository root:

```bash
./atlas help
./atlas version
./atlas ingest receita estabelecimentos
./atlas normalize receita estabelecimentos
./atlas refresh receita estabelecimentos --release 2026-07
./atlas status
./atlas status --json
./atlas releases list
./atlas releases inspect --release 2026-07
./atlas releases drop-derived --release 2026-07 --layer bronze --dry-run
./atlas releases rebuild-establishments --from-release 2026-05 --to-release 2026-07
```

The existing sbt commands remain supported from `apps/etl`, including:

```bash
sbt "runMain atlas.Main ingest-receita-estabelecimentos"
```

## Layout

- `apps/etl/` - Scala/Spark ETL application and local data layers
- `docs/` - product direction, data contracts, user manual, operations, and roadmap decisions
- `apps/etl/data/raw/receita/` - downloaded snapshots, protected and ignored by Git

Start with the [documentation index](docs/index.md), see the canonical [Atlas unified plan](docs/atlas_unified_plan.md) for product direction, read the [status registry manual](docs/manual/status_registry.md), and use [apps/etl/README.md](apps/etl/README.md) for commands. Raw data was migrated intact to `apps/etl/data/raw/receita/2026-06`; its incomplete `.part` archive remains resumable.
