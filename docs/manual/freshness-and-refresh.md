# Freshness and refresh

Atlas currently uses operator-selected monthly Receita snapshots. The folder name identifies the intended snapshot month; Atlas does not currently verify publication completeness or schedule refreshes.

The default configuration points to `2026-06`. A run timestamp records when Atlas processed the files, not when Receita last changed a company record. Acquire or resume raw snapshots with the root download command, then publish derived state through the [refresh runbook](../operations/refresh-runbook.md), preserving raw archives and interrupted `.part` downloads.

Derived generations moved to `data/_atlas/_trash` remain recoverable until an operator explicitly
runs guarded cleanup with `--force`. `./atlas storage cleanup` is the normal entry point: it
inspects trash, failed or inactive bundle generations, old bronze releases, and completed work.
Configured recovery counts protect the current and prior publication, and the default seven-day
window separates quarantine from deletion. Raw source data is never considered. See
[local ETL operations](../operations/local-etl.md) for two-step cleanup, legacy-trash
reconciliation, and the separate WSL host-compaction procedure.
