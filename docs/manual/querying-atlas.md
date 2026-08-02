# Querying Atlas

Atlas has no public API, search index, customer UI, or supported query service. For local inspection, DuckDB can query the generated Parquet datasets directly from either its command-line client or its browser-based UI. The DuckDB UI described here is a developer tool and is not the future Atlas product UI.

Use silver for normalized internal exploration. Query bronze when investigating source-shaped values or pipeline quality. Do not query raw Receita CSV directly for product behavior. Silver is an internal contract rather than a published product or gold contract, and queries in this guide do not create new ranking, filtering, aggregation, or public-query semantics.

Paths in this guide are relative to `apps/etl`. If `ATLAS_RECEITA_BRONZE_DIR` or `ATLAS_RECEITA_SILVER_DIR` was used when building the data, replace the paths accordingly.

## Start DuckDB

Start the terminal client from `apps/etl`:

```bash
duckdb
```

Start DuckDB's local browser UI instead with:

```bash
duckdb -ui
```

The UI opens a local SQL workspace in the browser. It still queries local files through DuckDB; it is not an Atlas server or dashboard. Keep the DuckDB process running while using the page, and stop it when finished. If the installed DuckDB build does not recognize `-ui`, update DuckDB to a build that includes the UI extension or use the terminal client.

These commands use an in-memory DuckDB session and do not copy the Parquet data. To preserve only view definitions and query history between sessions, pass a local database filename such as `duckdb atlas-inspection.duckdb -ui`. Treat that file as generated local state and do not commit it.

## Create convenient views

Create a view over the latest complete normalized establishment table:

```sql
CREATE OR REPLACE VIEW silver_establishments AS
SELECT *
FROM read_parquet(
  'data/silver/receita/establishments_current/**/*.parquet',
  hive_partitioning = true
);
```

After at least two releases have been refreshed and change events exist, create the compact history view:

```sql
CREATE OR REPLACE VIEW establishment_history AS
SELECT *
FROM read_parquet(
  'data/silver/receita/establishment_change_events/**/*.parquet',
  hive_partitioning = true
);
```

The first refreshed release only seeds `establishments_current`, so the history path may not exist yet. History contains selected field-level changes, not complete copies of previous establishment rows. See the [change-event schema](../specs/schemas/establishment-change-events.md) for the tracked fields and limitations.

Create a view over the durable per-release summaries (the path exists after the seed refresh):

```sql
CREATE OR REPLACE VIEW establishment_release_summaries AS
SELECT *
FROM read_parquet(
  'data/silver/receita/establishment_release_summaries/**/*.parquet',
  hive_partitioning = true
);
```

Summaries contain totals, state buckets, and updated-field counts for every release. Percentages and alert thresholds are intentionally derived/not implemented and never affect publication.

For bronze investigation, select a release explicitly:

```sql
CREATE OR REPLACE VIEW bronze_establishments AS
SELECT *
FROM read_parquet(
  'data/bronze/receita/estabelecimentos/release=2026-07/**/*.parquet',
  hive_partitioning = true
);
```

Selecting a release prevents an investigation from accidentally mixing snapshots. Replace `2026-07` with the release being examined.

Aggregate partner field-quality issues before inspecting any source-linked records:

```sql
SELECT field_name, quality_reason, count(*) AS issue_count
FROM read_parquet(
  'data/_atlas/quality/receita/company-data/2026-07/partner_field_quality_issues/**/*.parquet'
)
GROUP BY field_name, quality_reason
ORDER BY issue_count DESC, field_name, quality_reason;
```

The path exists only when the release has issues. These diagnostics can contain source-linked
natural-person evidence, so do not export unrestricted rows.

## Discover schemas and available data

Inspect columns and types without guessing field names:

```sql
DESCRIBE silver_establishments;
DESCRIBE establishment_history;
DESCRIBE bronze_establishments;
```

Check the latest silver release, row count, and partition distribution:

```sql
SELECT release, count(*) AS row_count
FROM silver_establishments
GROUP BY release
ORDER BY release;

SELECT state, count(*) AS row_count
FROM silver_establishments
GROUP BY state
ORDER BY row_count DESC;
```

`establishments_current` should normally contain one release. Before trusting the output, also inspect operational status and the release inventory from the repository root:

```bash
./atlas status
./atlas status --json
./atlas releases list
./atlas releases inspect --release 2026-07
```

The status registry reports the latest job attempts; it is not establishment history and does not prove that an output path still exists.

## Look up establishments and companies

Look up one establishment by its fourteen-character CNPJ:

```sql
SELECT
  cnpj_full,
  trade_name,
  is_headquarters,
  registration_status_code,
  is_active,
  opening_date,
  main_cnae,
  state,
  municipality_code,
  email
FROM silver_establishments
WHERE cnpj_full = '12345678000109';
```

List every establishment belonging to one eight-character company root:

```sql
SELECT
  cnpj_full,
  is_headquarters,
  trade_name,
  is_active,
  state,
  municipality_code
FROM silver_establishments
WHERE cnpj_root = '12345678'
ORDER BY cnpj_branch, cnpj_check;
```

Numeric and alphanumeric CNPJs coexist. Keep every CNPJ field as a string: the first twelve positions may contain uppercase letters, the final two check positions remain numeric, and leading zeros are significant. Do not cast identifiers to numeric types.

Atlas now publishes a company-profile gold table through the atomic company-data bundle. Grouping
silver establishments remains useful for internal inspection but does not replace the gold
company-profile contract.

## Explore status, geography, dates, and CNAE

Count active and inactive establishments by state:

```sql
SELECT
  state,
  is_active,
  count(*) AS establishment_count
FROM silver_establishments
GROUP BY state, is_active
ORDER BY state, is_active DESC NULLS LAST;
```

Find active establishments opened in a date range:

```sql
SELECT
  cnpj_full,
  trade_name,
  opening_date,
  main_cnae,
  state,
  municipality_code
FROM silver_establishments
WHERE is_active = true
  AND opening_date >= DATE '2026-06-01'
  AND opening_date < DATE '2026-07-01'
ORDER BY opening_date DESC, cnpj_full
LIMIT 100;
```

Filter by a primary or secondary CNAE code:

```sql
SELECT
  cnpj_full,
  trade_name,
  main_cnae,
  secondary_cnaes,
  state
FROM silver_establishments
WHERE main_cnae = '6201501'
   OR list_contains(secondary_cnaes, '6201501')
LIMIT 100;
```

Silver establishment municipality values remain Receita codes. The atomic bundle separately
provides official geography, and v0.3b gold leads apply versioned business CNAE groups. These
silver examples still do not define lead-product behavior.

## Inspect compact change history

Summarize changes by target release and type:

```sql
SELECT
  to_release,
  change_type,
  count(*) AS event_count
FROM establishment_history
GROUP BY to_release, change_type
ORDER BY to_release, change_type;
```

Show the timeline for one establishment:

```sql
SELECT
  event_id,
  cnpj_full,
  from_release,
  to_release,
  change_type,
  changed_fields,
  detected_at
FROM establishment_history
WHERE cnpj_full = '12345678000109'
ORDER BY to_release, detected_at;
```

Expand the changed-field array into one row per field:

```sql
SELECT
  h.cnpj_full,
  h.from_release,
  h.to_release,
  h.change_type,
  change.field_name,
  change.old_value,
  change.new_value
FROM establishment_history AS h,
UNNEST(h.changed_fields) AS fields(change)
ORDER BY h.to_release, h.cnpj_full, change.field_name;
```

Count which tracked fields changed most often:

```sql
SELECT
  change.field_name,
  count(*) AS change_count
FROM establishment_history AS h,
UNNEST(h.changed_fields) AS fields(change)
WHERE h.change_type = 'updated'
GROUP BY change.field_name
ORDER BY change_count DESC, change.field_name;
```

Inserted and removed events identify membership changes between releases. Updated events contain selected old and new field values encoded as JSON strings. A missing event does not prove that every source or untracked field was unchanged, and the history cannot reconstruct complete prior rows.

## Investigate quality and lineage

Check nullable and diagnostic conditions in the published silver table:

```sql
SELECT
  count(*) AS row_count,
  count(*) FILTER (WHERE opening_date IS NULL) AS missing_opening_date,
  count(*) FILTER (WHERE main_cnae IS NULL) AS missing_main_cnae,
  count(*) FILTER (WHERE state IS NULL) AS missing_or_invalid_state,
  count(*) FILTER (WHERE municipality_code IS NULL) AS missing_municipality_code,
  count(*) FILTER (WHERE email IS NULL) AS missing_email
FROM silver_establishments;
```

Confirm the contracted primary key is populated and unique:

```sql
SELECT
  count(*) AS row_count,
  count(cnpj_full) AS populated_keys,
  count(DISTINCT cnpj_full) AS distinct_keys
FROM silver_establishments;
```

Inspect provenance for a representative record:

```sql
SELECT
  cnpj_full,
  source_name,
  source_file,
  ingestion_timestamp,
  silver_transformation_timestamp,
  release,
  record_hash
FROM silver_establishments
WHERE cnpj_full = '12345678000109';
```

Published silver excludes structurally malformed candidates. To investigate excluded rows or duplicate-key failures, query the corresponding Parquet path beneath `data/_atlas/quality/receita/establishments/<release>/` when it exists:

```sql
SELECT *
FROM read_parquet(
  'data/_atlas/quality/receita/establishments/2026-07/malformed_rows/**/*.parquet',
  hive_partitioning = true
)
LIMIT 100;
```

Replace `malformed_rows` with `duplicate_cnpj_full` to inspect a duplicate-key quarantine. These directories are produced only when the relevant condition occurs, so DuckDB reports a missing-file error when there is nothing to inspect. Review the JSON or Markdown quality report and [data-quality manual](data-quality.md) before interpreting these records; do not edit raw data or silently invent correction rules.

## Export a small inspection result

Export only a bounded query result, not an uncontrolled copy of the full internal table:

```sql
COPY (
  SELECT cnpj_full, trade_name, opening_date, main_cnae, state, municipality_code
  FROM silver_establishments
  WHERE is_active = true
    AND state = 'PE'
  ORDER BY opening_date DESC NULLS LAST
  LIMIT 1000
) TO '/tmp/atlas-establishment-inspection.csv' (HEADER, DELIMITER ',');
```

The file is an ad hoc local inspection artifact, not a contracted Atlas export. Do not commit generated data, and do not treat this example as authorization for the planned lead-export or gold layers.

## Existing examples and contract references

SQL files under `apps/etl/examples/duckdb/` demonstrate bronze inspection and preview future lead and graph questions. Preview queries do not define production silver, gold, ranking, filtering, or public-query contracts.

For authoritative field meanings and limitations, see:

- [Silver establishment schema](../specs/schemas/silver-establishment.md)
- [Establishment change-event schema](../specs/schemas/establishment-change-events.md)
- [Establishment release-summary schema](../specs/schemas/establishment-release-summaries.md)
- [Bronze establishment schema](../specs/schemas/bronze-receita-cnpj.md)
- [Data layers](data_layers.md)
- [Data quality](data-quality.md)
- [Status registry](status_registry.md)
