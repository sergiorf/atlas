# Atlas CLI reference

Run commands from the repository root with `./atlas`. This reference follows the command surface
implemented by the wrapper and `atlas.Main`; `./atlas help` is the concise runtime summary.

## Safety legend

| Label | Meaning |
| --- | --- |
| Read-only | Inspects metadata or files without modifying Atlas data |
| Build-only | Changes compiled build outputs, not data |
| Network write | Downloads immutable public inputs |
| Derived write | Writes rebuildable bronze, silver, gold, reports, or exports |
| Quarantine | Moves eligible derived data into recoverable Atlas trash |
| Permanent delete | Irreversibly deletes eligible trash after safety checks |

Common pipeline options are `--release YYYY-MM`, `--config PATH`, and `--memory SIZE`. A release
selects the snapshot and the matching dated segment in configured paths. Memory values use JVM
notation such as `10G` or `8192M`.

## Find help and build Atlas

### `help`

**Read-only.** Prints the concise command summary.

```bash
./atlas help
```

### `version`

**Read-only.** Prints the local CLI and development ETL version.

```bash
./atlas version
```

### `compile`

**Build-only.** Runs `sbt compile` in `apps/etl`.

```bash
./atlas compile
```

### `test`

**Build-only.** Runs the complete Scala test suite with `sbt test`.

```bash
./atlas test
```

Use [Build and test Atlas](../development/building.md) for focused Scala and Python tests.

### `clean`

**Build-only.** Runs `sbt clean`. It does not remove Atlas data.

```bash
./atlas clean
```

## Acquire source data

### `download receita company-data`

**Network write.** Downloads or resumes one explicit release of `Empresas`, `Socios`, `Simples`,
the six Receita reference groups, TOM, IBGE Localities, and matching `Estabelecimentos`. It verifies
separate manifests and records success only after the complete raw input set is ready.

```text
./atlas download receita company-data --release YYYY-MM
```

```bash
./atlas download receita company-data --release 2026-07
./atlas status --release 2026-07
```

This command writes raw data but no bronze, silver, or gold data. Interrupted downloads are
resumable; do not delete `.part` files casually. Use the [company-data runbook](company-data-pipeline.md)
for acceptance and recovery.

### `download receita estabelecimentos`

**Network write.** Standalone establishment acquisition for isolated operation or recovery.
Extraction is enabled by default.

```text
./atlas download receita estabelecimentos [--release YYYY-MM|--latest] [--no-extract]
```

```bash
./atlas download receita estabelecimentos --release 2026-07
./atlas download receita estabelecimentos --release 2026-07 --no-extract
```

Use `--latest` only when the operator intentionally delegates month selection to the publisher
discovery logic. Refresh never downloads implicitly.

## Run pipeline stages

### `ingest receita estabelecimentos`

**Derived write; starts Spark.** Reads configured extracted CSV for one release, writes
source-faithful bronze Parquet, and emits bronze quality and status records. Raw files are unchanged.

```bash
./atlas ingest receita estabelecimentos --release 2026-07
```

Use this stage independently for parser or bronze diagnostics. Routine publication should use a
refresh command.

### `normalize receita estabelecimentos`

**Derived write; starts Spark.** Reads release bronze, validates and normalizes establishments,
and writes a release-scoped silver candidate. It does not publish current data.

```bash
./atlas normalize receita estabelecimentos --release 2026-07
```

This is an advanced diagnostic stage, not a way to bypass chronological publication.

### `refresh receita estabelecimentos`

**Derived write; starts Spark.** Runs establishment ingestion and normalization, compares the
candidate with current, writes compact history and a release summary, and publishes a newer current
release after validation.

```bash
./atlas refresh receita estabelecimentos --release 2026-07 --memory 10G
```

Releases must advance chronologically. Use `--allow-legacy-current` only for a verified one-time
upgrade of legacy current data; prefer the rebuild runbook when protected raw releases exist.

### `refresh receita company-data`

**Derived write; starts Spark.** Builds the complete matching company-data release locally and
atomically publishes bronze, silver, history, relationship, gold, and quality components only after
the whole candidate passes its gates.

```bash
./atlas refresh receita company-data --release 2026-07 --memory 10G
./atlas releases validate-bundle --full
```

The command requires previously acquired raw manifests and performs no network I/O. A failed
candidate leaves the current bundle unchanged.

## Inspect status and releases

### `status`

**Read-only; does not start Spark.** Summarizes recorded snapshots and expands problems for the
newest release by default.

```text
./atlas status [--release YYYY-MM] [--verbose|--json]
```

```bash
./atlas status
./atlas status --release 2026-07
./atlas status --verbose
./atlas status --json
```

Use `--verbose` for exact timestamps and paths and `--json` for automation. Status is diagnostic;
never edit registry files to manufacture success.

### `releases list`

**Read-only.** Lists releases visible across raw, bronze, silver work, reports, and history.

```bash
./atlas releases list
```

### `releases inspect`

**Read-only.** Resolves known raw and derived paths for one release and reports existence and
protection state.

```bash
./atlas releases inspect --release 2026-07
```

### `releases inspect-bundle`

**Read-only; does not start Spark.** Shows current bundle metadata or the bundle associated with an
explicit release.

```bash
./atlas releases inspect-bundle
./atlas releases inspect-bundle --release 2026-07
```

Use this command rather than guessing generation paths.

### `releases validate-bundle`

**Read-only.** Runs structural validation by default; `--full` starts Spark and adds data-contract
checks. `--json` emits machine-readable results.

```text
./atlas releases validate-bundle [--bundle-id ID] [--full] [--json]
```

```bash
./atlas releases validate-bundle
./atlas releases validate-bundle --full
./atlas releases validate-bundle --bundle-id 2026-07-example --json
```

The report distinguishes passed, warned, failed, and skipped checks. Warnings require review but
are not silently converted into failures.

## Export gold products

### `export-leads`

**Derived write; starts Spark.** Reads only the current contracted gold lead product, applies
bounded filters, and writes CSV or Parquet plus a sibling manifest.

```text
./atlas export-leads --group GROUP --output PATH
  [--state UF] [--municipality-code CODE]
  [--opened-from YYYY-MM-DD] [--opened-before YYYY-MM-DD]
  [--format csv|parquet] [--limit N] [--force]
```

```bash
./atlas export-leads \
  --group software_services \
  --state PE \
  --municipality-code 2531 \
  --opened-from 2026-07-01 \
  --opened-before 2026-08-01 \
  --format csv \
  --limit 100000 \
  --output /tmp/atlas-recife-software-leads
```

`--opened-before` is exclusive. The limit must be between 1 and 1,000,000. Existing output is
rejected unless `--force` is explicit; forced replacement moves the previous output aside. The
result is a Spark output directory, not a single file. See [Company products and lead exports](company-products.md).

## Rebuild and release recovery

All rebuild and drop commands preserve raw inputs. Preview destructive operations first.

### `releases rebuild-establishments`

**Dry-run by default; derived replacement with `--force`.** Recreates all generated establishment
state for the inclusive range from protected raw releases, validates the staged replacement, and
activates it only after success.

```bash
./atlas releases rebuild-establishments --from-release 2026-05 --to-release 2026-07
./atlas releases rebuild-establishments --from-release 2026-05 --to-release 2026-07 --force --memory 10G
```

The previous active generation is quarantined for recovery. See the [refresh and rebuild runbook](refresh-runbook.md).

### `releases rebuild-company-data`

**Dry-run by default; derived replacement with `--force`.** Rebuilds complete company-data bundles
chronologically across an inclusive raw-release range and publishes only fully validated bundles.

```bash
./atlas releases rebuild-company-data --from-release 2026-05 --to-release 2026-07
./atlas releases rebuild-company-data --from-release 2026-05 --to-release 2026-07 --force --memory 10G
```

### `releases drop-derived`

**Dry-run by default; quarantine with `--force`.** Plans or quarantines one release's eligible
derived paths. Layers are `bronze`, `silver`, `reports`, `history`, and `all-derived`.

```bash
./atlas releases drop-derived --release 2026-07 --layer bronze --dry-run
./atlas releases drop-derived --release 2026-07 --layer bronze --force
```

Raw paths and protected active state are refused.

### `releases drop-stale-derived`

**Dry-run by default; quarantine with `--force`.** Finds recognized legacy derived layouts that no
longer belong to the current contract.

```bash
./atlas releases drop-stale-derived --dry-run
./atlas releases drop-stale-derived --force
```

It protects raw data, current silver, and compact history.

### `releases purge-trash`

**Dry-run by default; permanent delete with `--force`.** Deletes only quarantined generations that
pass independent recovery and age checks. The default recovery window is seven days.

```bash
./atlas releases purge-trash
./atlas releases purge-trash --older-than-days 7 --force
```

Use unified storage cleanup for routine lifecycle work. Never manually delete blocked trash.

## Storage and cleanup

### `storage usage`

**Read-only.** Streams an inventory without following symbolic links and reports apparent bytes,
file counts, protection, paths, and guarded next steps.

```text
./atlas storage usage [--category CATEGORY] [--release YYYY-MM] [--top N] [--json]
```

```bash
./atlas storage usage --top 30
./atlas storage usage --category raw
./atlas storage usage --release 2026-07
./atlas storage usage --json
```

Release attribution is path-based; unversioned files are not forced into a release.

### `storage cleanup`

**Dry-run by default; quarantine and permanent-delete phases with `--force`.** Applies guarded
policy to existing trash, inactive bundles, old bronze, and completed work. Raw data and active or
required recovery state are protected.

```text
./atlas storage cleanup
  [--older-than-days N] [--work-older-than-days N]
  [--retain-bundles N] [--retain-bronze-releases N]
  [--include KINDS] [--dry-run|--force] [--json]
```

```bash
./atlas storage cleanup
./atlas storage cleanup --older-than-days 0 --force
./atlas storage cleanup --include inactive-bundles,bronze,work --retain-bundles 2
```

One forced run can permanently delete previously eligible trash and quarantine newly eligible live
data. Newly quarantined data is retained until a later invocation. `--json` is inspection-only and
cannot be combined with `--force`.

### `storage reconcile-trash`

**Dry-run by default; metadata repair with `--force`.** Validates recognized legacy rebuild trash
and writes missing recovery manifests. It does not delete data.

```bash
./atlas storage reconcile-trash
./atlas storage reconcile-trash --force
```

Run cleanup afterward; reconciliation does not bypass retention or eligibility rules.

### `storage reclaim --prepare-wsl`

**Read-only.** Checks whether Linux cleanup is complete and prints the separate Windows-side VHD
compaction step. `--json` is available for automation.

```bash
./atlas storage reclaim --prepare-wsl
./atlas storage reclaim --prepare-wsl --json
```

The command does not compact a disk. The Windows helper defaults to dry-run; its forced mode shuts
down WSL and is documented in [Storage cleanup](../specs/storage-cleanup.md#wsl-host-space-reclamation).

## Choosing the right command

| Goal | Command |
| --- | --- |
| See available commands | `./atlas help` |
| Build or test code | `./atlas compile`, `./atlas test` |
| Acquire a complete monthly source set | `download receita company-data` |
| Publish the normal monthly company-data release | `refresh receita company-data` |
| Inspect current health | `status`, then `releases validate-bundle` |
| Export a contracted lead list | `export-leads` |
| Diagnose one establishment stage | `ingest` or `normalize` |
| Recreate derived data from protected raw releases | `releases rebuild-*` |
| Understand disk use | `storage usage` |
| Reclaim eligible derived storage | `storage cleanup` |

For failures and recovery choices, use [Troubleshooting](troubleshooting.md).
