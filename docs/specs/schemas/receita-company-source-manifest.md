# Receita company source manifest

- **Status:** Implemented for pre-bronze local validation only
- **Owner:** `apps/etl/scripts/company_data_manifest.py`
- **Contract version:** `manifest_version = 1`

This contract does not make `Empresas`, Receita reference groups, TOM, or IBGE supported Atlas
datasets. It provides a side-effect-free gate for inspecting already acquired immutable inputs.
There is no downloader, public Atlas command, Spark reader, bronze writer, or published company
table. The first real selected-release manifest has not yet been captured.

## Root object

The UTF-8 JSON object contains:

| Field | Required value |
| --- | --- |
| `manifest_version` | Integer `1` |
| `release` | Operator-selected CNPJ release in `YYYY-MM` form |
| `created_at` | Non-empty creation timestamp |
| `publisher` | Non-empty publisher identifier |
| `producer_version` | Non-empty discovery producer version |
| `discovery_run_id` | Non-empty unique discovery-run identifier |
| `datasets` | Exactly one entry for every required CNPJ group |
| `references` | TOM and IBGE Localities capture entries |

Required CNPJ logical names are `empresas`, `cnae`, `municipios`, `naturezas`, `paises`,
`qualificacoes`, and `motivos`. Every dataset entry repeats the root `release`; disagreement is a
blocking mixed-release error.

## CNPJ dataset and archive entries

Each dataset declares `logical_name`, `release`, `parser`, and a non-empty `archives` array. A
parser declares `encoding`, one-character `delimiter`, `header` fixed to `false`, one-character
`quote`, one-character `escape`, and `expected_fields`. The expected count is seven for
`empresas` and two for each reference group. These are release evidence, not universal defaults.

Every archive declares:

- `source_url`, publisher `filename`, relative local `path`, and `retrieved_at`;
- exact `bytes` and lowercase `sha256` for the archive;
- every non-directory ZIP member, with relative `path`, uncompressed `bytes`, lowercase
  eight-character `crc32`, and lowercase `sha256`.

Paths resolve beneath an operator-supplied raw root. Absolute paths, traversal outside that root,
unsafe ZIP paths, duplicate member paths, missing members, extra members, corrupt archives, and
hash or size disagreement are blocking errors. Validation streams files and ZIP members; it does
not extract, modify, or write them. CSV members are strictly decoded and every record is checked
against the declared field count, including quoted delimiters.

## Geography reference entries

`references.tom` declares source URL, relative path, retrieval timestamp, content type, byte count,
SHA-256, and a five-field parser contract. It receives the same strict full-file parsing check.

`references.ibge_localities` declares the same provenance fields except for a CSV parser. Its
content must be a non-empty UTF-8 JSON array. Every municipality must expose an identifier and
identifiable immediate region, intermediate region, UF, and macroregion parents. A missing parent
blocks readiness; name matching is never used.

## Validator result and compatibility

`validate_manifest(manifest, raw_root)` returns a deterministically ordered sequence of bounded
`Diagnostic(rule, location, message)` values. An empty sequence is success. Diagnostics identify
contract locations and never include source records.

`inspect_archive(...)` and `inspect_file(...)` calculate the evidence records used to assemble a
manifest. They read local inputs, stream SHA-256 calculation, and return in-memory values; they do
not download, extract, or write anything. Parser settings remain an explicit reviewed declaration
because Atlas must not infer them heuristically.

This is a new, separate manifest version and does not read, rewrite, or migrate the implemented
`Estabelecimentos` downloader manifest. A future acquisition implementation may generalize shared
concepts only with explicit compatibility analysis and tests.

The selected-release gate is complete only after this validator succeeds against real local
inputs and the resulting filenames, multiplicity, parser settings, sizes, and hashes are reviewed.
Synthetic test success alone does not authorize bronze ingestion.
