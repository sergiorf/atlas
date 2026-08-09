# Serving projection

- **Status:** Planned
- **Manifest version:** `1`
- **Query contract:** `v1`
- **Milestone:** v0.4 serving foundation

The serving projection is a disposable, immutable generation derived from one validated atomic
company-data bundle. DuckDB is the first measured candidate, not an accepted technology until the
representative and national gates pass.

## Inputs

The first projection reads only the bundle components `gold_company_profiles`,
`gold_company_search_names`, `gold_company_search_prominence`, and
`gold_leads_new_companies`. The search-name and prominence inputs are planned v0.3c prerequisites;
the projection must not substitute selective lead rows for comprehensive name coverage or derive
private prominence semantics. It resolves every input through the selected bundle manifest,
verifies its recorded hash, and rejects guessed paths, traversal, symlinks, mixed releases,
unsupported schemas, raw/silver inputs, or incomplete validation evidence.

The indexer owns its file-format models and must not import ETL implementation classes. A bundle
validation attestation is stored separately from the immutable bundle and is bound to the exact
bundle-manifest SHA-256. It records validator and contract versions, validation mode and outcome,
completion time, and the component hashes reviewed. Serving requires full validation. The serving
quality contract decides which warnings remain blocking.

## Projection semantics

Candidate logical relations are:

- `serving_metadata`, one row for generation and source identity;
- `company_profiles`, one row per `cnpj_root`;
- `company_search_documents`, comprehensive current and historically observed legal-name,
  trade-name, root-CNPJ, and establishment-CNPJ evidence;
- `company_prominence`, versioned prominence tier and reason evidence;
- `lead_search`, lead rows enriched by an exact profile join.

The projection may rename documented fields, add versioned normalized search keys, create physical
indexes, sort data, and duplicate values. It may combine contracted match class and prominence
according to the query contract while returning both forms of evidence. It may not reclassify
CNAEs, infer status, collapse unknown tax states, invent geographic precedence, derive private
prominence semantics, or create risk or opaque ranking scores.

## Runtime layout

Generated state lives under the configured data root, never below the source tree by default:

```text
<data-root>/_atlas/serving/
  generations/<serving-generation-id>/
    atlas.duckdb
    serving-manifest.json
    validation-report.json
    benchmark-report.json
  failed/<serving-generation-id>/
  current_generation.json
  serving.lock
```

Staging, generations, and pointer temporary files must be on the same filesystem. Lack of atomic
move support is blocking.

## Manifest

The serving manifest records manifest/query/normalization/relevance versions, generation and
source bundle IDs, source manifest hash and release, relative component paths and hashes,
taxonomy/calculation versions, indexer and database versions, start/completion timestamps, row
counts, database hash and size, validation outcome, and predecessor generation ID. It contains no
absolute machine path.

## Build and publication

A build acquires the serving lock, resolves and validates its source, creates a unique staging
generation, builds the database, closes and reopens it read-only, runs all blocking validation,
and finalizes evidence before promotion. Generations are immutable after promotion.

The generation directory is promoted atomically before `current_generation.json` is replaced
atomically. Readers resolve the pointer once per request and retain that generation for the whole
operation. A failed post-switch verification restores the predecessor pointer; the new validated
generation remains inactive rather than being mislabeled as a failed build.

Current plus two predecessor generations are retained initially. Retained validated generations
may serve generation-bound cursors. Rollback changes only the serving pointer and never edits or
rolls back the gold bundle. Concurrent build, rollback, and cleanup operations are lock-protected.

## Technology gate

DuckDB is accepted only if correctness, judged relevance, pagination, streaming export,
concurrency, rebuild, memory, storage, cutover, and rollback gates pass. If a workload fails after
reasonable tuning, the same fixture and workload are used to evaluate PostgreSQL or, for judged
search failures, a dedicated search engine. v0.4 must not retain unused serving technologies.

HTTP, UI, identity, billing, saved state, alerts, cloud deployment, incremental generation
mutation, partner-network serving, and arbitrary SQL are outside this contract.
