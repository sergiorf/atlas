# Silver establishment schema

- **Status:** Implemented
- **Contract level:** Internal schema contract
- **Input:** Bronze Receita `estabelecimentos`
- **Output:** `data/silver/receita/establishments` relative to `apps/etl`
- **Primary key:** `cnpj_full`
- **Partition:** `state`

The silver table is a curated, rebuildable establishment model. It does not enrich municipality or CNAE codes and is not a public or gold contract.

| Field | Spark type | Nullable | Meaning |
| --- | --- | --- | --- |
| `cnpj_root` | string | yes | Normalized eight-digit company root from bronze |
| `cnpj_branch` | string | yes | Normalized four-digit establishment order |
| `cnpj_check` | string | yes | Normalized two-digit check component |
| `cnpj_full` | string | no | Unique fourteen-digit establishment identifier |
| `is_headquarters` | boolean | yes | Headquarters indicator inherited from bronze |
| `trade_name` | string | yes | Trimmed establishment trade name |
| `registration_status_code` | string | no | Receita registration-status code; one of `01`, `02`, `03`, `04`, or `08` |
| `is_active` | boolean | yes | True only for status `02`; null when the status is null |
| `registration_status_date` | date | yes | Registration-status date |
| `registration_status_reason` | string | yes | Registration-status reason code |
| `opening_date` | date | yes | Establishment opening date |
| `main_cnae` | string | yes | Seven-digit primary CNAE; invalid values become null |
| `secondary_cnaes` | array<string> | yes | Valid seven-digit secondary CNAEs, ordered and deduplicated; null when source is null |
| `street_type` | string | yes | Trimmed street type |
| `street_name` | string | yes | Trimmed street name |
| `street_number` | string | yes | Trimmed street number |
| `address_extra` | string | yes | Trimmed address complement |
| `neighborhood` | string | yes | Trimmed neighborhood |
| `postal_code` | string | yes | Digits-only postal code left-padded to eight positions; over-width values become null |
| `state` | string | yes | Uppercase two-letter state; invalid non-null values become null |
| `municipality_code` | string | yes | Trimmed Receita municipality code; not yet resolved to a name |
| `country_code` | string | yes | Trimmed Receita country code |
| `foreign_city_name` | string | yes | Trimmed foreign city name |
| `phone_1_area_code` | string | yes | Digits-only first DDD |
| `phone_1_number` | string | yes | Digits-only first phone number |
| `phone_2_area_code` | string | yes | Digits-only second DDD |
| `phone_2_number` | string | yes | Digits-only second phone number |
| `fax_area_code` | string | yes | Digits-only fax DDD |
| `fax_number` | string | yes | Digits-only fax number |
| `email` | string | yes | Trimmed lowercase email; syntax is not validated |
| `special_status` | string | yes | Trimmed special-status value |
| `special_status_date` | date | yes | Special-status date |
| `source_name` | string | yes | Bronze source name |
| `source_file` | string | yes | Original source file recorded by bronze |
| `ingestion_timestamp` | timestamp | yes | Bronze ingestion timestamp |
| `silver_transformation_timestamp` | timestamp | no | Timestamp assigned by the silver transformation |

Before this schema is produced, structural validation requires the CNPJ components to be exactly 8, 4, and 2 digits, `cnpj_full` to be exactly 14 digits, and the registration status to be one of `01`, `02`, `03`, `04`, or `08`. Malformed source-shaped bronze rows are written beneath `data/_atlas/quality/receita/establishments/<snapshot>/malformed_rows` and excluded. State is normalized to a valid Brazilian UF or null under the existing nullable contract, with invalid source values reported diagnostically.

The quality gate enforces unique `cnpj_full` values after malformed rows are excluded. Conflicting valid duplicates are reported beneath the snapshot quality directory and reject publication. The job does not validate the CNPJ checksum or silently deduplicate records. These stricter input gates do not change the clean silver table layout, so no stored-schema migration is required.
