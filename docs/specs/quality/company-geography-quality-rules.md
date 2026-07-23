# Company and geography quality rules

- **Status:** Implemented
- **Owner:** company data quality gate
- **Behavior:** Bundle-blocking gates plus diagnostics

These rules govern atomic bundle publication.

| Rule | Stage | Severity and behavior |
| --- | --- | --- |
| `company_root_invalid` | Silver candidate | Quarantine row and block bundle publication |
| `company_root_conflict` | Silver candidate | Report bounded conflicting samples and block publication |
| `share_capital_invalid` | Bronze/silver candidate | Preserve raw value, diagnose, and block publication |
| `reference_code_empty` | Reference candidate | Quarantine row and block publication |
| `reference_code_conflict` | Reference candidate | Report bounded conflicting samples and block publication |
| `company_reference_missing` | Silver candidate | Report by dimension and block May–July publication; default threshold is zero |
| `tom_mapping_ambiguous` | Geography candidate | Block publication; name matching is forbidden |
| `ibge_parent_missing` | Geography candidate | Block publication except for the exact official exterior sentinel |
| `municipality_coverage_missing` | Bundle candidate | Block when a non-null used establishment code is uncovered |
| `source_release_mismatch` | Preflight | Stop before Spark work |

The company gate also requires one release value and complete provenance. Diagnostics include rule
identifier, total count, bounded representative evidence where a generated quarantine is applicable,
input paths, candidate output path, and release. No rule may collect an unbounded dataset locally.

Reference dimensions require non-empty unique codes and non-empty descriptions. Exact duplicate
rows may collapse only with an explicit count; conflicting descriptions block publication.
Geography requires unique TOM and IBGE municipality mappings, valid seven-digit IBGE codes, valid
UF/region parents, and exact referential coverage for every non-null municipality code used by the
candidate establishment bundle. The one contracted exception is the official TOM
`9707 / 0 / EXTERIOR / EX` record: it remains joinable and has null Brazilian hierarchy fields.
Conflicts, ambiguity, any other missing parent, or uncovered used codes block publication.

Bundle quality also checks required raw groups and manifests, release agreement, record counts,
history arithmetic, output readability, and consistency between each current table and its release
summary. Diagnostics are written before rejection. No rule edits raw inputs or silently drops a
valid source-shaped record.
