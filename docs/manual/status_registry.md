# Medallion status registry

The local status registry reports which dataset snapshot and layer last ran, whether it succeeded, when it finished, how many rows were known, and where its output belongs. It gives future operational tooling and a future dashboard a stable file contract without introducing a database or web application.

Atlas uses the direction `raw -> bronze -> silver -> gold -> serving/index`. Today, local Receita files are supported input, and Receita establishments have implemented bronze and silver outputs. Both jobs record status. Gold, serving/index, and a web dashboard remain roadmap work; their absence must not be interpreted as failed runs.

From `apps/etl`, list recorded runs with:

```bash
sbt "runMain atlas.Main status"
```

The command reads `data/_atlas/status` and prints source, dataset, snapshot, layer, status, output rows, quarantined rows, warning types, finish time, and output path. It starts no Spark session. Missing warning fields in older records display as zero/`-`. A custom registry root can be set with `ATLAS_STATUS_DIR` or a custom configuration file.

Registry files follow `data/_atlas/status/<source>/<dataset>/<snapshot>/<layer>.json`. For example: `data/_atlas/status/receita/estabelecimentos/2026-06/bronze.json`.

- `success`: the instrumented job completed its output and status publication.
- `success_with_warnings`: the layer was produced, but rows were quarantined or quality warnings were recorded; inspect the warning report paths.
- `failed`: the instrumented job failed; error details are recorded when available, and the command still exits as failed.
- missing: no status file has been recorded for that identity. This is not evidence of success or failure.
- not implemented: the layer or job is outside the current supported implementation. Atlas does not create placeholder records for future work.

A malformed file is reported separately while other valid records remain visible. Each file represents the latest recorded attempt for its source, dataset, snapshot, and layer, not an append-only history or a check that output files still exist.

Status JSON is generated operational metadata. It can contain local paths and failure messages, changes whenever jobs run, and is reproducible by running the job. It therefore remains outside Git along with raw input and generated bronze/silver data. See the [run-status contract](../specs/run-status.md) for field-level behavior.
