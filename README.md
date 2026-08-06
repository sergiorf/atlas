# Atlas

Atlas is a private commercial monorepo for turning Brazilian public company data into trusted,
business-ready tables. The current implementation processes Receita Federal companies,
establishments, official reference dimensions, and municipality geography through a local,
quality-gated atomic silver bundle with compact monthly history.

Atlas uses a medallion-style raw -> bronze -> silver -> gold -> serving/index data flow. See [Data layers](docs/manual/data_layers.md) for each layer contract and current Receita examples.

## Current scope

`apps/etl` acquires immutable monthly source packages, writes release-scoped bronze, normalizes
companies and establishments, resolves official references and TOM-to-IBGE municipality
geography, records compact history, and atomically publishes coherent silver generations.
Full-data acceptance remains operator-run. `Simples`, `Socios`, corporate graphs, CNAE business
groups, gold products, exports, serving, API, UI, AI, sanctions, and procurement remain roadmap
items.

## Atlas local CLI

From the repository root:

```bash
./atlas help
./atlas version
./atlas download receita company-data --release 2026-05
./atlas download receita estabelecimentos --release 2026-05
./atlas refresh receita company-data --release 2026-05
./atlas ingest receita estabelecimentos
./atlas normalize receita estabelecimentos
./atlas refresh receita estabelecimentos --release 2026-07
./atlas status
./atlas storage usage
./atlas storage cleanup
./atlas releases inspect-bundle
./atlas releases validate-bundle --full
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

The implemented company-data foundation covers `Empresas`, six official Receita reference
dimensions, Receita-to-IBGE municipality geography, compact company and establishment history,
and atomic same-release publication. Acquire both source groups, then run the local-only refresh:

```bash
./atlas download receita company-data --release YYYY-MM
./atlas download receita estabelecimentos --release YYYY-MM
./atlas refresh receita company-data --release YYYY-MM
./atlas releases inspect-bundle
```

See the [unified plan](docs/atlas_unified_plan.md#delivery-roadmap), concise
[delivery record](docs/plans/receita-company-data-foundation.md), and
[operator runbook](docs/operations/company-data-pipeline.md).

## Layout

- `apps/etl/` - Scala/Spark ETL application and local data layers
- `docs/` - product direction, data contracts, user manual, operations, and roadmap decisions
- `apps/etl/data/raw/receita/` - downloaded snapshots, protected and ignored by Git

Start with the [documentation index](docs/index.md), consult the canonical [Dataset and source
catalog](docs/source_catalog.md) for the complete inventory, and use the [local ETL operations
guide](docs/operations/local-etl.md) for commands and recovery procedures. The [Atlas unified
plan](docs/atlas_unified_plan.md) remains the sole owner of product priority and sequencing.
