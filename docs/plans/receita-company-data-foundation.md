# Receita company data foundation

**Status: Planned**  
**Roadmap owner: Atlas unified plan**  
**Implementation authorization: Not implemented**  
**Current implementation slice: Contract, synthetic-fixture, and local manifest-validation baseline**

All paths, commands, schemas, configuration, and quality behavior in this document are design
targets. They do not describe currently runnable Atlas behavior, create a compatibility
commitment, or authorize implementation. The existing May–July Receita `Estabelecimentos`
pipeline remains the only implemented data behavior.

## Decision and objective

Atlas will prioritize reusable company data coverage before a customer-facing lead export. The
v0.3a objective is to produce a coherent, reproducible silver foundation for May, June, and July:
root-level companies from `Empresas`; the six official Receita reference groups; a deterministic
Receita/TOM-to-IBGE municipality hierarchy; compact company change history; and an atomic bundle
that ties those outputs to the existing establishment state.

Success means an operator can acquire declared official inputs, build each release chronologically,
validate cross-table relationships, and either publish the complete bundle or leave the previous
bundle untouched. `export-leads` is intentionally deferred until gold company products in v0.3b.

## Non-goals

This tranche does not implement `Simples`, `Socios`, partner networks, CNAE business groupings,
gold tables, lead exports, population, areas or boundaries, OpenSearch, any other serving store,
API, website, dashboard, AI, sanctions, procurement, Docker, cloud, scheduling, or orchestration.
It does not change raw data, expose silver as a public product, or make redistribution claims.

## Source inventory and official evidence

The [source catalog](../source_catalog.md) owns the exact input inventory, support state, official
evidence, cadence, and redistribution status. The [source interpretation
contract](../specs/datasets/receita-company-reference-sources.md) owns layouts, snapshot agreement,
manifest requirements, and the gate that must pass before bronze ingestion. In summary, a bundle
requires same-release `Empresas`, the six Receita references, and supported `Estabelecimentos`, plus
captured TOM and IBGE geography inputs. Runtime inputs remain immutable outside Git; population and
boundaries are excluded.

## Planned data flow and outputs

### Raw and bronze

Raw acquisition extends the existing restartable manifest pattern without altering previously
downloaded bytes. `Empresas` follows the [planned raw layout](../specs/schemas/raw-receita-empresas.md)
and would write release-scoped [planned bronze](../specs/schemas/bronze-receita-empresas.md). Each of the
six reference groups would receive source-faithful release-scoped bronze output. TOM and IBGE responses
are captured as immutable reference inputs, not fetched during refresh.

### Silver company and history

The [planned `silver_company`](../specs/schemas/silver-company.md) is the latest valid root-level
company state keyed by `cnpj_root`. It resolves only official descriptions needed by the company
record. May seeds current; June and July produce compact events and durable summaries under the
[company history contract](../specs/schemas/company-change-history.md). Full historical copies are
not retained after publication.

### Reference and geography

The six [planned Receita dimensions](../specs/schemas/receita-reference-dimensions.md) remain
release-versioned. The [planned geography table](../specs/schemas/receita-ibge-municipality-geography.md)
uses exact identifiers to expose municipality, immediate region, intermediate region, state, and
macroregion. It does not use fuzzy names or contain population, area, geometry, or density.

### Bundle publication

A release bundle records one immutable bundle identifier and the exact release/hash of:

- establishment current state and its existing history summary;
- company current state and company history summary;
- all six Receita reference dimensions;
- the TOM and IBGE geography captures and resulting municipality hierarchy;
- quality reports, schema/status versions, and producer version.

Consumers resolve a single `current_bundle` pointer and never assemble independently current
tables. Gold and public consumers remain forbidden until their roadmap phase and contracts exist.

## Proposed operator interfaces

The following interfaces follow Atlas's current command hierarchy but are proposed only; none is
currently runnable or listed in the current manual:

```text
./atlas download receita company-data --release YYYY-MM
./atlas ingest receita company-data --release YYYY-MM
./atlas refresh receita company-data --release YYYY-MM
./atlas releases rebuild-company-data --from-release 2026-05 --to-release 2026-07
./atlas releases inspect-bundle --release YYYY-MM
```

`download` is the only command allowed network access. It acquires every required Receita group and
captures the configured TOM and IBGE reference inputs. `ingest` validates manifests and writes
release-scoped bronze outputs without publishing. `refresh` performs local-only transformation and
publishes only a release newer than the current bundle. `rebuild-company-data` requires an explicit
inclusive range and reconstructs it chronologically. Existing establishment commands remain
unchanged until implementation chooses and documents a migration.

Command delivery follows capability delivery: acquisition and bronze own `download` and `ingest`;
bundle publication owns `refresh`, `inspect-bundle`, and rebuild. Component transformations remain
internal unless an operator need justifies a public command.

## May–July backfill

The initial implementation target requires protected raw inputs for `2026-05`, `2026-06`, and
`2026-07`. Preflight verifies every required archive and hash before Spark work. May builds the
seed company state and receives a release summary but no mass insert events. June compares May to
June; July compares June to July. Establishment state/history already published for those months
must be validated and incorporated, not silently recomputed under changed semantics.

If existing establishment artifacts cannot satisfy the bundle manifest, implementation must stage
an explicit compatible rebuild from protected raw data and report it as data movement. Missing
months, mixed releases, or missing reference captures stop the three-month backfill.

## Publication and recovery model

Each planned refresh would write to a unique staging generation. The job would validate schemas, quality gates,
referential coverage, readable Parquet, summaries, and bundle-manifest hashes before atomically
switching the `current_bundle` pointer. The prior generation remains recoverable until the new
pointer and post-publication read check succeed.

Failure before the pointer swap leaves current untouched and records a failed run plus diagnostics.
Failure after the swap triggers pointer rollback to the recorded prior generation; neither raw
inputs nor previously valid history are deleted. Retry uses a new staging generation. Orphaned
staging data is removable only through a guarded derived-data cleanup command with dry-run support.

## Compatibility and data movement

This additive plan does not change the implemented establishment schema, existing commands, or raw
layout. New silver/reference paths are internal and initially have no compatibility guarantee.
Introducing `current_bundle` changes future publication coordination, so implementation must
version the manifest and keep current establishment readers working until they deliberately adopt
bundle resolution.

The May–July backfill reads immutable raw data and creates new bronze, silver, reference, history,
report, staging, and manifest artifacts. It must not move, rewrite, or delete raw files. Before any
derived migration, implementation records source/destination counts and bytes, collision checks,
available disk space, and rollback location. No generated data is committed.

## Quality gates

The [planned company and geography rules](../specs/quality/company-geography-quality-rules.md) are
bundle-blocking. In addition to per-table schema and key checks, publication requires:

- all declared input manifests and hashes, with a single CNPJ release;
- unique valid company roots and dimension codes;
- no ambiguous TOM-to-IBGE mapping and complete coverage for municipality codes used by candidate
  establishments;
- company reference integrity for required legal-nature and qualification joins;
- history arithmetic consistent with previous/current row counts;
- all bundle components readable and named in the manifest;
- no mutation of protected raw paths and no network access during refresh.

Diagnostics must include counts and representative bounded samples. The implementation may refine
thresholds only by updating the owning planned contracts before behavior ships.

## Automated acceptance checks

Small in-memory fixtures must cover numeric and alphanumeric roots, blanks, invalid widths and
characters, decimal-comma capital, duplicate roots, reference duplicates and misses, TOM/IBGE exact
joins, hierarchy extraction, May seeding, June/July insert-update-remove events, unchanged releases,
mixed-release rejection, failed publication, rollback, and deterministic rebuild output.

Focused parser/schema/transformation tests run first. Because implementation changes Scala ETL,
`sbt test` must then pass from `apps/etl`, followed by CLI help and dry-run checks proving proposed
commands are discoverable and refresh performs no network access.

## Full-data acceptance checks

An operator-run May–July acceptance records, for every release and dataset, archive counts and
hashes, source and accepted row counts, quarantine counts, distinct keys, duplicate conflicts,
reference misses, geography coverage, event counts, summary arithmetic, Parquet sizes, elapsed
time, peak practical resource observations, and final bundle contents. SQL spot checks inspect
representative companies and establishments across UFs and both numeric/alphanumeric identifier
forms.

Acceptance also verifies raw file counts and bytes are unchanged, only one coherent bundle is
visible, the previous generation can be restored, no unexpected network call occurred, and local
disk usage remains within the documented 32 GB RAM / 1 TB SSD operating target. Results and any
unsupported cases become implementation evidence in the owning specs and operations guidance.

## Implementation slices

1. Contract and fixture baseline: finalize source/version details and executable fixture checks.
2. Acquisition and bronze: add manifest-aware `Empresas` and reference inputs; only acquisition may use the network.
3. Reference/geography: build versioned dimensions and exact TOM-to-IBGE hierarchy with gates.
4. Company silver: normalize, resolve references, quarantine, and validate current company state.
5. History: add compact May–July company events and summaries.
6. Bundle publication: stage, validate, atomically switch, roll back, and expose inspection.
7. Backfill and documentation: run full-data acceptance and update manuals only for commands that
   have become implemented.

Each slice must update its owning specification and focused tests in the same change. An
implementation request may deliver smaller coherent slices, but may not bypass a missing contract
or present partially assembled state as a published bundle.

Slice 1 precedes all new bronze ingestion. Its Scala declarations are side-effect-free schemas,
and its committed inputs are synthetic fixtures. The versioned
[source-manifest contract](../specs/schemas/receita-company-source-manifest.md) now validates local
archives, ZIP membership, hashes, strict parser behavior, TOM, and IBGE hierarchy without writing
data. No public acquisition command was added and no publisher data was downloaded by this
implementation.

Slice 1 is not complete until an operator captures a selected real release and the validator
accepts its verified filenames, multiplicity, parser settings, sizes, and hashes. Until that
release-specific evidence exists, slice 2 remains blocked even though the schema and validator
tests pass.

## Deferred work

v0.3b owns `Simples`, reviewed `Socios`, gold company profiles, partner networks, business CNAE
groups, gold leads, and `export-leads`. Gold must precede OpenSearch or any other serving index,
API, website, dashboard, or public product. Population, boundaries, density, sanctions,
procurement, and AI retain their later unified-plan phases.
