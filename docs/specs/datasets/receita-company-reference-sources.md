# Receita company and reference source interpretation

- **Status:** Planned
- **Owner:** future `apps/etl/src/main/scala/atlas/receita` company-spine pipeline
- **Roadmap:** v0.3a company-spine silver foundation

This specification is a design target. Its paths, schemas, and commands are not currently
runnable behavior and do not authorize implementation.

## Source ownership and scope

Receita Federal owns the monthly CNPJ bulk publication. The planned tranche reads `Empresas` and
the snapshot's six reference groups: `CNAE`, `Municipios`, `Naturezas`, `Paises`, `Qualificacoes`,
and `Motivos`. Atlas retains publisher filenames, archive hashes, retrieval timestamps, selected
release, and source URLs in immutable raw manifests. Redistribution remains review required.

The official CNPJ metadata defines each headerless layout. Files are semicolon-delimited and are
read string-first with the declared publisher encoding. Blank values become null only in bronze;
raw bytes are never edited. `cnpj_root` is an eight-character uppercase alphanumeric string and is
never numeric. Reference codes remain strings so padding and publisher semantics are preserved.

## Snapshot interpretation

All CNPJ inputs in a company-spine bundle must belong to the same operator-selected `YYYY-MM`
release. A missing required group blocks the bundle before transformation. File count, byte count,
and SHA-256 are recorded per archive. Atlas does not infer a release from row contents or silently
mix reference months.

The separate Receita TOM municipality table and the IBGE Localities municipality response are
captured as versioned reference inputs. Their retrieval time and content hash are part of the
bundle manifest. They bridge Receita municipality codes to IBGE identifiers; fuzzy name matching
is not permitted.

`Estabelecimentos` retains its implemented specification. `Simples` and `Socios` are excluded from
this tranche. Population, boundaries, gold products, and business-defined CNAE groups are also
excluded.

