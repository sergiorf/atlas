# Repository Guidelines

## Scope and canonical guidance

Atlas is a private commercial monorepo. Production ETL code belongs in `apps/etl/src/main/scala/atlas`, tests mirror it under `apps/etl/src/test/scala/atlas`, and product and data-contract decisions belong in `docs`.

Before substantial work, read:

- [`docs/atlas_unified_plan.md`](docs/atlas_unified_plan.md)
- [`docs/feature_development_workflow.md`](docs/feature_development_workflow.md)
- the relevant dataset specification under `docs/specs/datasets/`
- the relevant schema contract under `docs/specs/schemas/`
- the relevant manual and operations pages

The unified plan owns direction and sequencing. Specifications own implemented data behavior. The manual owns user-facing claims. Planned documents do not authorize implementation.

v0.1 supports only Receita `estabelecimentos` bronze ingestion. Do not add API, UI, indexer, AI, sanctions, PNCP, Docker, cloud, billing, dashboard, silver, or gold implementations until their roadmap phase is explicitly requested.

## Design discussion before implementation

Questions, proposals, and requests framed as “would it be better,” “should we,” “what do you think,” or similar are design discussions, not authorization to edit files.

For changes affecting architecture, data pipelines, contracts, operational workflows, CLI behavior, or multiple documentation owners:

1. Inspect the relevant repository context.
2. Respond as a senior data engineer with findings, alternatives, trade-offs, risks, and pushback where appropriate.
3. Identify unclear or unnecessary requirements and ask focused questions.
4. Propose a decision and an implementation boundary.
5. Do not modify files until the user explicitly approves implementation.

Explicit requests such as “implement,” “change,” “add,” “fix,” or “proceed” authorize implementation within the agreed scope. If intent is ambiguous, remain in discussion mode.

## Development workflow

Classify each change as trivial, bounded, or substantial using the feature workflow. Use the complete workflow for changes to public or published data behavior, architecture, dataset ownership, schema contracts, refresh semantics, query or index behavior, lineage, quality rules, privacy or licensing boundaries, or the supported product surface. Keep isolated fixes proportionate.

Inspect the current tree, relevant implementation, configuration, tests, and documentation before editing. Check `git status` and preserve user changes. Write an independently verifiable plan for work spanning several files and state compatibility, migration, and data-movement risks. Do not silently broaden scope; revise the plan when new evidence materially changes it.

Implement the smallest coherent vertical slice. Add or update small in-memory tests; tests must not depend on the full public dataset. Run focused checks first and then `sbt test` from `apps/etl` when Scala behavior changes. Review the final diff and tree, run `git diff --check`, and report verification evidence, unverified items, data movements, compatibility impact, and follow-up risks.

## Data and documentation rules

Keep raw, bronze, silver, gold, and exports separate. Raw source data is immutable: never edit, normalize, delete, or reinterpret raw files in place. Never commit or delete downloaded archives, extracted CSV, generated Parquet, Spark temporary files, credentials, or private data. Every transformation must be reproducible from declared inputs, versioned configuration, and documented rules.

Shared data contracts are the product core. Do not create ETL, notebook, UI, API, index, or analysis semantics that bypass a missing contract. Published tables, golden datasets, derived fields, query behavior, quality rules, and unsupported cases must be explicit. Schema or behavior changes require compatibility analysis, migration notes when applicable, documentation updates, and affected tests.

Documentation is part of done for every user-visible or data-visible capability. Update the owning manual, catalog, contract, specification, operational page, README, index, or plan in the same change. Correct stale documentation in the affected scope. Verify examples against generated sample data, tests, or executable queries and check local links.

## Scala and Spark conventions

Use Scala 2.12, two-space indentation, `PascalCase` types, `camelCase` methods and values, and lowercase packages. Prefer small typed transformations over row indexing. Isolate Receita layouts in schema modules. Do not use `collect()` on large DataFrames or convert them to local collections. Use disk-based stages and avoid unnecessary shuffles.

From `apps/etl`, keep these workflows working:

- `sbt compile`
- `sbt test`
- `sbt "runMain atlas.Main ingest-receita-estabelecimentos"`
- `sbt clean`
- `python scripts/download_receita.py --month 2026-06 --extract`

Use concise imperative commit subjects. Pull requests explain changed ETL behavior, validation, schema or configuration changes, and small representative input/output examples. Worktrees, subagents, and commits are optional execution tools, not repository requirements.
