# Company change events and release summaries

- **Status:** Implemented
- **Owner:** Receita company refresh workflow
- **Contract level:** Internal history contract
- **Event output target:** bundle-relative `data/silver/receita/company_change_events/to_release=YYYY-MM`
- **Summary output target:** bundle-relative `data/silver/receita/company_release_summaries/to_release=YYYY-MM`

These tables are implemented within atomic bundle generations.

Company events mirror the compact establishment-history pattern. Each event has a deterministic
`event_id`, `cnpj_root`, nullable `from_release`, non-null `to_release`, `change_type` (`inserted`,
`updated`, or `removed`), `changed_fields` structs containing JSON old/new values, and
`detected_at`. Tracked business fields are legal name, legal nature, responsible qualification,
share capital, size, and responsible federative entity; provenance timestamps do not trigger
events.

The May release seeds current company state without emitting one inserted event per company. June
compares May to June and July compares June to July. Calendar gaps remain legal when the actual
previous published release is recorded. Each attempted published release receives one durable
summary containing releases, row counts, inserted/updated/removed counts, malformed and duplicate
counts, reference-miss counts, bundle identifier, outcome, and processing timestamp.

History is append-only within an active bundle generation. Rebuild stages a complete chronological
replacement and swaps it only after every release passes validation.
