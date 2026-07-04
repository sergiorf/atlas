# Atlas data product contract

This document defines global invariants across Atlas data products. Dataset and schema specifications refine these rules but may not silently contradict them.

## Contract levels

- **Internal schema contract:** a reproducible interface between pipeline stages. It may evolve with coordinated migrations and tests.
- **Published data-product contract:** a table, export, or golden dataset exposed to consumers. Changes require compatibility analysis and migration notes.
- **Public query contract:** API, index, or UI behavior visible outside the pipeline. It requires explicit filters, limits, errors, freshness, and unsupported cases.

Bronze Receita output in v0.1 is an internal schema contract. Atlas has no public API, index, UI, or production gold contract yet.

## Data layers

- **Raw:** immutable source bytes, outside Git. Atlas never edits, normalizes, or deletes them in place.
- **Bronze:** source-faithful parsing with explicit schemas, stable identifiers, provenance, and conservative type conversion.
- **Silver:** future normalized entities, reference joins, and cross-source reconciliation.
- **Gold:** future business-ready, queryable product concepts.
- **Exports:** future controlled projections of gold contracts.
- **Serving stores:** future disposable, rebuildable derivatives of gold data.

Consumers must not bypass a missing shared contract or invent private semantics in notebooks, indexes, APIs, or interfaces.

## Reproducibility and lineage

Every derived artifact must identify declared source inputs, versioned code and configuration, transformation rules, and refresh time. Published fields require a documented source or derivation. Unknown and unsupported values must be represented deterministically rather than guessed.

Raw, bronze, silver, gold, exports, and temporary files remain physically separate. Moves of raw data require source and destination inspection plus file-count and byte-count verification.

## Compatibility

Field removal, rename, type change, key change, semantic reinterpretation, partition change, and query-behavior change require explicit impact analysis. Published contracts additionally require migration notes and affected consumer tests. Additive changes are not automatically compatible when they alter nullability, uniqueness, ranking, or resource expectations.

## Quality and diagnostics

Each job reports its dataset, inputs, outputs, row count, run time, and applicable identifier, completeness, uniqueness, domain, and referential metrics. Specifications define whether a rule warns, rejects, quarantines, or merely reports. A successful write does not imply acceptable data quality.

## Freshness, privacy, and resources

Every supported product surface states its source snapshot, refresh mechanism, expected freshness, and failure visibility. Atlas does not infer real-time freshness from a completed batch.

Credentials, private data, and sensitive person-level features are outside Git. Privacy, licensing, redistribution, or access-control changes require explicit review. Pipelines must respect documented laptop, storage, shuffle, and serving budgets and avoid collecting large datasets to the driver.
