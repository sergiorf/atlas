# Receita company-data foundation delivery record

- **Status:** Implemented; full-data acceptance pending
- **Roadmap milestone:** v0.3a
- **Purpose:** Historical decision and handoff record

The company-data foundation is implemented through atomic silver publication. This page records
the delivered boundary and the remaining acceptance work; it no longer repeats schemas, quality
rules, commands, or recovery behavior owned by active specifications and operations guides.

## Delivered boundary

Atlas can acquire immutable, manifest-backed `Empresas`, six Receita reference groups, TOM, and
IBGE Localities inputs; combine them with the matching `Estabelecimentos` release; and publish one
coherent silver bundle containing companies, establishments, reference dimensions, municipality
geography, compact history, quality evidence, and lineage.

```mermaid
flowchart LR
    A["Company source package<br/>Empresas + references + TOM + IBGE"] --> C["Same-release candidate"]
    B["Estabelecimentos<br/>raw release"] --> C
    C --> D["Bronze and silver components"]
    D --> E{"Schema, quality, history,<br/>lineage, and readability pass?"}
    E -- No --> F["Retain failed candidate<br/>current bundle unchanged"]
    E -- Yes --> G["Immutable generation"]
    G --> H["Atomic current_bundle switch"]
```

The design deliberately keeps raw acquisition separate from transformation, requires an exact
CNPJ release across monthly inputs, pins reference captures by hash, and exposes a single bundle
pointer so consumers cannot assemble mixed-release current tables.

## Remaining acceptance and hardening

The operator-run May–July acceptance must still record full-data counts, quality evidence,
resource use, representative queries, rollback evidence, and confirmation that raw bytes remain
unchanged. The next operational hardening task is a coordinated company-data download command that
also invokes the existing establishment acquisition module and performs a final same-release
readiness check.

These are exit checks on the implemented foundation, not missing transformation stages. Gold
company profiles, `Simples`, `Socios`, corporate relationship graphs, CNAE business groups, lead
exports, serving, API, and UI remain later roadmap work.

## Active owners

- Product priority and remaining sequence: [Atlas unified plan](../atlas_unified_plan.md#delivery-roadmap)
- Operator commands and acceptance procedure:
  [company-data bundle runbook](../operations/company-data-pipeline.md)
- Source inventory and interpretation:
  [dataset catalog](../source_catalog.md) and
  [company/reference source specification](../specs/datasets/receita-company-reference-sources.md)
- Schemas and quality rules: [documentation index](../index.md#company-data-specifications)
- Publication, failure, and cleanup behavior:
  [company-data bundle runbook](../operations/company-data-pipeline.md#failure-and-recovery)

This record is not an additional contract. If it conflicts with an active specification or
runbook, the active owner wins.
