# Company and geography quality rules

- **Status:** Planned
- **Owner:** future company-spine quality gate
- **Behavior:** Bundle-blocking gates plus diagnostics

These rules are not implemented and do not describe current pipeline behavior.

The planned company gate requires unique canonical `cnpj_root`, parseable non-negative share
capital when present, one release value, and complete required provenance. It reports malformed
roots, duplicate roots, capital parse failures, null business fields, unknown legal-nature and
qualification codes, row counts, and input/output paths. Any malformed root, conflicting duplicate,
or capital parse failure blocks publication; optional reference misses are reported and evaluated
against an explicitly configured threshold that defaults to zero for the May–July backfill.

Reference dimensions require non-empty unique codes and non-empty descriptions. Geography requires
unique TOM and IBGE municipality mappings, valid seven-digit IBGE codes, valid UF/region parents,
and exact referential coverage for every non-null municipality code used by the candidate
establishment bundle. Conflicts, ambiguity, or uncovered used codes block publication.

Bundle quality also checks required raw groups and manifests, release agreement, record counts,
history arithmetic, output readability, and consistency between each current table and its release
summary. Diagnostics are written before rejection. No rule edits raw inputs or silently drops a
valid source-shaped record.

