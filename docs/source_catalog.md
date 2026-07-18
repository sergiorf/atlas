# Source catalog

This catalog records source ownership, location, ingestion boundaries, and refresh assumptions. A listing does not by itself authorize implementation; the [unified plan](atlas_unified_plan.md) controls sequencing.

## Receita Federal CNPJ — Estabelecimentos

- **Status:** Implemented for bronze ingestion and silver establishment normalization
- **Publisher:** Receita Federal do Brasil
- **Official distribution:** `https://arquivos.receitafederal.gov.br/public.php/webdav` (the downloader's configured Receita WebDAV endpoint)
- **Atlas input:** Monthly `Estabelecimentos` archives extracted beneath `apps/etl/data/raw/receita/<YYYY-MM>/estabelecimentos/extracted`
- **Ownership:** Public source material remains owned and governed by its publisher; Atlas owns its code, contracts, transformations, and derived product logic.
- **Licensing and redistribution:** Confirm current publisher terms before redistributing source or derived datasets. Runtime data is not committed to Git.
- **Refresh assumption:** Monthly snapshots identified by `YYYY-MM`; refresh is currently operator-triggered, not scheduled.
- **Acquisition boundary:** The operator-triggered Atlas CLI downloads only `Estabelecimentos` archives, resumes partial transfers, extracts safely, and records a manifest and raw pipeline status. It does not schedule refreshes or modify completed source bytes.
- **Ingestion boundary:** Atlas reads only Estabelecimentos CSV files, produces source-faithful bronze Parquet, and derives the curated silver establishment table. Both derived stages emit quality reports.
- **Specification:** [Receita CNPJ](specs/datasets/receita-cnpj.md)

## Planned sources

Other Receita groups, IBGE geography, CNAE reference enrichment, sanctions, procurement, labor, trade, financial, macroeconomic, and judicial sources remain planned. See the [unified plan](atlas_unified_plan.md#dataset-strategy-and-sequencing). Add ownership, URLs, licensing review, refresh expectations, and an approved dataset specification here before implementing any of them.
