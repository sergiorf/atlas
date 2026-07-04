# Freshness and refresh

Atlas v0.1 uses operator-selected monthly Receita snapshots. The folder name identifies the intended snapshot month; Atlas does not currently verify publication completeness or schedule refreshes.

The default configuration points to `2026-06`. A run timestamp records when Atlas processed the files, not when Receita last changed a company record. Replace or add snapshots only through the [refresh runbook](../operations/refresh-runbook.md), preserving raw archives and interrupted `.part` downloads.
