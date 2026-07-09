# Establishment change-event schema

- **Status:** Implemented for Receita `Estabelecimentos` release refresh
- **Output:** `data/silver/receita/establishment_change_events/to_release=YYYY-MM`
- **Input:** latest silver current table and the normalized candidate release
- **Primary key:** `event_id`

Atlas stores compact selected field deltas for release-to-release changes. It does not store full previous records. The latest full normalized establishment record remains `data/silver/receita/establishments_current`.

| Field | Spark type | Nullable | Meaning |
| --- | --- | --- | --- |
| `event_id` | string | no | Deterministic hash of source, dataset, CNPJ, target release, and change type |
| `source` | string | no | Currently `receita` |
| `dataset` | string | no | Currently `estabelecimentos` |
| `cnpj_full` | string | no | Fourteen-digit establishment identifier |
| `from_release` | string | yes | Previous release when known |
| `to_release` | string | no | Candidate release that produced the event |
| `change_type` | string | no | `inserted`, `updated`, or `removed` |
| `changed_fields` | array<struct> | no | Selected fields with `field_name`, `old_value`, and `new_value` JSON strings |
| `detected_at` | timestamp | no | Processing time when Atlas detected the change |

Tracked fields are the normalized lead-relevant establishment fields: status, opening date, CNAEs, location, address, trade name, contact fields, special status, and headquarters flag. Volatile provenance fields such as source file and processing timestamps do not trigger change events.

The first release seeds the latest current table and does not emit one insert event per establishment. History comparison is local Spark/Parquet work and must not compare raw CSV files directly.
