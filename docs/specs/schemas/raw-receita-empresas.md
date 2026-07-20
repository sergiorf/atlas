# Raw Receita empresas layout

- **Status:** Planned
- **Owner:** future Receita company raw acquisition
- **Contract level:** Source layout design target

This is not implemented or currently runnable. Raw archives and extracted bytes remain immutable
and outside Git.

The planned parser assigns the seven official headerless `Empresas` positions, in order:

| Position | Field | Type before parsing | Meaning |
| ---: | --- | --- | --- |
| 1 | `cnpj_root` | string | Eight-position company root |
| 2 | `legal_name` | string | Registered company name |
| 3 | `legal_nature_code` | string | Receita legal-nature reference code |
| 4 | `responsible_qualification_code` | string | Responsible party qualification code |
| 5 | `share_capital_raw` | string | Locale-formatted share-capital value |
| 6 | `company_size_code` | string | Receita company-size code |
| 7 | `responsible_federative_entity` | string | Responsible federative entity name |

The delimiter is `;`, header is false, and parsing is string-first using encoding, quote, and
escape settings verified and declared by the acquisition manifest. The source contract is exposed
declaratively as `CompanyDataSchemas.empresasRaw`; it has no production reader or writer. A future
parser must preserve malformed rows for bronze diagnostics. Any publisher layout change requires
revisiting this planned contract before ingestion code.
