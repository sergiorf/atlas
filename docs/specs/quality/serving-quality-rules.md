# Serving quality rules

- **Status:** Planned
- **Applies to:** serving query contract v1 and serving manifest v1

No candidate becomes current unless every blocking check passes after the database has been closed
and reopened.

## Source checks

Blocking checks require a contained immutable generation, supported bundle manifest, unique named
gold components, exact component hashes, full validation attestation bound to the manifest hash,
supported schema signatures, one source release, and supported calculation/taxonomy versions.
Missing, malformed, changed, mixed-release, traversal, symlink, raw, or silver inputs fail closed.

`PASS_WITH_WARNINGS` source validation is eligible only when every warning code is allowlisted by
this contract. Unknown warning codes are blocking. The initial implementation must expose the
reviewed warning-code list explicitly rather than treating all warnings as safe.

## Projection checks

Blocking checks include:

- required relations and columns;
- profile source-to-serving row-count equality;
- lead source-to-serving row-count equality;
- unique company root;
- unique lead key `(cnpj_full, business_group)`;
- complete lead-to-profile join coverage;
- source release and taxonomy agreement;
- preservation of null and tri-state tax values;
- serving manifest/database hash and size agreement;
- database readability after close and reopen.

## Query checks

The generated fixture must prove every filter alone and in supported combinations, judged search
order and match evidence, normalization, numeric and alphanumeric CNPJ handling, deterministic
ties, zero results, injection-shaped data, cursor integrity, and pagination without duplicates or
omissions. Rebuilding the same logical inputs may change timestamps and generation identity but
must preserve logical query results and manifest invariants.

## Publication checks

Failure before pointer replacement leaves the current pointer unchanged. Failure immediately after
replacement restores the predecessor atomically. Malformed pointers, unavailable predecessors,
concurrent publication, unsupported atomic moves, and rollback failures are blocking and retain
diagnostic evidence. A failed serving candidate never changes gold or any earlier data layer.

## Resource and benchmark gates

Provisional acceptance on the 32 GB development machine is:

- exact lookup warm p95 at most 100 ms;
- common filtered page warm p95 at most 500 ms;
- broad filtered page warm p95 at most 1 second;
- no accepted interactive workload execution over 2 seconds;
- full rebuild peak process memory at most 24 GB;
- eight simultaneous interactive readers plus one bounded export reader;
- export streaming without full-result application materialization;
- no mixed-generation response during cutover.

Relevance gates are defined by the judged fixture and must pass independently of latency. Reports
separate process-cold, connection-cold, and warm runs; they must not claim filesystem-cold state
unless the operating-system cache was controlled.
