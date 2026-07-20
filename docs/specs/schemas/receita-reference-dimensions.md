# Receita reference dimensions

- **Status:** Planned
- **Owner:** future Receita reference pipeline
- **Contract level:** Internal silver reference design target

These tables and paths are not implemented or currently runnable.

The publisher source layout for every group is exactly two string positions: `code` and
`description`. `CompanyDataSchemas.referenceRaw` records that shared source shape, while
`referenceGroups` enumerates the six Atlas-owned dimension names. This shared declaration avoids
six copies of an identical parser contract; each group remains separately manifested and
versioned.

Each monthly bundle would produce one release-scoped dimension for `cnae`, `municipality`,
`legal_nature`, `country`, `partner_qualification`, and `registration_status_reason`. Each row
contains the preserved string `code`, trimmed `description`, `release`, `source_name`,
`source_file`, `ingestion_timestamp`, and a deterministic `record_hash`. `municipality` preserves
the Receita/TOM code and source description; geographic identifiers belong to the separate
geography contract.

The intended path is `data/silver/receita/references/<dimension>/release=YYYY-MM`. Code is the
primary key within a dimension and release. Empty or invalid codes are quarantined; conflicting
duplicates reject the bundle. Exact duplicates may be collapsed only with a reported count.
Descriptions are source labels, not Atlas business classifications. Reference rows are versioned
with their CNPJ snapshot and never silently carried forward from another release.
