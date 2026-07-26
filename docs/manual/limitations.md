# Limitations

Atlas currently:

- implements Receita `Estabelecimentos` and the company-data atomic silver workflow, but the
  May–July full-data acceptance must still record production counts, performance, and quality evidence;
- accepts coexisting numeric and uppercase alphanumeric CNPJs, validates their canonical structure but not their checksum;
- converts valid `yyyyMMdd` dates and represents invalid or blank dates as null;
- enforces unique company and establishment identifiers and applies documented bundle quality
  gates, but quarantined duplicate companies can leave unmatched establishments;
- resolves official company reference descriptions and TOM-to-IBGE municipality hierarchy, but
  does not add population, area, geometry, or density;
- does not implement `Simples`, `Socios`, corporate relationship graphs, CNAE business groups,
  gold tables, exports, scheduled refresh, API, UI, search, ranking, billing, AI, sanctions, or
  procurement;
- runs locally and is designed around a 32 GB RAM, 1 TB SSD development machine.

Silver is an internal pipeline contract, not a published product surface. DuckDB lead and graph examples are demonstrations, not supported product contracts.
