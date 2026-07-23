# Synthetic company data fixtures

These UTF-8 files are invented test records, not publisher data. They exercise the positional
contracts and edge cases that must be settled before acquisition or bronze ingestion is added.
Production input encoding remains a manifest value verified against the selected publisher
release; these fixtures do not assert that Receita publishes UTF-8.

- `empresas/valid.csv` and `empresas/scenarios.csv`: semicolon parsing, quoted delimiters, numeric
  and alphanumeric roots, invalid widths and characters, blanks, and capital outcomes.
- `references/scenarios.csv`: exact duplicates, conflicting duplicates, blank codes, and blank
  descriptions.
- `geography/`: exact TOM-to-IBGE identifiers, a minimal immediate/intermediate/state/region
  hierarchy, and explicit ambiguous, unmatched, and missing-parent outcomes.
- `history/company_states.csv`: May seed plus June/July update, unchanged, and removal expectations.

The files are synthetic contract fixtures used by focused transformation and history tests.

The Python manifest tests build temporary ZIP, TOM, IBGE, and manifest fixtures at runtime. No
publisher archive, extracted member, checksum manifest, or generated data is committed here.
