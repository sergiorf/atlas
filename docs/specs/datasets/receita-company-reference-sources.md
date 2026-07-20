# Receita company and reference source interpretation

- **Status:** Planned
- **Owner:** future `apps/etl/src/main/scala/atlas/receita` company data pipeline
- **Roadmap:** v0.3a Receita company data foundation

This specification is a design target. Its paths, schemas, and commands are not currently
runnable behavior and do not authorize implementation.

## Source ownership and scope

Receita Federal owns the monthly CNPJ bulk publication. The planned tranche reads `Empresas` and
the snapshot's six reference groups: `CNAE`, `Municipios`, `Naturezas`, `Paises`, `Qualificacoes`,
and `Motivos`. Atlas retains publisher filenames, archive hashes, retrieval timestamps, selected
release, and source URLs in immutable raw manifests. Redistribution remains review required.

The official CNPJ metadata defines seven headerless positions for `Empresas` and two positions,
`code` and `description`, for each reference group. Files are semicolon-delimited and are read
string-first. The selected release manifest must declare the verified character encoding, quote,
and escape settings; the official layout does not provide enough evidence for Atlas to hard-code a
universal encoding. Missing verified parser settings block bronze ingestion. Blank values become
null only in bronze; raw bytes are never edited.

`cnpj_root` is preserved as a string and is never converted through a numeric type. Bronze may
trim, uppercase, and remove display punctuation, but it must retain invalid source-shaped values
for diagnostics. Silver alone enforces canonical `[0-9A-Z]{8}` roots. Reference codes remain
strings so padding and publisher semantics are preserved.

## Snapshot interpretation

All CNPJ inputs in a company data bundle must belong to the same operator-selected `YYYY-MM`
release. A missing required group blocks the bundle before transformation. File count, byte count,
and SHA-256 are recorded per archive. Atlas does not infer a release from row contents or silently
mix reference months.

The separate Receita TOM municipality table has five fields: TOM municipality code, IBGE
municipality code, TOM name, IBGE name, and UF abbreviation. The captured IBGE Localities
`/api/v1/localidades/municipios` response supplies municipality, immediate region, intermediate
region, state, and macroregion identifiers and names. Retrieval time, source URL, byte count, and
content hash are part of the bundle manifest. Identifiers are exact join keys; fuzzy name matching
is not permitted.

## Contract-baseline readiness gate

Acquisition and bronze implementation may begin only when a selected publisher release has:

- an enumerated archive manifest for `Empresas` and all six reference groups;
- verified archive and extracted-file naming and multiplicity;
- verified encoding, delimiter, quote, and escape settings;
- recorded URLs, filenames, byte counts, SHA-256 hashes, and retrieval timestamps;
- a captured TOM CSV and IBGE municipality response with the same provenance fields;
- redistribution still marked review required unless a separate review changes it.

The declarative Scala schemas and synthetic fixtures verify field shape only. They are not readers,
writers, acquisition support, or evidence that a specific monthly snapshot is locally complete.

`Estabelecimentos` retains its implemented specification. `Simples` and `Socios` are excluded from
this tranche. Population, boundaries, gold products, and business-defined CNAE groups are also
excluded.
