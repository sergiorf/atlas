# Bronze Receita empresas schema

- **Status:** Planned
- **Owner:** future Receita company bronze transformation
- **Contract level:** Internal design target
- **Output target:** `data/bronze/receita/empresas/release=YYYY-MM`
- **Partition target:** none unless full-data evidence requires one

This schema and path are not implemented or currently runnable.

Bronze retains the seven source fields from the raw layout after trimming and blank-to-null
conversion. `cnpj_root`, reference codes, company-size code, and responsible federative entity
remain nullable strings. `share_capital` is a nullable Spark `decimal(20,2)` parsed by replacing the
publisher decimal comma; the original `share_capital_raw` remains available for diagnostics.

Bronze appends non-null `source_name = receita_cnpj_empresas`, `source_file`,
`ingestion_timestamp`, and `release`. CNPJ roots are uppercased, display punctuation is removed,
and under-width values are left-padded without numeric conversion; invalid or over-width values
are preserved for the silver quality gate. Writes are release-scoped and rebuildable from the raw
manifest.

