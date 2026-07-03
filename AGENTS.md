# Repository Guidelines

## Structure and scope

Atlas is a private commercial monorepo. Production ETL code belongs in `apps/etl/src/main/scala/atlas`, tests mirror it under `apps/etl/src/test/scala/atlas`, and product/architecture decisions belong in `docs`. Keep raw, bronze, silver, gold, and exports separate. Never commit or delete downloaded Receita archives, extracted CSV, Parquet, Spark temporary files, credentials, or private data.

v0.1 supports only Receita `estabelecimentos`. Do not add API, UI, indexer, AI, sanctions, PNCP, Docker, cloud, billing, or dashboard implementations until their roadmap phase is explicitly requested.

## Plan-implement workflow

Every new feature follows this loop:

1. Inspect the current tree, relevant code, configuration, tests, and documentation before editing. Check `git status` and identify any existing user changes.
2. Write a short implementation plan with independently verifiable steps. State data migration and compatibility risks explicitly. For work spanning several files, keep the plan updated while implementing.
3. Implement the smallest coherent vertical slice. Preserve raw data and existing behavior unless the plan explicitly changes them. Keep file-specific ETL logic isolated and typed.
4. Add or update in-memory tests for the behavior. Never make tests depend on the full public dataset.
5. Run the narrowest relevant checks first, then `sbt test` from `apps/etl`. Also inspect `git diff --check` and the final tree.
6. Update README, configuration examples, data-model/quality docs, and roadmap notes in the same change whenever behavior or interfaces change.
7. Finish by comparing the result with the plan and reporting completed validation, unverified items, data movements, and follow-up risks.

Do not silently broaden a feature beyond its plan. If new requirements materially change architecture or could risk existing data, stop and revise the plan before continuing.

## Scala and Spark conventions

Use Scala 2.12, two-space indentation, `PascalCase` types, `camelCase` methods/values, and lowercase packages. Prefer small typed transformations over row indexing. Isolate Receita layouts in schema modules. Do not use `collect()` on large DataFrames or convert them to local collections. Use disk-based stages and avoid unnecessary shuffles.

From `apps/etl`, keep these workflows working:

- `sbt compile`
- `sbt test`
- `sbt "runMain atlas.Main ingest-receita-estabelecimentos"`
- `sbt clean`
- `python scripts/download_receita.py --month 2026-06 --extract`

Use concise imperative commit subjects. Pull requests explain changed ETL behavior, validation, schema/config changes, and small representative input/output examples.
