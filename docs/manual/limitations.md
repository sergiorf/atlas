# Limitations

Atlas currently:

- implements and has operator-accepted the Receita `Estabelecimentos` and company-data atomic
  silver foundation; detailed production evidence remains local because generated data and
  record-level diagnostics are not committed;
- implements and has operator-accepted v0.3b company products and corporate relationships with
  limitations; full validation of the 2026-07 bundle reported no failures, one skipped predecessor
  check, and 51 establishments without an accepted company under the documented duplicate-company
  quarantine policy;
- accepts coexisting numeric and uppercase alphanumeric CNPJs, validates their canonical structure but not their checksum;
- converts valid `yyyyMMdd` dates and represents invalid or blank dates as null;
- enforces unique company and establishment identifiers and applies documented bundle quality
  gates, but quarantined duplicate companies can leave unmatched establishments;
- resolves official company reference descriptions and TOM-to-IBGE municipality hierarchy, but
  does not add population, area, geometry, or density;
- implements `Simples`, source-faithful `Socios`, deterministic Brazilian legal-entity
  relationships, versioned CNAE groups, company-profile/partner-network/lead gold, and controlled
  lead exports; it does not resolve natural people, assert unsupported control, materialize
  unrestricted graph closure, or provide scheduled refresh, API, UI, search, ranking, billing,
  AI, sanctions, or procurement;
- runs locally and is designed around a 32 GB RAM, 1 TB SSD development machine.

Silver is an internal pipeline contract, not a published product surface. DuckDB lead and graph examples are demonstrations, not supported product contracts.
