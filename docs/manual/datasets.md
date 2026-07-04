# Datasets

## Supported

Atlas supports only the Receita Federal CNPJ `Estabelecimentos` file group. Bronze preserves establishment identity, registration, CNAE, address, contact, and special-status fields and adds normalized CNPJ identifiers and provenance.

The first v0.2 slice adds a curated silver establishment table with unique identifiers, normalized status, CNAE, location and contact fields, and retained bronze lineage. See the [dataset specification](../specs/datasets/receita-cnpj.md), [bronze schema](../specs/schemas/bronze-receita-cnpj.md), and [silver schema](../specs/schemas/silver-establishment.md).

## Planned

Municipality names, CNAE business groups, lead exports, other Receita file groups, IBGE/geography, sanctions, procurement, and other sources remain unsupported. Their intended sequence is documented in the [unified plan](../atlas_unified_plan.md) and [dataset roadmap](../roadmap/datasets.md).
