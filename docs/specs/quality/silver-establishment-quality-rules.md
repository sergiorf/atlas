# Silver establishment quality rules

- **Status:** Implemented
- **Behavior:** Identity failures reject publication; other metrics are diagnostic

The silver job validates its prepared data before invoking the Parquet writer. It writes JSON and Markdown reports for accepted and rejected validation runs.

| Metric | Rule | Effect |
| --- | --- | --- |
| `row_count` | Count all prepared rows | Diagnostic |
| `invalid_cnpj_count` | Count null values or values not matching fourteen digits | Reject when nonzero |
| `duplicate_key_count` | Count distinct non-null `cnpj_full` values occurring more than once | Reject when nonzero |
| `duplicate_row_count` | Count every row belonging to a duplicate key | Diagnostic accompanying rejection |
| `null_opening_date_count` | Count null opening dates | Diagnostic |
| `invalid_main_cnae_count` | Count null or non-seven-digit primary CNAEs | Diagnostic |
| `malformed_secondary_cnae_token_count` | Count nonblank comma-separated tokens that are not seven digits | Diagnostic |
| `invalid_state_count` | Count non-null source states that are not two letters after uppercasing | Diagnostic |
| `null_municipality_code_count` | Count null municipality codes | Diagnostic |

A rejected run writes reports with `status = rejected`, throws an error, and does not invoke the silver writer, so an existing silver dataset is not replaced. An accepted report means the validation gate passed; a later storage failure can still prevent publication. The job does not deduplicate, quarantine, validate CNPJ checksums, or impose thresholds on non-identity metrics.
