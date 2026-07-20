# Receita-to-IBGE municipality geography

- **Status:** Planned
- **Owner:** future Atlas geography reference pipeline
- **Contract level:** Internal silver reference design target
- **Output target:** `data/silver/geography/municipalities_current`
- **Primary key:** `receita_municipality_code`

This table and path are not implemented or currently runnable.

The mapping uses the official Receita TOM municipality CSV to map a Receita/TOM municipality code
to a seven-digit IBGE municipality code. That code joins exactly to a captured response from the
IBGE Localities `/api/v1/localidades/municipios` endpoint. Names are descriptive and are never used
as fallback join keys.

| Field | Spark type | Nullable | Meaning |
| --- | --- | --- | --- |
| `receita_municipality_code` | string | no | Preserved TOM code used by CNPJ data |
| `receita_municipality_name` | string | yes | TOM name |
| `ibge_municipality_code` | string | no | Seven-digit IBGE code |
| `ibge_municipality_name` | string | no | IBGE municipality name |
| `immediate_region_code`, `immediate_region_name` | string | yes | IBGE immediate region |
| `intermediate_region_code`, `intermediate_region_name` | string | yes | IBGE intermediate region |
| `state_code`, `state_abbreviation`, `state_name` | string | no | IBGE state identity |
| `region_code`, `region_abbreviation`, `region_name` | string | no | IBGE macroregion identity |
| `tom_source_hash`, `ibge_source_hash` | string | no | Captured-input provenance |
| `reference_as_of` | timestamp | no | Bundle capture time |

Unmatched or ambiguous codes reject publication after diagnostics; fuzzy matching is forbidden.
Districts, subdistricts, coordinates, population, area, and boundaries are deferred.

