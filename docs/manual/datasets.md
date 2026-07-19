# Datasets

## Supported

Atlas supports only the Receita Federal CNPJ `Estabelecimentos` file group. Bronze preserves establishment identity, registration, CNAE, address, contact, and special-status fields and adds normalized CNPJ identifiers and provenance. Numeric and alphanumeric CNPJs coexist in the same string key space; only the first twelve positions may contain uppercase letters, while check digits remain numeric.

The implemented v0.2 slice adds a curated silver establishment table with unique identifiers,
normalized status, CNAE, location and contact fields, retained bronze lineage, compact
release-to-release change events, and one analytical summary per published release. See the
[dataset specification](../specs/datasets/receita-cnpj.md) and the implemented schemas in the
[documentation index](../index.md#implemented-specifications).

## Catalog status

The canonical [Dataset and source catalog](../source_catalog.md) uses three classes:

- **Supported** means implemented behavior covered by an active specification. Today this is only
  Receita CNPJ `Estabelecimentos`.
- **Planned** means approved roadmap work through v0.6 that remains unsupported until implemented.
- **Candidate** means a demand-dependent later source with no implementation commitment.

The catalog contains the complete inventory, official source evidence, access and redistribution
notes, and expected cadence. The [unified plan](../atlas_unified_plan.md#delivery-roadmap) remains
the sole owner of priorities and sequencing.
