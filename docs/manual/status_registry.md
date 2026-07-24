# Local run-status registry

The local status registry reports which dataset snapshot and layer last ran, whether it succeeded, when it finished, how many rows were known, and where its output belongs. It gives future operational tooling and a future dashboard a stable file contract without introducing a database or web application.

Status follows the implemented pipeline structure:

```text
raw -> bronze -> silver latest current
                  |-> change events
                  `-> release summaries
                         |
                         `-> atomic bundle

future: gold -> serving/index
```

Receita establishments and company data have implemented raw acquisition, bronze and silver
transformations, compact history, release summaries, and atomic bundle publication. Instrumented
jobs record raw, component, and bundle status. Candidate component evidence remains inside its
generation until successful atomic publication activates canonical records. Release summaries are
durable analytical data rather than status records. Gold,
serving/index, and a web dashboard remain roadmap work, so their absence is not a failed run.

From `apps/etl`, list recorded runs with:

```bash
sbt "runMain atlas.Main status"
```

From the repository root, use:

```bash
./atlas status
./atlas status --json
```

The command reads `data/_atlas/status` and prints two human-readable sections. `DATA PIPELINE`
contains component stages and measured row, file, change, quarantine, and warning evidence.
`ATOMIC PUBLICATION` contains only bundle outcome, finish time, generation path, and a concise
failure message. Existing establishment dataset spellings are displayed uniformly as
`establishments`; stored JSON identities do not change. An absent quarantine measurement displays
as `-`, while a measured zero displays as `0`. `--json` remains the unmodified registry array.
The command starts no Spark session.

Registry files follow `data/_atlas/status/<source>/<dataset>/<snapshot>/<layer>.json`. For example: `data/_atlas/status/receita/estabelecimentos/2026-06/bronze.json`.

Current identifiers intentionally reflect existing job contracts:

| Operation | `source` | `dataset` | `layer` |
| --- | --- | --- | --- |
| Raw acquisition | `receita` | `estabelecimentos` | `raw` |
| Company-data source acquisition and verification | `receita` | `company-data` | `raw` |
| Bronze ingestion | `receita` | `estabelecimentos` | `bronze` |
| Silver normalization/publication | `receita` | `establishments` | `silver` |
| Compact change history | `receita` | `estabelecimentos_history` | `history` |
| Company bronze and silver | `receita` | `companies` | `bronze`, `silver` |
| Company change history | `receita` | `companies` | `history` |
| Company reference dimensions | `receita` | `company-references` | `bronze`, `silver` |
| Municipality geography | `receita` | `municipality-geography` | `silver` |
| Atomic company-data publication | `receita` | `company-data` | `bundle` |

The Portuguese source identifier and English silver identifier are established internal status
identities. Consumers should use the full tuple rather than infer data-layer semantics from the
dataset spelling.

Successful refreshes and full rebuilds also record `receita / establishments / <release> / silver` with `data/silver/receita/establishments_current` as the output. Rebuild status paths name active locations after promotion; the temporary rebuild UUID is never retained in activated status metadata.

An integrated download records `receita / estabelecimentos / <release> / raw`. A failed explicit
release is recorded at the same identity. If `--latest` fails before Receita reports a release,
there is no snapshot identity under which Atlas can record that attempt.

The company-data downloader records `receita / company-data / <release> / raw` only after its
source manifest validates. Failure uses the same identity and preserves diagnostics for `atlas status`.
Successful atomic publication records `receita / company-data / <release> / bundle` and points to
the immutable generation selected by `current_bundle.json`. Component rows and diagnostics are
measured during staging but become canonical only after that same generation is selected.
A failed publication points to its retained failed candidate when one was produced; embedded
component status and warning paths are rewritten to that retained location.

- `success`: the instrumented job completed its output and status publication.
- `success_with_warnings`: the layer was produced, but rows were quarantined or quality warnings were recorded; inspect the warning report paths.
- `failed`: the instrumented job failed; error details are recorded when available, and the command still exits as failed.
- missing: no status file has been recorded for that identity. This is not evidence of success or failure.
- not implemented: the layer or job is outside the current supported implementation. Atlas does not create placeholder records for future work.

A malformed file is reported separately while other valid records remain visible. Each file represents the latest recorded attempt for its source, dataset, snapshot, and layer, not an append-only run ledger or a check that output files still exist. Establishment business history is stored separately as compact change-event and release-summary Parquet under silver.

Durable analytical release metrics live in `data/silver/receita/establishment_release_summaries`; status remains latest-attempt operational metadata. Status JSON is generated operational metadata. It can contain local paths and failure messages, changes whenever jobs run, and is reproducible by running the job. It therefore remains outside Git along with raw input and generated bronze/silver data. See the [run-status contract](../specs/run-status.md) for field-level behavior.

For company silver, `malformed_companies` reports structurally invalid rows and
`duplicate_companies` reports every source row for roots that occurred more than once. Duplicate
groups do not block the bundle: all rows in the group are excluded, the remaining table is
published uniquely, and status is `success_with_warnings`. The displayed quarantine total combines
malformed and duplicate rows; each warning retains its own count and diagnostic path.
