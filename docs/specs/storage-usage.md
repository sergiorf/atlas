# Storage usage inventory

- **Status:** Implemented
- **Contract version:** 1

`./atlas storage usage` is a read-only, point-in-time inventory of configured Atlas data and Spark
temporary storage. It does not delete, move, rewrite, or open source data as records.

The scanner walks without following symbolic links and accumulates file counts and apparent byte
sizes as a stream. A file that changes during the scan can make the result approximate. Unreadable
or disappearing paths are reported as scan errors instead of being silently treated as empty.

Categories are `raw`, `bronze`, `silver`, `bundles`, `staging`, `work`, `trash`, `quality`,
`reports`, `metadata`, `spark`, and `unclassified`. Policies are deliberately conservative:
raw, active/history-capable silver, retained bundle generations, and metadata are not declared disposable.
Unknown paths are inspect-only. The displayed next step names an existing guarded command where
one applies; it is not deletion authorization.

Contract-v2 cleanup makes exact old-bronze, stale-work, failed-bundle, and inactive-bundle
candidates lifecycle-managed. Usage inventory remains conservative: it reports their containing
locations and points to `storage cleanup`, while that command performs the candidate-level
reachability, retention, raw-rebuild, status, and transaction checks.

`--release YYYY-MM` counts only files with an exact release-shaped path component, including
`YYYY-MM`, `release=YYYY-MM`, `to_release=YYYY-MM`, or a bundle identifier beginning
`YYYY-MM-`. Files that cannot be attributed by path are absent from a release-filtered result.
`--category` limits scanning to one category, and `--top N` limits only the displayed human rows.

`--json` emits one object with `contract_version`, selected filters, filesystem capacity records,
and locations containing exact `bytes`, `files`, `policy`, `action`, and `errors`. Contract
version 1 uses apparent file sizes and does not claim allocated-block or WSL VHDX file size.

The inventory can explain live Atlas files inside WSL. Reclaiming Linux files does not itself
guarantee that Windows immediately reports a smaller `ext4.vhdx`; use
`storage reclaim --prepare-wsl` and the separate Windows PowerShell helper after Linux cleanup.
