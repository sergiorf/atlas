# Bronze Receita empresas schema

- **Status:** Planned
- **Owner:** future Receita company bronze transformation
- **Contract level:** Internal design target
- **Output target:** `data/bronze/receita/empresas/release=YYYY-MM`
- **Partition target:** none unless full-data evidence requires one

This schema and path are not implemented or currently runnable.

Bronze retains the seven source fields from the raw layout after trimming and blank-to-null
conversion. It adds `share_capital` while retaining `share_capital_raw`, so the target contains
eight business/source columns. `cnpj_root`, reference codes, company-size code, and responsible
federative entity remain nullable strings. `share_capital` is a nullable Spark `decimal(20,2)`
parsed from the publisher decimal comma. Blank capital produces null; malformed, overflowing, or
negative capital remains diagnosable and blocks later publication rather than being coerced to
zero.

Bronze appends non-null `source_name = receita_cnpj_empresas`, `source_file`,
`ingestion_timestamp`, and `release`. CNPJ roots are uppercased, display punctuation is removed,
and invalid source-shaped values are preserved for the silver quality gate. Atlas does not
left-pad ambiguous under-width roots until official evidence and fixtures establish that behavior.
Writes are release-scoped and rebuildable from the raw manifest.

`CompanyDataSchemas.empresasBronze` is currently an executable shape assertion only. No Atlas job
produces this schema or path.
