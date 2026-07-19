# Receita Federal CNPJ dataset specification

- **Status:** Implemented for `Estabelecimentos` bronze, silver normalization, and compact release history
- **Owner:** `apps/etl/src/main/scala/atlas/receita`
- **Identifier format authority:** [Receita Federal alphanumeric CNPJ specification](https://www.gov.br/receitafederal/pt-br/assuntos/noticias/2024/outubro/cnpj-tera-letras-e-numeros-a-partir-de-julho-de-2026/)

## Supported input

Atlas can acquire the supported monthly archives through
`./atlas download receita estabelecimentos --release YYYY-MM`. The command resumes `.part` files,
validates remote sizes when available, atomically promotes completed archives, safely extracts ZIP
members by default, maintains a local manifest, and records raw-stage status. `--latest` discovers
the lexically latest published `YYYY-MM`; the discovered month is recorded as the snapshot.
Acquisition remains operator-triggered and is not implicitly run by refresh.

Atlas reads headerless, semicolon-delimited Receita `Estabelecimentos` CSV using configurable encoding (default `ISO-8859-1`). The layout has exactly the 30 ordered fields defined by the [raw schema contract](../schemas/raw-receita-cnpj.md). Parsing is permissive and string-first.

Inputs are one or more extracted files matched beneath the configured raw directory. Raw files are immutable and remain outside Git. The snapshot month is operator-controlled and is not inferred from file contents. When the configured raw directory contains a `YYYY-MM` segment, Atlas replaces that segment with the selected release before reading; a custom path without a date segment is used unchanged.

## Interpretation

- Blank strings become null after trimming.
- CNPJ components are trimmed and uppercased, standard display-mask characters (`.`, `/`, `-`) are removed, and under-width values are left-padded to 8, 4, and 2 positions. Unknown characters and over-width values are preserved so silver can reject them rather than accepting a corrupted identifier.
- Roots and branches accept `0-9` and `A-Z`; the two check positions remain numeric. `cnpj_full` is the 14-character string concatenation and must never be cast to a number.
- Bronze diagnostics check the resulting 14-character length; checksum calculation is not implemented.
- `headquarters_branch_code = "1"` produces `is_headquarters = true`; other non-null values produce false and null produces null under Spark comparison semantics.
- Registration-status, opening, and special-status dates parse only eight-digit `yyyyMMdd` values; other values become null.
- `source_file` comes from Spark's input filename, and `ingestion_timestamp` is the processing time.

## Outputs and unsupported behavior

The ingestion job writes the [bronze schema](../schemas/bronze-receita-cnpj.md), partitioned by `state` under a release-scoped path, plus its diagnostic quality reports.

The standalone normalization job reads bronze and writes a release-scoped candidate using the curated [silver establishment schema](../schemas/silver-establishment.md), partitioned by `state`. It parses secondary CNAEs into a valid deduplicated array, normalizes state, postal and contact fields, derives active status, preserves provenance, and enforces unique fourteen-character establishment identifiers through the [silver quality gate](../quality/silver-establishment-quality-rules.md). Only refresh or full rebuild may publish that schema as latest current.

The release refresh command compares normalized silver records by `cnpj_full` and selected field hashes, writes [compact establishment change events](../schemas/establishment-change-events.md), writes one [durable release summary](../schemas/establishment-release-summaries.md), and publishes the new latest current table. The first release seeds current state and does not emit one insert event per establishment, but it does receive a summary. Unchanged releases also receive a summary and omit the empty event partition.

Latest-current publication is monotonic by the operator-selected `YYYY-MM` release. Normal refresh rejects a candidate equal to or older than current. Month gaps are supported and compare the two actual locally available releases. Standalone normalization writes a release-scoped candidate and cannot publish current. The guarded full rebuild command recreates the complete active establishment generation chronologically from an explicit range of protected raw releases.

Bronze may preserve malformed or shifted source-shaped rows. Silver requires uppercase alphanumeric roots and branches, numeric check positions, the corresponding canonical full string, and valid registration status codes; it quarantines malformed rows before enforcing uniqueness among valid numeric and alphanumeric keys. Atlas does not deduplicate establishments automatically, verify source completeness, validate CNPJ checksums, resolve municipality names, apply business CNAE groups, or join other Receita files.
