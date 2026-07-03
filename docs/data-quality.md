# Data quality

Data quality is a product feature. Each v0.1 run records input/output paths, row count, invalid CNPJ-length count, null CNPJ roots, null opening dates, null main CNAEs, and UTC run time in JSON and Markdown. Future jobs should follow the same small-aggregation pattern and add domain-specific checks without collecting datasets to the driver.
