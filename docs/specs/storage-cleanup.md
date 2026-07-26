# Unified storage cleanup

- **Status:** Implemented
- **Contract version:** 1

`./atlas storage cleanup` is the recommended read-only-by-default entry point for reclaimable
Atlas storage. It combines permanent-deletion decisions for existing `_trash` generations with
quarantine decisions for failed company bundle candidates. Raw data and active bundle generations
are outside its candidate inventory and are never modified.

## Command contract

```bash
./atlas storage cleanup
./atlas storage cleanup --older-than-days 7 --force
./atlas storage cleanup --json
```

Dry-run and seven full days are the defaults. `--older-than-days` accepts a non-negative integer.
`--json` is versioned inspection output and cannot be combined with `--force`.

Force acquires the company-bundle lock, invokes guarded trash deletion under the establishment
lock, rescans failed candidates, and atomically moves eligible candidates into timestamped trash.
Trash is purged before failed candidates are moved, so newly quarantined data is never permanently
deleted in the same invocation, including with a zero-day window. A second dry run and force
invocation are required to delete it.

## Failed bundle eligibility

A failed bundle is eligible only when it is a direct directory beneath
`data/_atlas/bundles/failed`, has the recognized `YYYY-MM-UUID` identity, contains no symbolic
links, remains inside the configured failed root, has readable internal statuses or a valid bundle
manifest yielding a trustworthy timestamp, is old enough, and is not referenced by the current
bundle pointer, active status metadata, or an active transaction journal. Malformed active status
metadata blocks every failed-bundle move because reference safety cannot be proven.

Eligible candidates move atomically to
`data/_atlas/_trash/<timestamp>/failed-company-bundle-<bundle-id>` and receive an
`.atlas-trash-manifest.json` with operation type `failed-company-bundle`. Existing
`releases purge-trash` and later `storage cleanup` invocations recognize that operation.
Cross-filesystem copy fallback is intentionally unsupported.

The human report separates bytes eligible for deletion, eligible for quarantine, actually
deleted, and actually quarantined. Quarantine on the same filesystem does not reclaim space.
Filesystem or manifest-write failures return a nonzero exit; already completed permanent
deletions cannot be rolled back.

Work directories, arbitrary staging layouts, inactive published generations, build caches, and
WSL VHDX compaction are outside contract version 1.
