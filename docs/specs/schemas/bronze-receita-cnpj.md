# Bronze Receita estabelecimentos schema

- **Status:** Implemented
- **Contract level:** Internal schema contract
- **Output:** `data/bronze/receita/estabelecimentos` relative to `apps/etl`
- **Partition:** `state`

Bronze retains all raw fields after trimming and blank-to-null conversion. Most remain nullable strings. Three source fields become nullable Spark `date` values:

- `registration_status_date`
- `opening_date`
- `special_status_date`

Atlas normalizes `cnpj_root`, `cnpj_branch`, and `cnpj_check` as nullable digit-only strings padded to widths 8, 4, and 2. It appends:

| Field | Spark type | Nullable | Meaning |
| --- | --- | --- | --- |
| `cnpj_full` | string | yes | Concatenated normalized root, branch, and check components when all are present |
| `is_headquarters` | boolean | yes | True only when source headquarters/branch code equals `1`; null when the source code is null |
| `source_name` | string | no | Constant `receita_cnpj_estabelecimentos` |
| `source_file` | string | no for file input | Spark input filename |
| `ingestion_timestamp` | timestamp | no | Processing timestamp assigned by the job |

The writer uses the configured write mode (default `overwrite`). This schema is not a public API or gold contract. Any semantic, type, field, key, or partition change requires affected tests and documentation; consumers must coordinate migrations.
