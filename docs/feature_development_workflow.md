# Atlas feature development workflow

This workflow turns a data-product idea into verified implementation without creating a competing roadmap. The [Atlas unified plan](atlas_unified_plan.md) owns direction, priority, milestones, sequencing, and product scope. Dataset, schema, quality, API, and indexing specifications own durable implemented behavior.

The default path for substantial work is:

> roadmap alignment → repository and source research → data-contract design → implementation plan → test-led execution → data and documentation verification

The workflow applies to humans and coding agents. It does not require a particular planning tool, worktree strategy, commit cadence, or use of subagents.

## Classify the change

### Trivial fix

A local correction with no new data interpretation, published schema change, architectural decision, quality rule, or user-visible behavior. Examples include a typo, broken link, incorrect log message, or regression against an existing explicit parser contract.

Inspect the affected code, tests, and documentation; make the smallest justified change; run focused verification; and review the diff. A separate design is unnecessary.

### Bounded enhancement

A contained change whose owner, contract, and behavior are already clear. Examples include expanded parsing within an established source contract, a derived internal field already specified, a stronger quality check without a published-schema change, an operational command for an existing stage, or a behavior-preserving local optimization.

Confirm roadmap compatibility, inspect the owning specifications, state success criteria and non-goals, and implement with affected tests and documentation.

### Substantial data-product feature

Use the complete workflow when a change:

- introduces a dataset, source, table, index, API, UI surface, or golden view;
- changes a published schema, query behavior, or golden-table meaning;
- changes refresh cadence, incremental loading, snapshotting, partitioning, versioning, storage layout, or resource guarantees;
- changes lineage, provenance, deduplication, identity resolution, matching, or quality semantics;
- changes privacy, licensing, redistribution, access control, or compliance boundaries;
- crosses ingestion, normalization, quality, indexing, API, UI, or operations boundaries;
- introduces a monetized capability or requires unresolved product or architecture decisions.

When uncertain, research first and reclassify after inspecting current contracts and implementation.

## Align with the unified plan

For substantial work, record the milestone and workstream, priority and active tranche, owning subsystem, deferred dependencies, and effect on the supported or monetized product surface. A conflict with accepted contracts, source boundaries, privacy assumptions, or deferred scope requires an explicit roadmap decision before implementation.

## Research repository and sources

Use repository and source evidence rather than guesses. Inspect the relevant official source material and representative fixtures; ingestion, schema, transformation, and quality code; public query behavior; tests; operations; refresh and recovery assumptions; compatibility constraints; and unsupported cases.

Resolve discoverable questions from code, fixtures, generated samples, and documentation. Escalate only genuine product choices or conflicts that evidence cannot settle.

Research must preserve these invariants:

- raw source data remains immutable;
- every published field has a documented source or derivation;
- golden tables are reproducible from declared inputs and transformations;
- notebooks, interfaces, APIs, and indexes do not invent private semantics;
- unknown and unsupported values behave explicitly and deterministically;
- quality failures produce coherent diagnostics;
- freshness and limitations are visible to users.

## Establish the data-contract design

Before planning a substantial implementation, establish:

- goal, observable outcome, non-goals, and unsupported cases;
- source ownership, location, licensing notes, and freshness assumptions;
- fields, types, keys, partitions, nullability, meaning, and compatibility;
- transformations, normalization, deduplication, enrichment, and lineage;
- quality checks, failure behavior, diagnostics, and recovery;
- query or interface behavior with representative examples;
- performance, storage, refresh, privacy, and redistribution expectations;
- viable alternatives and why the selected design is preferred.

Record durable decisions in the owning specification. Require explicit human direction for compatibility breaks, privacy or licensing changes, roadmap expansion, or unresolved product tradeoffs—not for choices already settled by canonical documents.

An active specification describes implemented behavior unless marked `Status: Planned`. Planned specifications do not authorize implementation or create compatibility commitments.

## Write a decision-complete plan

A substantial plan records success criteria, roadmap alignment, ownership, evidence, affected contracts, chosen design, rejected alternatives, non-goals, compatibility, diagnostics, documentation, and completion checks.

Organize it into small vertical tasks. Each task identifies the delivered behavior, likely implementation and test locations, prerequisites, test or data check, documentation change, and focused verification. Put documentation in the task that makes it true. Prefer semantic milestones over mechanical file-edit lists.

## Execute through quality gates

Apply these gates proportionately:

1. **Baseline:** run the narrowest relevant existing tests or fixture checks and record unrelated failures.
2. **Red:** add or identify a focused failing test, schema assertion, sample check, or quality rule when practical.
3. **Green:** implement the smallest coherent slice satisfying the contract.
4. **Focused verification:** run the closest parser, schema, transformation, quality, or query checks after each task.
5. **Broader verification:** run affected integration tests and `sbt test` for Scala behavior.
6. **Data verification:** inspect representative generated samples for schema, counts, nulls, keys, and edge cases; a full public-data run is not required for routine development.
7. **Review:** inspect the complete diff for drift, duplicate semantics, hidden interface logic, stale documentation, unsupported fallbacks, and unrelated changes.
8. **Completion evidence:** report commands, results, sample metrics, limitations, data movements, and plan deviations.

Do not claim completion from inspection alone when executable verification exists. Passing tests do not override an incorrect contract, undocumented field, or broken lineage.

## Documentation gate

Update every applicable owner with the implementation:

- `docs/manual/` for purpose, examples, behavior, freshness, quality expectations, and limitations;
- `docs/source_catalog.md` for inputs, licensing notes, refresh assumptions, and ingestion boundaries;
- `docs/specs/datasets/` for source interpretation;
- `docs/specs/schemas/` for fields, types, keys, partitions, nullability, and meaning;
- `docs/specs/quality/` for validation, completeness, diagnostics, and failure behavior;
- future API and indexing specifications for filters, ranking, pagination, and responses;
- `docs/operations/` for commands, refresh, recovery, resources, and troubleshooting;
- README and documentation indexes for onboarding or navigation changes;
- the unified plan only when roadmap state, priorities, milestones, or the immediate queue changes.

Do not present planned capabilities as current. Execute representative examples or cover them with tests, compare displayed schemas with implementation, check local links and headings, and search the affected area for stale claims. Correct stale material in scope; report materially broader corrections as scope expansion.

## Completion checklist

Substantial work is complete when success criteria pass; immutability and lineage remain intact; contracts match generated data; published fields have documented meaning and derivation; quality, freshness, diagnostics, and limitations are explicit; focused and affected broader tests pass or failures are accounted for; examples are verified; owning documentation is current; representative output is inspected; the diff is scoped; and completion evidence is reported.
