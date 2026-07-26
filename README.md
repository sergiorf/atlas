# Atlas

Atlas is a private commercial monorepo for turning Brazilian public company data into trusted, business-ready tables. The current implementation ingests Receita Federal `estabelecimentos` to bronze, normalizes them into the first v0.2 silver contract, and can maintain a compact local change history between monthly releases.

Atlas uses a medallion-style raw -> bronze -> silver -> gold -> serving/index data flow. See [Data layers](docs/manual/data_layers.md) for each layer contract and current Receita examples.

## Current scope

`apps/etl` reads the raw Receita establishment snapshot, writes release-scoped bronze Parquet,
builds the latest curated silver establishment table, produces quality reports, records run status,
and stores compact history. A separate pre-bronze command acquires and verifies the company-data
source bundle. Company-data transformations, municipality enrichment, CNAE business groups, lead
exports, API, UI, indexing, AI, sanctions, and procurement remain roadmap items.

## Atlas local CLI

From the repository root:

```bash
./atlas help
./atlas version
./atlas download receita company-data --release 2026-05
./atlas ingest receita estabelecimentos
./atlas normalize receita estabelecimentos
./atlas refresh receita estabelecimentos --release 2026-07
./atlas status
./atlas status --release 2026-07
./atlas status --verbose
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

## Company-data foundation

Atlas now implements the pre-bronze acquisition gate for the planned Receita company data
foundation: `Empresas`,
official Receita reference dimensions, a Receita-to-IBGE municipality hierarchy, and compact
May–July company history published with the existing establishment state as a coherent bundle.
See the [unified plan](docs/atlas_unified_plan.md#v03a--receita-company-data-foundation-active-next-tranche)
and the [detailed foundation plan](docs/plans/receita-company-data-foundation.md).

`./atlas download receita company-data --release YYYY-MM` downloads and verifies the immutable
source bundle and records raw status. Company-data bronze, silver, history, bundle publication,
gold tables, lead exports, OpenSearch, API, and website work remain unimplemented.

## Layout

- `apps/etl/` - Scala/Spark ETL application and local data layers
- `docs/` - product direction, data contracts, user manual, operations, and roadmap decisions
- `apps/etl/data/raw/receita/` - downloaded snapshots, protected and ignored by Git

Start with the [documentation index](docs/index.md), consult the canonical [Dataset and source
catalog](docs/source_catalog.md) for the complete inventory, and use the [local ETL operations
guide](docs/operations/local-etl.md) for commands and recovery procedures. The [Atlas unified
plan](docs/atlas_unified_plan.md) remains the sole owner of product priority and sequencing.
