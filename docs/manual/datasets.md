# Datasets

## Supported

Atlas supports transformed Receita Federal CNPJ `Estabelecimentos`, `Empresas`, and the six
reference groups used by the atomic company-data foundation. Establishment bronze preserves
identity, registration, CNAE, address, contact, and special-status fields and adds normalized CNPJ
identifiers and provenance. Numeric and alphanumeric CNPJs coexist in the same string key space;
only the first twelve positions may contain uppercase letters, while check digits remain numeric.

Atlas can acquire, verify, and transform the company-data source bundle (`Empresas`, six Receita
references, TOM, and IBGE Localities) together with matching establishments. It publishes company,
reference, geography, history, and establishment components through one atomic silver bundle.
The May–July foundation acceptance was operator-confirmed on 2026-07-30. Silver remains an internal
contract rather than a public query product.

v0.3b was operator-accepted with limitations on 2026-08-08 and supports `Simples` and reviewed
`Socios`. Masked natural-person evidence remains
internal and is not resolved across companies. Gold products expose company profiles,
evidence-preserving legal-entity partner networks, bounded relationship paths, versioned CNAE
business groups, and establishment-grained new-company leads.

Silver establishments have unique identifiers, normalized status, CNAE, location and contact
fields, retained bronze lineage, compact release-to-release change events, and one analytical
summary per published release. See the [dataset specification](../specs/datasets/receita-cnpj.md)
and the implemented schemas in the
[documentation index](../index.md#implemented-specifications).

## Catalog status

The canonical [Dataset and source catalog](../source_catalog.md) uses three classes:

- **Supported** means implemented behavior covered by an active specification. This includes
  Receita CNPJ `Estabelecimentos` and the accepted v0.3a and v0.3b company-data products.
- **Planned** means approved roadmap work through v0.6 that remains unsupported until implemented.
- **Candidate** means a demand-dependent later source with no implementation commitment.

The catalog contains the complete inventory, official source evidence, access and redistribution
notes, and expected cadence. The [unified plan](../atlas_unified_plan.md#delivery-roadmap) remains
the sole owner of priorities and sequencing.
