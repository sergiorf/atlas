# Raw Receita estabelecimentos layout

- **Status:** Implemented
- **Contract level:** Source layout

Raw source bytes are immutable. Atlas assigns the following 30 headerless positions as nullable strings, in order:

| Position | Field | Position | Field |
| ---: | --- | ---: | --- |
| 1 | `cnpj_root` | 16 | `street_number` |
| 2 | `cnpj_branch` | 17 | `address_extra` |
| 3 | `cnpj_check` | 18 | `neighborhood` |
| 4 | `headquarters_branch_code` | 19 | `postal_code` |
| 5 | `trade_name` | 20 | `state` |
| 6 | `registration_status_code` | 21 | `municipality_code` |
| 7 | `registration_status_date` | 22 | `ddd_1` |
| 8 | `registration_status_reason` | 23 | `phone_1` |
| 9 | `foreign_city_name` | 24 | `ddd_2` |
| 10 | `country_code` | 25 | `phone_2` |
| 11 | `opening_date` | 26 | `fax_ddd` |
| 12 | `main_cnae` | 27 | `fax` |
| 13 | `secondary_cnaes` | 28 | `email` |
| 14 | `street_type` | 29 | `special_status` |
| 15 | `street_name` | 30 | `special_status_date` |

The delimiter defaults to `;`, encoding to `ISO-8859-1`, quote and escape to `"`, header to false, and Spark parsing mode to `PERMISSIVE`. Configuration may change delimiter or encoding; changing field order requires a dataset-contract change.
