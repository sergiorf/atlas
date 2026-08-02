# Receita company-product quality rules

- **Status:** Implemented
- **Owner:** Receita company-product silver normalization
- **Release boundary:** One operator-selected `YYYY-MM` snapshot

Partner records with a missing or structurally invalid source-company CNPJ root or a participant
type outside `1`, `2`, and `3` are written to `malformed_partners` and excluded from silver. An
unusable entry date does not invalidate the source-reported partner relationship.

For a non-blank `entry_date_raw`, silver assigns at most one issue using this precedence:

1. a value other than exactly eight digits is `invalid_date_format`;
2. eight digits that do not form a real `yyyyMMdd` date are `invalid_calendar_date`;
3. a parsed date before `1582-10-15` is `date_before_supported_minimum`;
4. a parsed date after the final day of the declared release month is `date_after_release`.

Affected records preserve `entry_date_raw`, publish with a null `entry_date`, and are written to
`data/_atlas/quality/receita/company-data/<release>/partner_field_quality_issues`. The diagnostic
contains `partner_record_id`, `source_company_cnpj_root`, `field_name`, `raw_value`,
`quality_reason`, `source_file`, and `release`. It is non-blocking and produces a partner-silver
`success_with_warnings` status. Its count is not a quarantined-row count because the partner row
remains published.

The lower bound is a storage-compatibility boundary that prevents dates subject to Spark's ancient
Parquet calendar ambiguity. It is not an assertion about when Brazilian companies or partner
relationships could legally exist. A stricter business boundary requires separate source evidence
and compatibility review.
