# Atlas documentation

Atlas documentation separates product direction, implemented contracts, user guidance, operations, and planned work. An active specification describes implemented behavior unless it is prominently marked `Status: Planned`; planned material is not a compatibility commitment and does not authorize implementation.

## Canonical documents

- [Atlas unified plan](atlas_unified_plan.md) — product direction, milestones, sequencing, and scope.
- [Feature development workflow](feature_development_workflow.md) — proportional planning, implementation, and verification gates.
- [Dataset and source catalog](source_catalog.md) — canonical inventory of supported, planned, and candidate datasets, with official inputs, ownership, access, licensing notes, and refresh assumptions.
- [Data product contract](data_product_contract.md) — global data-layer, lineage, reproducibility, and compatibility invariants.

## Learn and inspect Atlas

- [Manual](manual/index.md)
- [Getting started](manual/getting-started.md)
- [Data layers](manual/data_layers.md)
- [Local run-status registry](manual/status_registry.md)
- [Querying Atlas](manual/querying-atlas.md)

## Operate Atlas

- [Local ETL operations](operations/local-etl.md)
- [Refresh runbook](operations/refresh-runbook.md)
- [Troubleshooting](operations/troubleshooting.md)

## Implemented specifications

- [Receita CNPJ dataset](specs/datasets/receita-cnpj.md)
- [Raw Receita estabelecimentos layout](specs/schemas/raw-receita-cnpj.md)
- [Bronze Receita estabelecimentos schema](specs/schemas/bronze-receita-cnpj.md)
- [Receita estabelecimentos quality rules](specs/quality/receita-cnpj-quality-rules.md)
- [Silver establishment schema](specs/schemas/silver-establishment.md)
- [Establishment change-event schema](specs/schemas/establishment-change-events.md)
- [Establishment release-summary schema](specs/schemas/establishment-release-summaries.md)
- [Silver establishment quality rules](specs/quality/silver-establishment-quality-rules.md)
- [Run-status registry contract](specs/run-status.md)

## Planned specifications

These design targets are unsupported, are not compatibility commitments, and do not authorize
implementation:

- [Receita company and reference sources](specs/datasets/receita-company-reference-sources.md)
- [Raw Receita empresas layout](specs/schemas/raw-receita-empresas.md)
- [Bronze Receita empresas schema](specs/schemas/bronze-receita-empresas.md)
- [Silver company schema](specs/schemas/silver-company.md)
- [Company change events and release summaries](specs/schemas/company-change-history.md)
- [Receita reference dimensions](specs/schemas/receita-reference-dimensions.md)
- [Receita-to-IBGE municipality geography](specs/schemas/receita-ibge-municipality-geography.md)
- [Company and geography quality rules](specs/quality/company-geography-quality-rules.md)
- [Company-spine silver-foundation implementation plan](plans/receita-company-spine-silver-foundation.md)

## Planned work

The [roadmap pointer](roadmap/release-roadmap.md) leads to the unified plan, which is the only
owner of future sequencing. Planned material describes direction rather than supported behavior.
