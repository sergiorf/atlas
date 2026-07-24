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
roots and negative or unparseable capital are quarantined. When more than one structurally valid
source row has the same `cnpj_root`, every row in that duplicate group is quarantined and no
survivor is selected, merged, or ranked. The silver stage continues with
`success_with_warnings`; the published table remains unique but can be incomplete. Absence from
this table is therefore not evidence that a legal entity closed or ceased to exist. The table does
not join establishments, derive a company profile, or retain full historical snapshots.

Duplicate source rows are written to the bundle-relative quality diagnostic
`data/_atlas/quality/receita/company-data/YYYY-MM/duplicate_companies`. It preserves all bronze
company fields and adds:

| Field | Spark type | Nullable | Meaning |
| --- | --- | --- | --- |
| `duplicate_group_size` | long | no | Number of structurally valid source rows sharing the root |
| `duplicate_business_variant_count` | long | no | Distinct normalized business-field variants in the group |
| `quality_reason` | string | no | Stable value `duplicate_cnpj_root` |

Quality metrics distinguish `duplicate_row_count`, the number of source rows quarantined, from
`duplicate_key_count`, the number of roots omitted. Malformed rows and duplicate rows are disjoint
and are not counted twice.

Unknown optional reference codes do not reject the bundle because Receita may retain a code in
`Empresas` that is absent from the same release's reference file. Each affected company is written
to the bundle-relative quality diagnostic
`data/_atlas/quality/receita/company-data/YYYY-MM/missing_reference_descriptions` with
`cnpj_root`, `dimension`, `code`, and `release`. Blank or conflicting rows inside a reference
dimension remain hard failures.
