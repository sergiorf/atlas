# Atlas documentation

Atlas documentation separates product direction, implemented contracts, user guidance, operations, and planned work. An active specification describes implemented behavior unless it is prominently marked `Status: Planned`; planned material is not a compatibility commitment and does not authorize implementation.

## Canonical documents

- [Atlas unified plan](atlas_unified_plan.md) — product direction, milestones, sequencing, and scope.
- [Feature development workflow](feature_development_workflow.md) — proportional planning, implementation, and verification gates.
- [Source catalog](source_catalog.md) — official inputs, ownership, licensing notes, and refresh assumptions.
- [Data product contract](data_product_contract.md) — global data-layer, lineage, reproducibility, and compatibility invariants.

## Use and operate Atlas

- [Manual](manual/index.md)
- [Data layers](manual/data_layers.md)
- [Local ETL operations](operations/local-etl.md)
- [Refresh runbook](operations/refresh-runbook.md)
- [Troubleshooting](operations/troubleshooting.md)

## Implemented specifications

- [Receita CNPJ dataset](specs/datasets/receita-cnpj.md)
- [Raw Receita estabelecimentos layout](specs/schemas/raw-receita-cnpj.md)
- [Bronze Receita estabelecimentos schema](specs/schemas/bronze-receita-cnpj.md)
- [Receita estabelecimentos quality rules](specs/quality/receita-cnpj-quality-rules.md)
- [Silver establishment schema](specs/schemas/silver-establishment.md)
- [Silver establishment quality rules](specs/quality/silver-establishment-quality-rules.md)

## Planned work

Future datasets, gold tables, serving, graph, and AI designs live under [`roadmap/`](roadmap/release-roadmap.md). They describe direction rather than supported behavior.
