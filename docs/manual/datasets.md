# Datasets

## Supported

Atlas supports only the Receita Federal CNPJ `Estabelecimentos` file group. Bronze preserves establishment identity, registration, CNAE, address, contact, and special-status fields and adds normalized CNPJ identifiers and provenance.

The implemented v0.2 slice adds a curated silver establishment table with unique identifiers,
normalized status, CNAE, location and contact fields, retained bronze lineage, compact
release-to-release change events, and one analytical summary per published release. See the
[dataset specification](../specs/datasets/receita-cnpj.md) and the implemented schemas in the
[documentation index](../index.md#implemented-specifications).

## Planned

Municipality names, CNAE business groups, lead exports, other Receita file groups, IBGE/geography, sanctions, procurement, and other sources remain unsupported. Their intended sequence is documented in the [unified plan](../atlas_unified_plan.md#delivery-roadmap).
