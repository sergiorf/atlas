# Atlas data layers

Atlas uses a one-way medallion-style flow: `raw -> bronze -> silver -> gold -> serving/index`.

Each layer has a distinct contract. Paths are relative to `apps/etl`. Atlas implements Receita
`estabelecimentos` and the company-data foundation through atomic silver; gold and serving/index
remain roadmap layers.

## Raw

Raw contains original downloaded and extracted source files beneath `data/raw/receita`. It exists for reproducibility. Never normalize, enrich, repair, or rewrite raw files, and never commit them to Git. Corrections belong in downstream transformations.

## Bronze

Bronze is parsed, source-shaped Parquet with named columns, basic safe typing, and ingestion metadata. The current `data/bronze/receita/estabelecimentos` output is partitioned by Brazilian state and remains close to Receita's layout.

Parsing, blank-to-null conversion, safe source typing, identifier assembly, provenance, and practical partitioning belong here. Cross-entity joins, business classifications, product metrics, and heavy business logic do not.

## Silver

Silver contains cleaned, validated, standardized tables with stable schemas. Reusable normalization, quality gates, deduplication, and joins between contracted Receita entities belong here.

The implemented establishment table standardizes state, postal and contact fields, CNAEs, and
active status; preserves provenance; and validates unique `cnpj_full` identifiers. Bronze may
contain malformed source-shaped rows. Silver quarantines invalid identifiers or registration
statuses before checking uniqueness among valid rows. Compact field deltas and durable release
summaries preserve history without retaining every prior full table.

The company-data foundation publishes companies, establishments, reference dimensions,
geography, and compact history as one atomic silver bundle. A bundle is not a new data layer and is
not gold: it is a versioned consistency boundary that prevents readers from mixing releases. Its
current pointer changes only after every required component and quality gate succeeds.
Company silver excludes all source rows belonging to a duplicated `cnpj_root` and reports them as
a non-blocking quarantine. This preserves primary-key uniqueness without choosing an arbitrary
company record, but it can leave establishments without a matching accepted company. Such absence
must not be interpreted as legal closure.

Partner, relationship, and tax-regime tables remain future contracted silver work. UI-specific
reports, lead lists, rankings, and graph presentation do not belong in silver.

## Gold

Gold contains business-ready, denormalized or purpose-specific query tables built from silver. Future `data/gold` outputs may include company profiles, company search, recent openings, CNAE summaries, and city or activity indicators. Derivations, unsupported cases, quality, and refresh behavior require explicit contracts. Atlas has not implemented gold yet.

## Serving and index layer

Serving stores are rebuildable projections of gold optimized for fast access. Future `data/serving` or `data/indexes` outputs may include DuckDB databases, search indexes, graph indexes, and API-ready snapshots. They may optimize physical layout, but must not own business meaning or introduce facts absent from gold. Current DuckDB examples are not a serving contract.

## Where work belongs

| Operation | Layer |
| --- | --- |
| Preserve a downloaded archive or extracted CSV | Raw |
| Assign columns, parse safe dates, record provenance | Bronze |
| Standardize an entity and validate its identifier | Silver |
| Build a contracted company profile or aggregate | Gold |
| Build a fast search or graph representation | Serving/index |

Use the earliest layer whose contract owns the operation. If business meaning is missing, revise the owning specification before implementing it downstream.
