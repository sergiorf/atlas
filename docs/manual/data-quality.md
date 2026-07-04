# Data quality

Each v0.1 ingestion reports row count, invalid or missing CNPJ identifiers, missing opening dates, and missing primary CNAEs. It records input, output, dataset name, and UTC run timestamp in JSON and Markdown.

These metrics are diagnostics, not rejection thresholds: the current job writes output even when counts are nonzero. Review the reports after each run and compare them with the intended source snapshot. A successful command does not certify source completeness, CNPJ checksum validity, uniqueness, or business correctness.

See the [quality-rule specification](../specs/quality/receita-cnpj-quality-rules.md) for exact behavior.
