# Receita company products

- **Status:** Implemented
- **Contract levels:** Silver internal; gold and lead export published data-product contracts
- **Release boundary:** One atomic bundle and one Receita release

`company_tax_regime_current` has one row per accepted root. Nullable Simples and MEI indicators
preserve unknown states. Duplicates and malformed rows are diagnosed and excluded; absence is not a
negative tax assertion.

`partners_current` has one row per source QSA record. It preserves source evidence, privacy class,
qualification, provenance, release, and stable record identity. It is internal.
`entry_date_raw` preserves the nullable source value. `entry_date` is a nullable date normalized
only from an eight-digit real calendar value between `1582-10-15` and the end of the declared
release month, inclusive. A value outside that contract leaves `entry_date` null without excluding
the partner row.

`partner_field_quality_issues` is an internal, release-scoped quality diagnostic with
`partner_record_id`, `source_company_cnpj_root`, `field_name`, `raw_value`, `quality_reason`,
`source_file`, and `release`. Its stable reason codes are `invalid_date_format`,
`invalid_calendar_date`, `date_before_supported_minimum`, and `date_after_release`. Blank source
dates produce no issue. The diagnostic can contain source-linked natural-person evidence and is
not a gold or export component.

`company_relationships_current` contains only deterministic legal-entity edges:

```text
source_company_cnpj_root -> participant_company_cnpj_root
```

Classes conservatively distinguish ownership/partnership interest, partner-administration,
management, legal representation, and unknown relationships. No edge asserts control or ultimate
beneficial ownership. Release observations record first, retained, or no-longer-observed status,
not legal-effective dates.

`company_profiles_current` has one row per root with legal attributes, nullable tax signals,
establishment counts, headquarters summary, relationship count, release, and rule version.

`company_partner_network_current` preserves immediate edges and adds deterministic connected
components, degree and component metrics, bounded cycle evidence, release, and calculation version.
Each connected component uses its lexicographically smallest company CNPJ root as `component_id`.
The undirected component calculation must reach a stable fixed point: after propagation stops, a
confirmation round must change zero node labels. `atlas.graph.max-component-propagation-rounds`
bounds changing rounds as an operational safeguard; the confirmation round does not consume that
allowance. Reaching the bound without stabilization fails the candidate and never publishes partial
component labels.
`company_relationship_paths_current` contains ordered node and edge paths through depth three.
Cycle search is bounded at depth six. Immediate edges remain authoritative.

`conf/cnae-groups.conf` owns taxonomy version and memberships. `leads_new_companies_current` has
one row per active establishment and matched group, with company identity, opening date, CNAE match
evidence, official geography, taxonomy version, and release.

`export-leads` reads only current gold leads, applies contracted filters and a one-million-row
maximum, writes CSV or Parquet atomically, and emits a manifest with filters, row count, source,
timestamp, and content hash.
