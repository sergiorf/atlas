# Datasets

## Supported

Atlas v0.1 supports only the Receita Federal CNPJ `Estabelecimentos` file group. It preserves establishment identity, registration, CNAE, address, contact, and special-status fields in bronze, then adds normalized CNPJ identifiers and provenance.

See the [dataset specification](../specs/datasets/receita-cnpj.md) and [bronze schema](../specs/schemas/bronze-receita-cnpj.md).

## Planned

Other Receita file groups, IBGE/geography, CNAE enrichment, sanctions, procurement, and other sources are not supported inputs. Their intended sequence is documented in the [unified plan](../atlas_unified_plan.md) and [dataset roadmap](../roadmap/datasets.md).
