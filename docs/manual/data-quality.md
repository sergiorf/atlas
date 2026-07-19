# Data quality

Bronze ingestion reports row count, invalid or missing CNPJ identifiers, missing opening dates, and missing primary CNAEs. These bronze metrics remain diagnostic and do not reject output.

Silver normalization first checks uppercase alphanumeric root and branch widths, numeric check positions, the fourteen-character full key, and registration status membership in `01`, `02`, `03`, `04`, `08`. Malformed rows—including shifted rows with date-like status values such as `20250324`—are written to `data/_atlas/quality/receita/establishments/<snapshot>/malformed_rows` and excluded from clean silver. The run may then publish as `success_with_warnings`.

The additive `alphanumeric_cnpj_count` metric counts accepted rows containing letters, allowing operators to detect post-cutover identifiers. Duplicate `cnpj_full` checks run only on structurally valid candidates. Any remaining duplicated valid key writes the involved rows to `duplicate_cnpj_full`, rejects publication, and leaves the prior silver output untouched. Silver also reports malformed CNAEs, invalid states, and missing municipality codes under their existing contract behavior.

Review reports after each command and compare them with the intended source snapshot. Passing the silver gate does not certify source completeness, CNPJ checksum validity, or business correctness. See the [bronze rules](../specs/quality/receita-cnpj-quality-rules.md) and [silver rules](../specs/quality/silver-establishment-quality-rules.md) for exact behavior.
