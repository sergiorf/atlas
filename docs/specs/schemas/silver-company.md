# Silver company schema

- **Status:** Implemented
- **Owner:** Receita company silver transformation
- **Contract level:** Internal contract
- **Input target:** bronze Receita `empresas` plus versioned reference dimensions
- **Output target:** bundle-relative `data/silver/receita/companies_current`
- **Primary key:** `cnpj_root`
- **Partition target:** none

The bundle workflow produces this table within the immutable generation selected by
`current_bundle.json`. Silver is not a public product.

| Field | Spark type | Nullable | Meaning |
| --- | --- | --- | --- |
| `cnpj_root` | string | no | Canonical `[0-9A-Z]{8}` company identifier |
| `legal_name` | string | yes | Trimmed registered name |
| `legal_nature_code` | string | yes | Preserved Receita code |
| `legal_nature_description` | string | yes | Exact reference-table description |
| `responsible_qualification_code` | string | yes | Preserved Receita code |
| `responsible_qualification_description` | string | yes | Exact reference-table description |
| `share_capital` | decimal(20,2) | yes | Parsed non-negative share capital |
| `company_size_code` | string | yes | Preserved Receita size code |
| `responsible_federative_entity` | string | yes | Trimmed source value |
| `source_file` | string | no | Bronze lineage |
| `ingestion_timestamp` | timestamp | no | Bronze processing time |
| `silver_transformation_timestamp` | timestamp | no | Silver processing time |
| `release` | string | no | Operator-selected `YYYY-MM` |
| `record_hash` | string | no | Deterministic hash of business fields |

Unknown optional reference codes remain present with a null description and are reported. Invalid
roots, negative or unparseable capital, and duplicate valid roots are quarantined; duplicates
reject publication rather than being silently selected. The table does not join establishments,
derive a company profile, or retain full historical snapshots.

Unknown optional reference codes do not reject the bundle because Receita may retain a code in
`Empresas` that is absent from the same release's reference file. Each affected company is written
to the bundle-relative quality diagnostic
`data/_atlas/quality/receita/company-data/YYYY-MM/missing_reference_descriptions` with
`cnpj_root`, `dimension`, `code`, and `release`. Blank or conflicting rows inside a reference
dimension remain hard failures.
