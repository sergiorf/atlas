# Receita Federal CNPJ dataset specification

- **Status:** Implemented for `Estabelecimentos` bronze, silver normalization, and compact release history
- **Owner:** `apps/etl/src/main/scala/atlas/receita`

## Supported input

Atlas reads headerless, semicolon-delimited Receita `Estabelecimentos` CSV using configurable encoding (default `ISO-8859-1`). The layout has exactly the 30 ordered fields defined by the [raw schema contract](../schemas/raw-receita-cnpj.md). Parsing is permissive and string-first.

Inputs are one or more extracted files matched beneath the configured raw directory. Raw files are immutable and remain outside Git. The snapshot month is an operator-controlled path convention, not inferred from file contents.

## Interpretation

- Blank strings become null after trimming.
- CNPJ root, branch, and check components retain digits, are left-padded to 8, 4, and 2 positions, and form `cnpj_full`.
- CNPJ validation in v0.1 checks only the resulting 14-character length; it does not validate check digits.
- `headquarters_branch_code = "1"` produces `is_headquarters = true`; other non-null values produce false and null produces null under Spark comparison semantics.
- Registration-status, opening, and special-status dates parse only eight-digit `yyyyMMdd` values; other values become null.
- `source_file` comes from Spark's input filename, and `ingestion_timestamp` is the processing time.

## Outputs and unsupported behavior

The ingestion job writes the [bronze schema](../schemas/bronze-receita-cnpj.md), partitioned by `state` under a release-scoped path, plus its diagnostic quality reports.

The normalization job reads bronze and writes the curated latest [silver establishment schema](../schemas/silver-establishment.md), also partitioned by `state`. It parses secondary CNAEs into a valid deduplicated array, normalizes state, postal and contact fields, derives active status, preserves provenance, and enforces unique fourteen-digit establishment identifiers through the [silver quality gate](../quality/silver-establishment-quality-rules.md).

The release refresh command compares normalized silver records by `cnpj_full` and selected field hashes, writes [compact establishment change events](../schemas/establishment-change-events.md), and publishes the new latest current table. The first release seeds current state and does not emit one insert event per establishment.

Bronze may preserve malformed or shifted source-shaped rows. Silver structurally validates CNPJ widths and registration status codes, quarantines malformed rows, then enforces uniqueness among valid candidates. Atlas does not deduplicate establishments automatically, verify source completeness, validate CNPJ checksums, resolve municipality names, apply business CNAE groups, or join other Receita files.
