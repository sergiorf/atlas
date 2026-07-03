# Repository Guidelines

## Project Structure & Module Organization

This repository is an early scaffold for a local Scala/Spark ETL pipeline over Brazil's Receita Federal CNPJ data. Keep production code under `src/main/scala/atlas/`, organized by responsibility: `config/`, `etl/`, `schema/`, `transform/`, and `io/`. Put entry-point wiring in `atlas/Main.scala`. Mirror packages in `src/test/scala/atlas/`.

Store runtime configuration in `conf/application.conf`, DuckDB examples in `examples/duckdb/`, raw inputs in `data/raw/`, and generated Parquet in `data/parquet/`. Do not commit Receita archives, CSVs, Parquet outputs, or Spark temporary files.

## Build, Test, and Development Commands

The sbt build is not yet checked in; when adding it, keep these standard workflows working:

- `sbt compile` — compile Scala sources and resolve Spark dependencies.
- `sbt test` — run all unit tests.
- `sbt "run --config conf/application.conf --sample"` — execute a small local sample (keep CLI options documented in `README.md`).
- `sbt clean` — remove generated build artifacts.
- `python scripts/download_receita.py --month 2026-01 --extract` — resumably download and extract one Receita snapshot.

Run Spark locally by default. Avoid requiring Docker, cloud services, or a cluster for routine development.

## Coding Style & Naming Conventions

Use two-space indentation and idiomatic Scala formatting. Name classes and objects in `PascalCase`, methods and values in `camelCase`, and packages in lowercase. Prefer small, typed transformations over untyped row indexing; isolate Receita column layouts in schema/parser modules. Keep file-specific ETL logic separate so future datasets such as Empresas and Socios can be added without changing Estabelecimentos code. Add and document a formatter (prefer `scalafmt`) with the initial build.

## Testing Guidelines

Place tests beside the mirrored package path and name suites `*Spec.scala` (for example, `transform/DateParsingSpec.scala`). The initial test framework should cover schema-to-column mapping, empty-string normalization, trimming, Receita date parsing, full-CNPJ construction, and representative malformed rows. Tests must use small in-memory fixtures; never depend on the full public dataset. Run `sbt test` before opening a pull request.

## Commit & Pull Request Guidelines

There is no commit history yet. Use concise imperative subjects such as `Add Estabelecimentos schema mapping`, and keep unrelated changes separate. Pull requests should explain the ETL behavior changed, list validation commands, call out schema or configuration changes, and link relevant issues. Include small input/output examples for transformation changes; screenshots are unnecessary unless documentation rendering is affected.

## Configuration & Data Safety

Keep paths and Spark settings configurable rather than hard-coded. Commit safe defaults and sample configuration only; never commit credentials, private company data, or large generated datasets.
