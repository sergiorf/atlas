# Establishment release-summary schema

- **Status:** Implemented for Receita `Estabelecimentos` refresh
- **Output:** `data/silver/receita/establishment_release_summaries/to_release=YYYY-MM`
- **Primary key:** `summary_id`
- **Partition:** `to_release`
- **Schema version:** `1`

Atlas writes exactly one durable analytical summary for every published release, including the seed and releases with no changes. Status files remain latest-attempt operational metadata; summaries are queryable, rebuildable release history.

| Field | Spark type | Nullable | Meaning |
| --- | --- | --- | --- |
| `summary_id` | string | no | Deterministic SHA-256 of source, dataset, seed/previous release, and target release |
| `source` / `dataset` | string | no | `receita` / `estabelecimentos` |
| `from_release` | string | yes | Previous published release; null for the seed or unknown legacy current |
| `to_release` | string | no | Release described by this row |
| `calculated_at` | timestamp | no | Summary calculation time |
| `schema_version` | string | no | Currently `1` |
| `previous_record_count` | long | yes | Previous total; null for the seed |
| `current_record_count` | long | no | Current total |
| `net_record_delta` | long | yes | Current minus previous; null for the seed |
| `inserted_count` / `updated_count` / `removed_count` | long | no | Establishment event counts |
| `event_count` | long | no | Sum of inserted, updated, and removed events |
| `state_counts` | array<struct> | no | State-sorted `state`, nullable previous/current counts, and zero-based delta; null state is explicit and states falling to zero remain |
| `changed_field_counts` | array<struct> | no | Field-name-sorted `field_name`, `count` pairs for updated events only |

Every non-seed summary satisfies `current_record_count - previous_record_count = inserted_count - removed_count`. A move between UFs changes the before/after state buckets; it is not assigned to one state. Percentages, warning thresholds, and publication-rejection thresholds are intentionally not stored or implemented.
