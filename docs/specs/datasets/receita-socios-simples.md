# Receita `Socios` and `Simples`

- **Status:** Implemented and operator-accepted with limitations on 2026-08-08
- **Roadmap milestone:** v0.3b
- **Source cadence:** Monthly Receita CNPJ snapshot
- **Refresh:** Operator-selected, local-only transformation

Atlas acquires `Socios` and `Simples` with the existing same-release Receita company package.
Manifest version 2 requires both groups and records archive and member sizes, hashes, parser
settings, retrieval evidence, and the exact release. Manifest version 1 remains readable for
historical v0.3a bundles but does not authorize v0.3b products.

The official `Simples` layout has seven fields: CNPJ root, Simples indicator, Simples option and
exclusion dates, MEI indicator, and MEI option and exclusion dates. `S`, `N`, and blank/other map
to true, false, and null.

The official `Socios` layout has eleven fields covering source company, participant type, name,
CNPJ/CPF, qualification, entry date, country, representative evidence, and age range.
Participant types are `1` legal entity, `2` natural person, and `3` foreign participant.

Atlas preserves the source `Socios` entry-date value. Silver parses an eight-digit real calendar
date only when it is on or after `1582-10-15` and no later than the final day of the declared
Receita release month. Blank values remain null without a warning. Values with an invalid format,
an impossible calendar date, an earlier date, or a date after the release remain preserved as raw
evidence, produce a null normalized date, and are reported as non-blocking field-quality issues.
The partner record itself remains eligible for silver because an unusable date does not disprove
the source-reported relationship.

Receita masks CPF values in public QSA files. Atlas retains source-shaped names and masked
identifiers only in internal bronze and silver. It does not use them as stable person identifiers,
merge people across companies, or include them in gold relationships or lead exports. A company
edge is created only for type `1` with a structurally valid CNPJ resolving exactly to a
same-release silver company.

Monthly comparison records Atlas observation status, not legal-effective transactions.

Official evidence:

- [Receita CNPJ open-data catalog](https://www.gov.br/receitafederal/pt-br/acesso-a-informacao/dados-abertos/cadastros)
- [Receita CNPJ metadata](https://www.gov.br/receitafederal/dados/cnpj-metadados.pdf/@@download/file)

Redistribution of source-derived fields remains review-required. Controlled v0.3b exports exclude
natural-person partner fields.
