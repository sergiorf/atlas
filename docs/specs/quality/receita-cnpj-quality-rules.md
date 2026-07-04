# Receita estabelecimentos quality rules

- **Status:** Implemented
- **Behavior:** Diagnostic reporting; no rejection thresholds

Each run computes one aggregation over transformed bronze data and reports:

| Metric | Rule |
| --- | --- |
| `row_count` | Count all transformed rows |
| `invalid_cnpj_length_count` | Count null `cnpj_full` or values whose length is not 14 |
| `null_cnpj_root_count` | Count null normalized roots |
| `null_opening_date_count` | Count null parsed opening dates, including invalid source dates |
| `null_main_cnae_count` | Count null primary CNAEs |

Reports also contain dataset name, input path, output path, and UTC run timestamp. The job writes JSON and Markdown beside the bronze dataset after writing Parquet.

Nonzero metrics do not fail or quarantine the run in v0.1. The checks do not prove CNPJ checksum validity, uniqueness, referential integrity, source-file completeness, allowed domain codes, or freshness. Adding thresholds or failure behavior is a substantial quality-contract change.
