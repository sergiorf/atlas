# Receita-to-IBGE municipality geography

- **Status:** Implemented
- **Owner:** Atlas geography reference pipeline
- **Contract level:** Internal silver reference contract
- **Output target:** bundle-relative `data/silver/receita/geography/municipalities/version=YYYY-MM`
- **Primary key:** `receita_municipality_code`

This versioned table is implemented within atomic bundle generations.

The five-field TOM source and nested IBGE municipality response are asserted by
`CompanyDataSchemas.tomMunicipalitiesRaw` and `ibgeMunicipalityRaw` and used by the exact join job.

The mapping uses the official Receita TOM municipality CSV to map a Receita/TOM municipality code
to a seven-digit IBGE municipality code. That code joins exactly to a captured response from the
IBGE Localities `/api/v1/localidades/municipios` endpoint. Names are descriptive and are never used
as fallback join keys.

| Field | Spark type | Nullable | Meaning |
| --- | --- | --- | --- |
| `receita_municipality_code` | string | no | Canonical four-digit TOM code used by CNPJ data |
| `receita_municipality_name` | string | yes | TOM name |
| `ibge_municipality_code` | string | no | Seven-digit IBGE code, or `0` for the official exterior sentinel |
| `ibge_municipality_name` | string | yes | IBGE municipality name; null for exterior |
| `immediate_region_code`, `immediate_region_name` | string | yes | IBGE immediate region |
| `intermediate_region_code`, `intermediate_region_name` | string | yes | IBGE intermediate region |
| `state_code`, `state_name` | string | yes | IBGE state identity; null for exterior |
| `state_abbreviation` | string | no | IBGE state abbreviation, or `EX` for exterior |
| `region_code`, `region_abbreviation`, `region_name` | string | yes | IBGE macroregion identity; null for exterior |
| `tom_source_hash`, `ibge_source_hash` | string | no | Captured-input provenance |
| `is_exterior` | boolean | no | True only for the official `9707 / 0 / EXTERIOR / EX` TOM row |
| `mapping_source` | string | no | `current_tom`, `verified_override`, or `carried_forward` |
| `mapping_source_release` | string | no | Release where the selected mapping was reviewed or observed |
| `current_tom_present` | boolean | no | Whether the selected release's TOM capture contains the mapping |
| `evidence_reference` | string | yes | Authoritative evidence for a reviewed override; null for TOM observations |
| `reference_as_of` | timestamp | no | Bundle capture time |

The publisher CSV heading is parsed as a row so raw parsing remains source-faithful, then removed
only when all five heading values match the official text. Numeric TOM values of one through four
digits are canonicalized to four characters with leading zeroes at the silver boundary; captured
raw values are never edited. The official exterior sentinel is kept
because establishments use TOM code `9707`; its Brazilian hierarchy fields are intentionally null.
Every other unmatched, parentless, malformed, ambiguous, or state-conflicting code rejects
publication. Fuzzy matching is forbidden.

Resolution precedence is the current TOM capture, a release-valid reviewed override, and then an
uncontradicted mapping from the latest earlier geography version. Reviewed overrides live in the
version-controlled `tom-municipality-overrides.csv` registry and require exact TOM and IBGE codes,
a release interval, and authoritative evidence. An override is eligible only when its IBGE code
exists in the selected release's pinned IBGE response.

A mapping absent from the current TOM capture is not interpreted as retired because Receita's TOM
contract has no lifecycle fields. A previously observed mapping may be carried forward only when
the current capture does not contradict it and the pinned IBGE response still contains its IBGE
code. Any different current mapping for the same TOM code blocks publication. Historical
observations remain release-scoped; Atlas never rewrites old geography.

The reviewed override for TOM `1182` maps Boa Esperança do Norte, MT, to IBGE `5101837` beginning
with release `2025-01`. It addresses an observed omission from the official TOM CSV and cites the
official IBGE municipality-code table and the municipality's official identification page.
Districts, subdistricts, coordinates, population, area, and boundaries are deferred.
