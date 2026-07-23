# Limitations

Atlas currently:

- implements Receita `Estabelecimentos` and the company-data atomic silver workflow, but the
  May–July full-data acceptance must still record production counts, performance, and quality evidence;
- accepts coexisting numeric and uppercase alphanumeric CNPJs, validates their canonical structure but not their checksum;
- converts valid `yyyyMMdd` dates and represents invalid or blank dates as null;
- enforces unique fourteen-character string identifiers in silver but not referential integrity across other Receita files;
- does not enrich municipality or CNAE codes;
- does not implement municipality lookup, CNAE business groups, gold tables, exports, scheduled refresh, API, UI, search, ranking, billing, AI, sanctions, or procurement;
- runs locally and is designed around a 32 GB RAM, 1 TB SSD development machine.

Silver is an internal pipeline contract, not a published product surface. DuckDB lead and graph examples are demonstrations, not supported product contracts.
