# Limitations

Atlas v0.1:

- supports only Receita `Estabelecimentos` bronze ingestion;
- validates normalized CNPJ length but not checksum;
- converts valid `yyyyMMdd` dates and represents invalid or blank dates as null;
- reports quality metrics without enforcing rejection thresholds;
- does not establish uniqueness or referential integrity across other Receita files;
- does not enrich municipality or CNAE codes;
- does not implement silver or gold tables, exports, scheduled refresh, API, UI, search, ranking, billing, AI, sanctions, or procurement;
- runs locally and is designed around a 32 GB RAM, 1 TB SSD development machine.

DuckDB lead and graph examples are demonstrations, not supported product contracts.
