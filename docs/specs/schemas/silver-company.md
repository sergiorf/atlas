# Silver company schema

- **Status:** Planned
- **Owner:** future Receita company silver transformation
- **Contract level:** Internal design target
- **Input target:** bronze Receita `empresas` plus planned reference dimensions
- **Output target:** `data/silver/receita/companies_current`
- **Primary key:** `cnpj_root`
- **Partition target:** none

This table and path are not implemented or currently runnable. Silver is not a public product.

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

