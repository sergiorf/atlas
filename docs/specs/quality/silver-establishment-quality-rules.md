# Silver establishment quality rules

- **Status:** Implemented
- **Behavior:** Structural failures are quarantined before strict valid-key checks

The silver job validates prepared data before invoking the silver Parquet writer. Structural validation runs first. Malformed rows are written as inspectable Parquet to `data/_atlas/quality/receita/establishments/<snapshot>/malformed_rows`, excluded from the clean candidate set, and recorded as a warning. Generated quality data remains local and outside Git.

| Metric | Rule | Effect |
| --- | --- | --- |
| `row_count` | Count all prepared rows | Diagnostic |
| `malformed_row_count` | Count rows with a nonconforming CNPJ component/full key or registration status outside `01`, `02`, `03`, `04`, `08` | Quarantine and exclude |
| `invalid_cnpj_count` | Count null values or values not matching fourteen digits | Included in structural diagnostics |
| `duplicate_key_count` | Count distinct valid `cnpj_full` values occurring more than once | Reject when nonzero |
| `duplicate_row_count` | Count every row belonging to a duplicate key | Diagnostic accompanying rejection |
| `null_opening_date_count` | Count null opening dates | Diagnostic |
| `invalid_main_cnae_count` | Count null or non-seven-digit primary CNAEs | Diagnostic |
| `malformed_secondary_cnae_token_count` | Count nonblank comma-separated tokens that are not seven digits | Diagnostic |
| `invalid_state_count` | Count non-null source states that are not valid Brazilian UF codes after uppercasing | Diagnostic; normalized output is null |
| `null_municipality_code_count` | Count null municipality codes | Diagnostic |

A date-like registration status such as `20250324` is malformed, not a business duplicate. An accepted run with quarantined rows publishes only valid rows and records `success_with_warnings`. Remaining duplicate valid keys write all involved rows to `data/_atlas/quality/receita/establishments/<snapshot>/duplicate_cnpj_full`, reject the run, and do not invoke the silver writer, so validation failures cannot replace an existing table. No duplicate is silently collapsed. A later storage failure can still prevent publication. CNPJ checksum validation is not implemented.
