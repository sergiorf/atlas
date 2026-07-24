# Company change events and release summaries

- **Status:** Implemented
- **Owner:** Receita company refresh workflow
- **Contract level:** Internal history contract
- **Event output target:** bundle-relative `data/silver/receita/company_change_events/to_release=YYYY-MM`
- **Summary output target:** bundle-relative `data/silver/receita/company_release_summaries/to_release=YYYY-MM`

These tables are implemented within atomic bundle generations.

Company events mirror the compact establishment-history pattern. Each event has a deterministic
`event_id`, `cnpj_root`, nullable `from_release`, non-null `to_release`, `change_type` (`inserted`,
`updated`, or `removed`), nullable `change_reason`, `changed_fields` structs containing JSON
old/new values, and `detected_at`. Tracked business fields are legal name, legal nature,
responsible qualification, share capital, size, and responsible federative entity; provenance
timestamps do not trigger events.

For removed events, `change_reason = source_absent` means the root was absent from both accepted
and duplicate-quarantined rows. `change_reason = quality_quarantine` means the root was present in
the release but all of its source rows were excluded by the duplicate-root policy. Inserted and
updated events have a null reason. A removed event describes departure from accepted current
state; it does not assert legal closure.

The May release seeds current company state without emitting one inserted event per company. June
compares May to June and July compares June to July. Calendar gaps remain legal when the actual
previous published release is recorded. Each attempted published release receives one durable
summary containing releases, row counts, inserted/updated/removed counts, malformed and duplicate
counts, duplicate-key counts, reference-miss counts, bundle identifier, outcome, and processing
timestamp. `duplicate_count` counts quarantined source rows; `duplicate_key_count` counts distinct
roots omitted from current.

History is append-only within an active bundle generation. Rebuild stages a complete chronological
replacement and swaps it only after every release passes validation.
