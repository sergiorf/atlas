# Unified storage cleanup

- **Status:** Implemented
- **Contract version:** 2

`./atlas storage cleanup` is the read-only-by-default lifecycle entry point for reclaimable Atlas
storage. Contract version 2 inventories existing trash, failed company bundles, inactive published
bundle generations, old establishment bronze releases, and completed establishment work. Raw data,
the current bundle, the retained recovery generation, active silver and history, and metadata are
never cleanup candidates.

## Command contract

```bash
./atlas storage cleanup
./atlas storage cleanup --older-than-days 7 --force
./atlas storage cleanup --include inactive-bundles,bronze,work
./atlas storage cleanup --retain-bundles 2 --retain-bronze-releases 1
./atlas storage cleanup --work-older-than-days 2 --json
```

Dry-run is the default. Configuration defaults live under `atlas.storage.cleanup`: seven days for
ordinary candidates and trash, two days for work, two retained bundle generations, and one retained
bronze release. CLI values override those defaults for one invocation. `--include` accepts
`trash`, `failed-bundles`, `inactive-bundles`, `bronze`, and `work`; `all-derived` selects all five.
At least two bundle generations must be retained. `--json` is inspection-only and cannot accompany
`--force`.

Force acquires the company-bundle lock and then the establishment-publication lock, purges eligible
pre-existing trash, rescans all live candidates under both locks, and atomically moves eligible
live candidates into timestamped trash. Newly quarantined data is never deleted in the same
invocation, including with a zero-day window. A later dry run and force invocation are required for
permanent deletion. Cross-filesystem copy fallback is unsupported.

## Eligibility and reachability

Every candidate is measured without following symbolic links and fails closed on path escape,
unreadable metadata, unknown identity or layout, future or unknown timestamps, active transaction
references, or insufficient age. JSON provides stable `blocking_reason_codes` alongside operator
messages.

Failed bundles retain the version-1 rules: exact `YYYY-MM-UUID` identity, trustworthy internal
status or manifest time, and no current-pointer, status, transaction, or symbolic-link reference.

Published bundle generations are parsed from their manifests. The current generation is always
protected. Atlas follows its `previous_bundle_id` chain and retains the configured count; if the
chain is shorter, it fills the recovery set with the newest valid generations. This preserves the
current generation and at least one prior accepted generation even when a newly seeded product
bundle has no predecessor link. Malformed pointers or manifests block cleanup rather than turning a
generation into an orphan.

Bronze candidates must use an exact `release=YYYY-MM` directory, have successful bronze status,
and retain corresponding immutable raw input. Release ordering, not modification time, determines
the newest retained releases. Completed status remains lineage evidence and does not retain bronze
forever. An active transaction reference still blocks movement.

Work cleanup recognizes only
`data/_atlas/work/receita/estabelecimentos/release=YYYY-MM/silver_candidate`. It requires a
successful silver status and the work-specific age. New normalization runs write a sibling
`.atlas-work-manifest.json` containing release, producer, completion time, and exact output path;
cleanup validates it when present and conservatively correlates legacy work with status. Unknown
work layouts, malformed manifests, identity mismatches, and active transaction references remain
blocked.

## Trash and legacy reconciliation

Quarantine manifests use operation types `failed-company-bundle`, `inactive-bundle`, `bronze`, or
`work`. Trash deletion independently rechecks its timestamp, manifest, containment, symlinks,
active rebuild journal, replacement expectations, and canonical status evidence.

Legacy full-establishment-rebuild trash without replacement paths remains blocked. Use:

```bash
./atlas storage reconcile-trash
./atlas storage reconcile-trash --force
```

Reconciliation recognizes only the exact legacy layout, verifies every corresponding active output,
successful canonical bronze/silver/history status, absence of an active rebuild journal, and absence
of symbolic links. Force writes the missing trash manifest but deletes no data. A later cleanup
invocation applies the normal recovery window.

## Reporting and compatibility

Human output separates protected or blocked bytes, bytes eligible for quarantine, bytes eligible
for permanent deletion, and bytes actually moved or deleted. JSON contract version 2 adds policy,
candidate identity, candidate kind, and stable blocker codes. Consumers of JSON contract version 1
must upgrade; existing command defaults and the two-stage failed-bundle behavior remain compatible.

Filesystem failure after an earlier successful purge can produce partial completion and a nonzero
exit. Operators must inspect the next dry run; Atlas never claims rollback of permanent deletion.
Raw data is not inventoried or touched.

## WSL host-space reclamation

Linux deletion and Windows host reclamation are separate operations. After trash has been purged,
run `./atlas storage reclaim --prepare-wsl`. The read-only preflight reports Linux filesystem space,
remaining Atlas trash, active transaction journals, likely Spark or Atlas jobs, the WSL distribution,
and the Windows command to run next.

From Windows PowerShell, `scripts/compact-atlas-wsl.ps1 -Distro NAME` is a dry run that resolves and
prints the exact registered `ext4.vhdx`. Adding `-Force` shuts down all WSL distributions and uses
`Optimize-VHD`, or `diskpart` when that cmdlet is unavailable, to compact that exact VHD. The script
never unregisters, resizes, exports, replaces, or recreates a distribution. Windows permissions and
WSL shutdown are host concerns; compaction is never invoked by Linux cleanup.
