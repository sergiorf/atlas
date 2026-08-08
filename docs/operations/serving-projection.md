# Serving projection operations

- **Status:** Planned; no serving command is implemented yet

v0.4 will build disposable serving generations from one validated atomic gold bundle. DuckDB is
the first benchmark candidate. This page records the intended operator boundary without claiming
an available product or command.

Planned indexer-local operations are build, validate, inspect, benchmark, rollback, and eventually
locked cleanup. Root `./atlas serving ...` routing is deferred until those operations stabilize.

Generated state belongs under `<data-root>/_atlas/serving` and remains uncommitted. National data,
databases, row-level reports, machine-private paths, and detailed benchmark artifacts must not be
committed. Only aggregate acceptance evidence belongs in documentation.

Normal acceptance will validate the selected gold bundle, build under a unique staging generation,
close and reopen the database, validate representative queries and pagination, promote the
generation and pointer atomically, and perform a read-after-switch check. Manual database mutation
is prohibited. Rollback changes only the serving pointer to a retained validated generation.

The provisional development-machine limits are 24 GB peak process memory, eight simultaneous
interactive readers plus one bounded export, and the latency limits in the serving quality rules.
The national benchmark remains an explicit operator acceptance step.
