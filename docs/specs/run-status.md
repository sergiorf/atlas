# Atlas run-status registry contract

- **Status:** Implemented for Receita establishment and atomic company-data pipeline components
- **Owner:** `apps/etl/src/main/scala/atlas/status`
- **Contract version:** `4` (additive raw-file metrics; versions 1 through 3 remain readable)

Atlas records the latest attempted ETL run for each source, dataset, snapshot, and layer as one UTF-8 JSON file. The root CLI calls the same operator-selected `snapshot` a `release`. Paths are relative to `apps/etl`:

```text
data/_atlas/status/<source>/<dataset>/<snapshot>/<layer>.json
```

For example, Receita `estabelecimentos` bronze for June 2026 is recorded at `data/_atlas/status/receita/estabelecimentos/2026-06/bronze.json`. A later attempt for the same identity replaces the earlier file. Status metadata is operational state, not a data layer or source of business facts.

## JSON fields

| Field | JSON type | Required | Meaning |
| --- | --- | --- | --- |
| `source` | string | yes | Stable source identifier, currently `receita` |
| `dataset` | string | yes | Stable layer-specific identifier, including existing establishment identities plus `companies`, `company-references`, `municipality-geography`, and bundle `company-data` |
| `snapshot` | string | yes | Operator-configured source snapshot, currently `2026-06` |
| `layer` | string | yes | Layer attempted: `raw`, `bronze`, `silver`, `history`, or atomic `bundle` |
| `status` | string | yes | `success`, `success_with_warnings`, or `failed` |
| `started_at` | string | yes | Run start as an ISO-8601 UTC instant |
| `finished_at` | string | yes | Run finish as an ISO-8601 UTC instant |
| `duration_seconds` | number | yes | Non-negative elapsed wall-clock seconds |
| `row_count` | integer | no | Produced/evaluated rows when known |
| `input_row_count` | integer | no | Rows evaluated by the layer |
| `output_row_count` | integer | no | Rows published by the layer |
| `quarantined_row_count` | integer | no | Rows excluded into generated quality output |
| `quality_warnings` | array | no | Warning objects containing `type`, `row_count`, `reason`, and `report_path` |
| `previous_row_count` | integer | no | Previous current total; absent for a seed or when unknown |
| `net_row_delta` | integer | no | Current total minus previous total |
| `inserted_row_count` | integer | no | Inserted establishment events |
| `updated_row_count` | integer | no | Updated establishment events |
| `removed_row_count` | integer | no | Removed establishment events |
| `file_count` | integer | no | Completed source archives for a raw acquisition |
| `byte_count` | integer | no | Total bytes across completed source archives |
| `extracted_file_count` | integer | no | Archives safely extracted during the raw acquisition |
| `input_paths` | array of strings | yes | Declared input paths or globs |
| `output_path` | string | no | Intended or produced output location |
| `partition_columns` | array of strings | yes | Physical output partition columns; empty when not applicable |
| `schema_version` | string | no | Version of this job's output schema when declared |
| `application_name` | string | no | Runtime application name |
| `job_name` | string | no | Stable command or job identifier |
| `error_type` | string | failed runs | Fully qualified exception type when available |
| `error_message` | string | no | Exception message when available; may be absent for exceptions without a message |

`success` means the layer was produced without quality warnings. `success_with_warnings` means the layer was produced but rows were quarantined or another quality warning was recorded. `failed` means the layer was not published. Writers create parent directories. Strings use JSON escaping. Readers accept absent optional fields, including warning fields in older records. The CLI reports malformed individual files and continues listing valid records.

## Publication and failure behavior

The downloader writes raw `success` only after every selected archive is complete, requested
extraction is complete, and the manifest is current. An explicit-release failure records `failed`.
A latest-release discovery failure cannot be assigned a snapshot and therefore cannot create a
snapshot-scoped record.

The bronze job writes `success` only after Parquet and quality reports have been written. Standalone silver normalization writes a release-scoped candidate and records `success_with_warnings` when malformed rows were quarantined; it does not publish current. Company-data refresh measures company bronze, aggregated reference bronze and silver, municipality-geography silver, company silver, and company history. Malformed or duplicate company failures include warning evidence and a diagnostic path; missing reference descriptions are a non-blocking `success_with_warnings`.

Bundle component records are first written inside the candidate generation. After the complete
generation passes validation and the atomic pointer is readable, Atlas rewrites staged paths to
the immutable generation and activates the component records together with bundle success. An
activation failure restores the previous pointer and component status files. A failed candidate
therefore cannot replace active component status; its diagnostics remain with the retained
candidate generation with canonical retained paths, while the canonical bundle identity records
the failed publication attempt and uses `output_path` for that retained candidate when available.
The same staged-then-activate rule applies to full rebuild. If a job throws after metadata is
known, it makes a best-effort write of `failed` and rethrows the original exception.

The registry does not prove that an output still exists or is complete; it reports the latest recorded attempt. History component `row_count` and `output_row_count` are event counts; silver component counts are produced rows. Durable analytical metrics live in the silver release-summary datasets. Missing means no run was recorded. No files are created for unimplemented gold, serving/index, or dashboard work.

Changing field meaning, identity/path conventions, required fields, status values, or replacement semantics requires compatibility analysis and a contract-version decision. Generated registry files remain local and are ignored by Git.
