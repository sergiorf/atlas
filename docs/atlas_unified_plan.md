# Atlas unified plan

This is the canonical product and delivery plan for Atlas. Focused documents in this directory expand individual decisions, but this file owns the complete product direction, scope boundaries, dataset sequence, target data products, commercial model, and phased roadmap.

## Product thesis

Atlas turns Brazilian public company data into clean company profiles, lead lists, public-data risk flags, procurement intelligence, and graph-ready market intelligence that can be sold through exports, API access, a user interface, and later a paid natural-language query assistant.

Atlas is not a generic data lake or a mirror of public files. Public datasets are inputs. The defensible product assets are the schemas, normalization and entity-resolution logic, quality controls, CNAE groupings, enrichment rules, explainable risk and lead signals, gold-table designs, indexing strategy, serving contracts, and validated query layer.

The first commercial question Atlas should eventually answer well is:

> Which active software companies opened this month in Recife?

Answering it requires trustworthy establishment status, municipality and state, opening date, and primary or secondary CNAE classification. The roadmap is ordered to deliver that capability before expanding into broader intelligence products.

## Customers and product surfaces

Atlas is intended to support:

- company and establishment search;
- B2B lead generation by geography, sector, status, and opening date;
- business-friendly company profiles;
- explainable public-data risk checks, explicitly not a credit score;
- public procurement and supplier intelligence;
- graph-ready market and geographic aggregates;
- CSV and Parquet exports;
- API access and a web interface after the data products stabilize;
- a paid, gated natural-language query assistant in a later phase.

The ETL is the foundation, not the customer-facing product. UI and API components must consume trusted gold tables or serving stores derived from them; they must never query raw public files directly.

## Architecture and monorepo boundaries

The durable flow is:

```text
Raw public data
  Receita / geography / sanctions / procurement
        ↓
apps/etl
        ↓
Bronze source-faithful tables
        ↓
Silver normalized and joined tables
        ↓
Gold business-ready tables and aggregates
        ↓
future apps/indexer
        ↓
search indexes and serving databases
        ↓
future apps/api
        ↓
future apps/ui / exports / paid AI assistant
```

The monorepo grows around clear responsibilities:

- `apps/etl`: local Scala/Spark pipelines that produce bronze, silver, and gold data;
- `apps/indexer`: future loaders from gold Parquet into selected serving stores;
- `apps/api`: future search, profile, export, risk, procurement, and query endpoints;
- `apps/ui`: future public site and customer dashboard;
- `packages/domain`: future shared CNPJ, company profile, lead filter, risk flag, and query-intent models;
- `packages/config`: future shared CNAE, municipality, and business-category configuration;
- `docs`: product direction, data contracts, user guidance, operations, and roadmap decisions;
- `infra`: future deployment assets only when deployment becomes an active phase.

Only `apps/etl` and documentation are implemented in v0.1. Documenting future components does not authorize building them early.

## Data layers and product contract

- Raw preserves downloaded source material exactly and remains outside Git.
- Bronze parses source files with explicit schemas, retains source meaning, adds stable identifiers and provenance, and writes Parquet.
- Silver normalizes entities, codes, dates, geography, reference values, and cross-source joins.
- Gold expresses sellable business concepts, query-ready profiles, lead lists, risk flags, supplier profiles, and aggregates.
- Exports are controlled projections of gold tables, not ad hoc raw-file dumps.
- Serving stores are disposable indexes or databases rebuilt from gold tables.

Gold tables are the principal product asset and contract between data engineering and every future product surface.

## Dataset strategy and sequencing

Atlas adds datasets only when they unlock a concrete customer question. It does not ingest every available Brazilian dataset at once.

### 1. Receita Federal CNPJ — identity spine

Receita has the highest priority. It supplies identity, registration status, names, legal attributes, establishments, locations, CNAEs, opening dates, partners, and tax-regime signals.

Important source groups are Empresas, Estabelecimentos, Socios, Simples, CNAE, Municipios, Naturezas Juridicas, Paises, Qualificacoes de Socios, and Motivos de Situacao Cadastral.

v0.1 implements only Estabelecimentos to bronze because it provides the first useful lead dimensions: location, opening date, registration status, primary and secondary CNAE, trade name, email, phone, and headquarters/branch status.

Future Receita silver tables include:

- `silver_company`;
- `silver_establishment`;
- `silver_partner`;
- `silver_company_tax_regime`;
- `silver_cnae`;
- `silver_municipality`;
- `silver_legal_nature`;
- `silver_partner_qualification`.

### 2. IBGE and reference geography

Geographic enrichment follows the basic Receita pipeline. It resolves municipality, state, and region names; enables normalized location filters; and supports population joins, density measures, regional comparisons, and companies-per-thousand-inhabitants metrics.

Planned outputs include `silver_municipality`, `silver_state`, `silver_region`, and `gold_company_density_by_city`. v0.1 preserves Receita municipality codes but does not implement these joins.

### 3. CGU and Portal da Transparencia sanctions

CEIS, CNEP, and CEPIM add explainable public-data risk intelligence after a company profile exists. A future `silver_sanction` should preserve source, company identifiers, entity name, sanction type and dates, sanctioning body, process number, and raw source identifier.

A future `gold_company_risk` should expose company identity and registration status, source-specific flags, sanction sources and count, latest sanction date, an explainable risk score, and human-readable reasons.

This must be described as public-data risk flags, never as a credit score. An initial explainable scale may use 0 for no public risk found, 30 for inactive or non-active registration, 70 for one public sanction, and 90 for multiple or severe sanctions. These rules remain future work and require validation before production use.

### 4. PNCP public procurement

PNCP enrichment answers which companies sell to government, where, in what categories, to which buyers, and for what value. A future `silver_public_contract` preserves contract and supplier identity, buyer, value and dates, category, description, and source URL.

A future `gold_public_supplier_profile` aggregates contract counts and values, buyer entities, last contract date, and leading procurement categories by company.

### 5. Graph-ready aggregates

Charts begin as tested gold aggregate tables, not as a dashboard. Planned questions include new companies by month, company counts by sector and location, active versus inactive companies, openings by CNAE group, age distributions, and companies per thousand inhabitants.

### 6. Later optional datasets

- RAIS, CAGED, and Novo CAGED may support public, non-identified employment and labor-market statistics. Atlas must not build employee-level or sensitive-person features.
- ComexStat may support sector, municipality, product, and country trade intelligence.
- CVM may enrich listed and public companies but cannot provide financials for all private companies.
- Banco Central data may add macroeconomic and regional credit-market context, not company-level bureau data.
- DataJud and CNJ data require a separate legal, ethical, product-risk, and technical review before inclusion.

These sources are not early-version implementation targets.

## CNPJ and entity modeling

Atlas always preserves:

- `cnpj_root`: the eight-digit company root;
- `cnpj_branch`: the four-digit establishment order;
- `cnpj_check`: the two check digits;
- `cnpj_full`: the normalized fourteen-digit establishment identifier.

Company records are root-level. Establishment records are branch/location-level. Location, CNAE, status, contact, and opening-date searches are generally establishment-level. Atlas must not collapse those concepts into one ambiguous entity.

Normalization removes punctuation, preserves digits, left-pads components to their official widths, and builds `cnpj_full` from root, branch, and check components. v0.1 validates length; checksum validation is a later enhancement.

## Target gold tables

The planned business-ready portfolio includes:

- `gold_company_profile`;
- `gold_leads_new_companies`;
- `gold_company_partner_network`;
- `gold_company_risk`;
- `gold_public_supplier_profile`;
- `gold_company_openings_by_month_city_cnae`;
- `gold_company_count_by_city_cnae_status`;
- `gold_company_age_distribution_by_city`;
- `gold_company_density_by_city`.

The first lead product filters active establishments by city/state, opening-date window, and business-defined CNAE groups such as software services. CNAE group configuration is versioned product logic rather than a hard-coded UI concern.

## Graph strategy

Graph functionality initially means reproducible aggregate tables plus DuckDB demonstration queries. It does not mean building a dashboard in the ETL repository. UI charts arrive only after aggregate definitions, quality rules, and refresh behavior are stable.

## Serving and indexing strategy

A future `apps/indexer` reads gold Parquet and loads the serving technology best matched to measured needs. Candidates include PostgreSQL, DuckDB, Meilisearch, ClickHouse, and OpenSearch. No database is selected merely because it is fashionable; query patterns, operating cost, local/deployment constraints, full-text needs, and aggregation workload drive the decision.

Gold remains authoritative. Serving databases and search indexes are derived and rebuildable.

## AI assistant strategy

AI is a later paid and gated product capability because provider calls cost money and create security and reliability obligations.

The assistant translates natural language into a constrained, validated JSON query intent. It must not generate or execute arbitrary SQL. The API validates allowed dimensions, filters, operators, limits, authorization, and cost before querying trusted serving data. Provider keys remain backend-only and must never be exposed to browsers.

Example future intents include active software companies opened this month in Recife, sanctioned suppliers in a state, or procurement-active companies in a sector. The deterministic query layer—not the model—owns data access semantics.

## v0.1 ETL scope

The first milestone is deliberately narrow:

1. Load HOCON configuration.
2. Start a local Spark session.
3. Read one or more Receita Estabelecimentos CSV files with an explicit string-first schema.
4. Use Receita-compatible delimiter, header, quote, and configurable encoding options.
5. Normalize CNPJ components and create `cnpj_full` and `is_headquarters`.
6. Add source name, source file, and ingestion timestamp.
7. Write state-partitioned bronze Parquet.
8. Produce JSON and Markdown quality reports.
9. Supply DuckDB examples and product/architecture documentation.

The command contract from `apps/etl` is:

```bash
sbt compile
sbt test
sbt "runMain atlas.Main ingest-receita-estabelecimentos"
```

v0.1 does not implement Empresas, Socios, Simples, sanctions, PNCP, API, UI, search, AI, billing, dashboards, Docker, cloud deployment, streaming, or orchestration platforms.

## Bronze establishment contract

The explicit source schema covers CNPJ components, headquarters/branch code, trade name, registration status and date/reason, foreign city and country, opening date, primary and secondary CNAEs, address, postal code, state and municipality code, phones, fax, email, and special status/date.

Normalized bronze adds:

- `cnpj_full`;
- `is_headquarters`;
- `source_name`;
- `source_file`;
- `ingestion_timestamp`.

Bronze output is stored under `apps/etl/data/bronze/receita/estabelecimentos` relative to the monorepo, with practical state partitioning that avoids excessive tiny files.

## Data quality policy

Data quality is customer-facing product behavior, not an afterthought. Every job reports at least:

- dataset name;
- input and output paths;
- row count;
- invalid CNPJ-length count when applicable;
- null mandatory identifiers;
- null opening dates and primary CNAEs for establishments;
- run timestamp.

v0.1 writes machine-readable JSON and a human-readable Markdown summary beside the bronze dataset. Later stages add uniqueness, referential integrity, domain-code, freshness, and cross-source reconciliation checks.

## Raw-data safety and migration

Raw public files are large, local, and never committed. They must not be deleted or overwritten during code or layout changes. Moves require source/destination inspection, conflict checks, and post-move file-count and byte-count verification. Interrupted `.part` files remain resumable.

The existing 2026-06 snapshot was moved intact to `apps/etl/data/raw/receita/2026-06`, including its partial archive. Future downloads default to the same Receita raw-data hierarchy.

## Laptop and operational constraints

The target development machine has 32 GB RAM and a 1 TB SSD. Spark runs locally and memory settings remain configurable.

Pipeline rules are:

- never load all datasets into driver memory;
- never call `collect()` on large DataFrames;
- never convert large DataFrames to local Scala collections;
- process file groups incrementally where practical;
- materialize Parquet after major stages;
- separate raw, bronze, silver, gold, and export storage;
- make extracted temporary CSV cleanup possible;
- minimize full shuffles and tiny-file creation;
- keep routine development independent of Docker, cloud services, and clusters.

The selected ETL stack is Scala 2.12, Apache Spark 3.5 locally, sbt, Parquet, Typesafe Config/HOCON, ScalaTest, scalafmt, and optional DuckDB for local inspection.

Kafka, Flink, Spark Streaming, Airflow, Kubernetes, custom databases, and cloud services are deliberately excluded from v0.1.

## Delivery roadmap

### v0.1 — local ETL foundation

- establish the monorepo and `apps/etl`;
- preserve and safely locate existing raw files;
- ingest Receita Estabelecimentos to bronze Parquet;
- normalize CNPJ identifiers and provenance;
- generate quality reports;
- add DuckDB examples and architecture/product documentation.

### v0.2 — normalized establishments and first lead export

- build silver establishment normalization — implemented as the first v0.2 slice;
- add municipality lookup support;
- operationalize CNAE group filters;
- ship the first export-leads command.

### v0.3 — complete Receita company spine

- ingest Empresas, Socios, and Simples;
- add required Receita reference tables;
- build company profile, partner-network, and new-company lead gold tables.

### v0.4 — graph-ready products

- build openings, counts, age, and density aggregates;
- add DuckDB graph/demo queries;
- export aggregates as CSV and Parquet.

### v0.5 — public-data risk

- ingest CEIS, CNEP, and CEPIM;
- build `gold_company_risk`;
- add explainable risk flags to company profiles.

### v0.6 — procurement intelligence

- ingest PNCP data;
- build `gold_public_supplier_profile`.

### v0.7 — serving and indexing

- introduce a separate indexer;
- load gold tables into the selected databases or search stores.

### v0.8 — API and simple UI

- expose validated search, profile, lead, export, risk, and procurement capabilities;
- add a minimal customer interface.

### v0.9 — paid AI query assistant

- add gated natural-language-to-query-intent functionality;
- enforce backend validation, authorization, limits, and cost controls.

### Later

Evaluate labor, trade, public-company financial, macroeconomic, and judicial datasets only against concrete customer demand and legal/ethical constraints.

## Commercial, legal, and IP principles

Atlas is intended as private commercial IP. Do not add public-library boilerplate that implies otherwise. Never commit API keys, provider tokens, credentials, `.env` files, private company data, generated public-data copies, or large runtime artifacts.

Public datasets themselves are not proprietary. Atlas creates value through trusted transformations, unified identifiers, quality and provenance, business taxonomies, explainable scores and signals, query-ready tables, serving design, and product workflows.

Public-data risk outputs must remain factual, explainable, source-attributed, and clearly distinguished from credit scoring. Sensitive or person-level features require legal and ethical review, and are outside the current plan.

## Definition of done and implementation discipline

A phase is complete only when its documented commands work, tests use small in-memory fixtures, configuration and schema changes are documented, data safety has been verified, and the relevant product question can be answered from the intended layer.

Every feature follows the repository [feature development workflow](feature_development_workflow.md): classify, inspect, design and plan proportionately, implement a coherent slice, test, document, compare against the plan, and report evidence and unverified items. Scope may not silently expand into a later phase.

For v0.1 specifically, done means the ingestion command loads configuration, starts local Spark, reads configured Estabelecimentos files, applies the explicit schema, constructs normalized CNPJ fields, adds metadata, writes bronze Parquet, and emits both quality reports. The required DuckDB bronze example must read the resulting Parquet.

## Supporting documents

- [Documentation index](index.md)
- [Feature development workflow](feature_development_workflow.md)
- [Source catalog](source_catalog.md)
- [Data product contract](data_product_contract.md)
- [Manual](manual/index.md)
- [Receita CNPJ dataset specification](specs/datasets/receita-cnpj.md)
- [Local ETL operations](operations/local-etl.md)
- [Future datasets](roadmap/datasets.md)
- [Future gold tables](roadmap/gold-tables.md)
- [Future serving layer](roadmap/serving-layer.md)
- [Future graph aggregates](roadmap/graph-aggregates.md)
- [Future AI query assistant](roadmap/ai-query-assistant.md)
- [Release roadmap](roadmap/release-roadmap.md)
