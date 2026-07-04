# Data quality

Bronze ingestion reports row count, invalid or missing CNPJ identifiers, missing opening dates, and missing primary CNAEs. These bronze metrics remain diagnostic and do not reject output.

Silver normalization additionally reports duplicate identifiers, malformed CNAEs, invalid states, and missing municipality codes. A null/non-fourteen-digit CNPJ or duplicate `cnpj_full` rejects silver publication; all other silver metrics are diagnostic. Rejected runs write reports and leave existing silver output untouched.

Review reports after each command and compare them with the intended source snapshot. Passing the silver gate does not certify source completeness, CNPJ checksum validity, or business correctness. See the [bronze rules](../specs/quality/receita-cnpj-quality-rules.md) and [silver rules](../specs/quality/silver-establishment-quality-rules.md) for exact behavior.
