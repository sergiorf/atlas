# Atlas documentation

Use this page to find the canonical owner for a question. Implemented specifications describe
current behavior; planned work is not a compatibility commitment or implementation authorization.

## Start here

- [Build and test Atlas](development/building.md)
- [CLI command reference](operations/cli-reference.md)
- [Architecture](architecture.md)
- [Datasets and important fields](manual/datasets.md)
- [Query Atlas](manual/querying-atlas.md)
- [Limitations](manual/limitations.md)

## Operate Atlas

- [Company-data and atomic bundle runbook](operations/company-data-pipeline.md)
- [Refresh and rebuild runbook](operations/refresh-runbook.md)
- [Company products and lead exports](operations/company-products.md)
- [Troubleshooting](operations/troubleshooting.md)
- [Run-status registry](manual/status_registry.md)
- [Data quality](manual/data-quality.md)
- [Freshness and refresh](manual/freshness-and-refresh.md)

## Product direction and engineering rules

- [Atlas unified plan](atlas_unified_plan.md) — product objective, current boundary, and roadmap
- [Data product contract](data_product_contract.md) — layer, lineage, and compatibility invariants
- [Feature development workflow](feature_development_workflow.md) — planning and delivery gates
- [Dataset and source catalog](source_catalog.md) — supported, planned, and candidate inputs

## Implemented data contracts

### Dataset interpretation

- [Receita CNPJ establishments](specs/datasets/receita-cnpj.md)
- [Receita company and reference sources](specs/datasets/receita-company-reference-sources.md)
- [Receita Socios and Simples](specs/datasets/receita-socios-simples.md)

### Raw and bronze

- [Raw establishments](specs/schemas/raw-receita-cnpj.md)
- [Bronze establishments](specs/schemas/bronze-receita-cnpj.md)
- [Raw companies](specs/schemas/raw-receita-empresas.md)
- [Bronze companies](specs/schemas/bronze-receita-empresas.md)
- [Company source manifest](specs/schemas/receita-company-source-manifest.md)

### Silver, history, and geography

- [Silver establishments](specs/schemas/silver-establishment.md)
- [Silver companies](specs/schemas/silver-company.md)
- [Reference dimensions](specs/schemas/receita-reference-dimensions.md)
- [Receita-to-IBGE municipality geography](specs/schemas/receita-ibge-municipality-geography.md)
- [Establishment change events](specs/schemas/establishment-change-events.md)
- [Establishment release summaries](specs/schemas/establishment-release-summaries.md)
- [Company history](specs/schemas/company-change-history.md)

### Company products

- [Silver and gold company products](specs/schemas/receita-company-products.md)
- [Company-product quality rules](specs/quality/receita-company-products-quality-rules.md)
- [Company and geography quality rules](specs/quality/company-geography-quality-rules.md)
- [Establishment quality rules](specs/quality/receita-cnpj-quality-rules.md)
- [Silver establishment quality rules](specs/quality/silver-establishment-quality-rules.md)

### Operational contracts

- [Run-status registry](specs/run-status.md)
- [Storage usage](specs/storage-usage.md)
- [Storage cleanup](specs/storage-cleanup.md)
