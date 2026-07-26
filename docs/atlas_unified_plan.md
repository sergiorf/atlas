# Atlas unified plan

This is the canonical product and delivery plan for Atlas. Focused documents in this directory expand individual decisions, but this file owns the complete product direction, scope boundaries, dataset sequence, target data products, commercial model, and phased roadmap.

The [Dataset and source catalog](source_catalog.md) is the canonical inventory of named inputs
and their support status, access evidence, licensing notes, and refresh assumptions. Catalog
entries do not change the priorities or sequencing owned here.

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

Only `apps/etl` and documentation exist today. Documenting future components does not authorize building them early.

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

v0.1 implemented Estabelecimentos to bronze because it provides the first useful lead dimensions: location, opening date, registration status, primary and secondary CNAE, trade name, email, phone, and headquarters/branch status. The local ETL foundation now also includes silver establishment normalization and compact release-to-release change events for selected establishment fields.

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

- `cnpj_root`: the eight-character uppercase alphanumeric company root;
- `cnpj_branch`: the four-character uppercase alphanumeric establishment order;
- `cnpj_check`: the two check digits;
- `cnpj_full`: the normalized fourteen-character establishment identifier.

Company records are root-level. Establishment records are branch/location-level. Location, CNAE, status, contact, and opening-date searches are generally establishment-level. Atlas must not collapse those concepts into one ambiguous entity.

Normalization trims whitespace, uppercases letters, removes the standard `.`, `/`, and `-` display mask, left-pads under-width components with zeros, and builds `cnpj_full` from root, branch, and check components without numeric conversion. Numeric and alphanumeric identifiers coexist: only the first twelve positions may contain `0-9` or `A-Z`, while the two check positions remain numeric. Identifiers must remain strings. Atlas validates canonical structure but does not validate checksums.

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

## Current implementation boundary

Atlas currently acquires operator-selected monthly Receita `Estabelecimentos` archives and processes
them through bronze and the first v0.2 silver slice. The implemented workflow provides:

- restartable raw acquisition with manifests and raw-stage status;
- release-scoped, state-partitioned bronze Parquet and diagnostic quality reports;
- a validated latest-current silver establishment table;
- quarantine and publication gates for malformed and duplicate identifiers;
- compact selected-field change events and one analytical summary per published release;
- release inventory, guarded derived-data cleanup, chronological rebuild, and local status commands;
- DuckDB guidance for local inspection.

Refresh never initiates network I/O. Operators acquire and inspect immutable raw input before
publishing derived state. Exact fields, paths, quality behavior, commands, and recovery procedures
belong to the implemented [specifications](index.md#implemented-specifications) and
[operations guides](operations/local-etl.md), rather than this product plan.

The v0.3a company-data foundation is implemented through atomic silver publication. An operator can
download a selected `Empresas` and Receita-reference release plus TOM and IBGE Localities, combine
it with the matching establishment release, build company and reference bronze, normalize company
and geography silver, create compact history, and publish one immutable same-release bundle. The
May–July full-data acceptance remains operator-run.

CNAE business groups, lead exports, gold, serving,
API, UI, search, AI, billing, sanctions, procurement, Docker, cloud deployment, streaming, and
orchestration platforms remain outside the implemented boundary.

## Raw-data safety and migration

Raw public files are large, local, and never committed. They must not be deleted or overwritten during code or layout changes. Moves require source/destination inspection, conflict checks, and post-move file-count and byte-count verification. Interrupted `.part` files remain resumable.

The default local hierarchy stores monthly snapshots beneath `apps/etl/data/raw/receita/<YYYY-MM>`.
Operational migration or recovery details belong in the refresh runbook, not in the product plan.

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

### v0.2 — normalized establishments and establishment history

- build silver establishment normalization — implemented;
- add local CLI, release status, lifecycle controls, compact establishment change events, and
  release summaries — implemented;
- the implemented May–July establishment slice is complete;
- municipality lookup, CNAE business groups, and `export-leads` are deferred to the company-product tranche.

### v0.3a — Receita company data foundation (active next tranche)

- ingest monthly `Empresas` through raw, bronze, and `silver_company`;
- ingest the six official Receita reference groups: CNAE, Municipios, Naturezas Juridicas,
  Paises, Qualificacoes de Socios, and Motivos de Situacao Cadastral;
- map Receita municipality codes through the official TOM table to the IBGE Localities hierarchy;
- backfill May–July company state and compact company history;
- publish establishments, companies, references, geography, and history as one coherent release bundle.

This foundation is implemented and documented in the [company data foundation
plan](plans/receita-company-data-foundation.md). The remaining v0.3a work is ordered as follows:

1. Complete the operator-run May–July full-data acceptance, including counts, quality evidence,
   resource observations, and representative bundle queries. Do not change the acquisition
   workflow during this acceptance.
2. After acceptance and before v0.3b, make
   `./atlas download receita company-data --release YYYY-MM` coordinate all raw inputs required by
   the matching atomic refresh, including the existing establishment acquisition module, and run a
   final same-release readiness check. Preserve the separate immutable raw layouts, manifests,
   resumability, and downloader implementations. Keep
   `./atlas download receita estabelecimentos --release YYYY-MM` as an advanced recovery and
   compatibility command.

These exit tasks establish reusable joined silver coverage and a safer routine monthly acquisition
interface before customer-facing company products.

### v0.3b — company products

- ingest `Simples` and reviewed `Socios` data;
- build gold company profiles and partner networks;
- operationalize versioned CNAE business groups;
- build gold lead products and expose controlled lead exports, including the deferred
  `export-leads` command.

Gold remains mandatory before any serving index, API, website, or public product consumes these
data. Silver foundation tables are internal contracts, not customer-facing products.

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

Completed milestones retain their implemented specifications and verification evidence. Active
milestones are complete only when their documented product question can be answered from the
intended layer without bypassing a missing contract.

## Supporting documents

- [Documentation index](index.md)
- [Feature development workflow](feature_development_workflow.md)
- [Dataset and source catalog](source_catalog.md)
- [Data product contract](data_product_contract.md)
- [Manual](manual/index.md)
- [Receita CNPJ dataset specification](specs/datasets/receita-cnpj.md)
- [Local ETL operations](operations/local-etl.md)
- [Release roadmap](roadmap/release-roadmap.md)
