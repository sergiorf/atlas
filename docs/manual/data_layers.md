# Atlas data layers

Atlas uses a one-way `raw -> bronze -> silver -> gold -> serving` architecture. Raw through gold
and controlled exports are implemented for the current Receita company products. Serving stores,
an API, and an application remain roadmap work.

| Layer | Current purpose | Representative implemented data |
| --- | --- | --- |
| Raw | Preserve publisher evidence and manifests unchanged | Receita archives and extracted files, TOM CSV, IBGE response |
| Bronze | Parse source layouts into provenance-rich Parquet | Establishments, companies, references, Socios, Simples |
| Silver | Normalize reusable entities, joins, and history | Companies, establishments, geography, tax regime, partners, relationships |
| Gold | Publish contracted business concepts | Company profiles, partner network, relationship paths, new-company leads |
| Serving | Rebuild gold projections for API access | Not implemented |

## Raw

Raw data exists for reproducibility. Never normalize, enrich, repair, overwrite, or manually clean
publisher files. Interrupted downloads remain resumable, and raw data is never a cleanup candidate.

## Bronze

Bronze assigns explicit schemas, performs safe source parsing, retains source meaning, and adds
stable identifiers and provenance. It may retain malformed or incomplete source-shaped rows so
downstream quality behavior remains explicit. Cross-entity business semantics do not belong here.

## Silver

Silver owns stable entity keys, normalization, reusable reference joins, deduplication rules,
quality gates, and compact observation history. The atomic company-data bundle ensures consumers do
not combine components from different releases; a bundle is a publication boundary, not a layer.

Silver tables are internal data contracts. Their reusable semantics may feed several gold products,
but they are not an API or customer-facing query contract by themselves.

## Gold

Gold owns business-ready, query-oriented products with documented grain, derivations, limitations,
freshness, and quality. Current products include company profiles, evidence-preserving company
partner networks, bounded relationship paths, and establishment-grained new-company leads.

Controlled exports are bounded projections of contracted gold tables. They do not bypass product
rules or expose internal natural-person diagnostics.

## Serving and product surfaces

Future serving databases or indexes will be disposable projections of gold optimized for measured
query patterns. They must not create facts or rules absent from gold. The future API and application
will consume these trusted products rather than query raw, bronze, or ad hoc silver data.

See [Architecture](../architecture.md) for diagrams, [Datasets](datasets.md) for table grain and
important attributes, and the [data product contract](../data_product_contract.md) for invariants.
