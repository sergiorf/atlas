# Receita company products

- **Status:** Implemented
- **Contract levels:** Silver internal; gold and lead export published data-product contracts
- **Release boundary:** One atomic bundle and one Receita release

`company_tax_regime_current` has one row per accepted root. Nullable Simples and MEI indicators
preserve unknown states. Duplicates and malformed rows are diagnosed and excluded; absence is not a
negative tax assertion.

`partners_current` has one row per source QSA record. It preserves source evidence, privacy class,
qualification, provenance, release, and stable record identity. It is internal.

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
`company_relationship_paths_current` contains ordered node and edge paths through depth three.
Cycle search is bounded at depth six. Immediate edges remain authoritative.

`conf/cnae-groups.conf` owns taxonomy version and memberships. `leads_new_companies_current` has
one row per active establishment and matched group, with company identity, opening date, CNAE match
evidence, official geography, taxonomy version, and release.

`export-leads` reads only current gold leads, applies contracted filters and a one-million-row
maximum, writes CSV or Parquet atomically, and emits a manifest with filters, row count, source,
timestamp, and content hash.
