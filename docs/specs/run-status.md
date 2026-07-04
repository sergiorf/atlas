# Atlas run-status registry contract

- **Status:** Implemented for Receita `estabelecimentos` bronze ingestion
- **Owner:** `apps/etl/src/main/scala/atlas/status`
- **Contract version:** `1`

Atlas records the latest attempted ETL run for each source, dataset, snapshot, and layer as one UTF-8 JSON file. Paths are relative to `apps/etl`:

```text
data/_atlas/status/<source>/<dataset>/<snapshot>/<layer>.json
```

For example, Receita `estabelecimentos` bronze for June 2026 is recorded at `data/_atlas/status/receita/estabelecimentos/2026-06/bronze.json`. A later attempt for the same identity replaces the earlier file. Status metadata is operational state, not a data layer or source of business facts.

## JSON fields

| Field | JSON type | Required | Meaning |
| --- | --- | --- | --- |
| `source` | string | yes | Stable source identifier, currently `receita` |
| `dataset` | string | yes | Stable dataset identifier, currently `estabelecimentos` |
| `snapshot` | string | yes | Operator-configured source snapshot, currently `2026-06` |
| `layer` | string | yes | Layer attempted, currently emitted only for `bronze` |
| `status` | string | yes | Exactly `success` or `failed` |
| `started_at` | string | yes | Run start as an ISO-8601 UTC instant |
| `finished_at` | string | yes | Run finish as an ISO-8601 UTC instant |
| `duration_seconds` | number | yes | Non-negative elapsed wall-clock seconds |
| `row_count` | integer | no | Produced/evaluated rows when known |
| `input_paths` | array of strings | yes | Declared input paths or globs |
| `output_path` | string | no | Intended or produced output location |
| `partition_columns` | array of strings | yes | Physical output partition columns; empty when not applicable |
| `schema_version` | string | no | Version of this job's output schema when declared |
| `application_name` | string | no | Runtime application name |
| `job_name` | string | no | Stable command or job identifier |
| `error_type` | string | failed runs | Fully qualified exception type when available |
| `error_message` | string | no | Exception message when available; may be absent for exceptions without a message |

Writers create parent directories. Strings use JSON escaping. Readers accept absent optional fields. The CLI reports malformed individual files and continues listing valid records.

## Publication and failure behavior

The bronze job writes `success` only after Parquet and quality reports have been written. Its status includes the evaluated row count, output `data/bronze/receita/estabelecimentos`, and partition column `state`. If the run throws after metadata is known, it makes a best-effort write of `failed` and rethrows the original exception. A registry-write error is attached to the original failure rather than replacing it.

The registry does not prove that an output still exists or is complete; it reports the latest recorded attempt. Missing means no run was recorded. No files are created for unimplemented gold, serving/index, or dashboard work.

Changing field meaning, identity/path conventions, required fields, status values, or replacement semantics requires compatibility analysis and a contract-version decision. Generated registry files remain local and are ignored by Git.
