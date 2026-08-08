# Dataset and source catalog

This is Atlas's canonical inventory of public datasets and source families. The hierarchy is
**publisher/source → dataset family → dataset or subdataset**; for example, Receita Federal →
CNPJ → `Estabelecimentos`.

Catalog inclusion records research and product interest. It does not authorize implementation,
change supported behavior, or override the [Atlas unified plan](atlas_unified_plan.md), which alone
owns priority and sequencing. A planned or candidate input still needs a reviewed dataset
specification and refresh contract before implementation.

External source details were last verified against the linked official publisher pages on
**2026-07-20**. “No access fee” describes the publisher's download or API, not permission to
commercially redistribute source or derived data. Unless an official dataset-specific grant has
been reviewed for Atlas, redistribution is marked **review required**.

## Status classes

- **Supported:** implemented Atlas behavior covered by an active specification.
- **Planned:** approved roadmap work through v0.6, but unsupported until implemented.
- **Candidate:** demand-dependent later work with no implementation commitment.

## Inventory

The roadmap milestone is descriptive, not a delivery authorization. `TBD before implementation`
means Atlas has deliberately not selected or contracted that input yet.

| Publisher / family | Dataset or subdataset | Atlas purpose | Status / milestone | Access and cost | Expected publisher cadence | Redistribution | Official evidence | Atlas specification |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Receita Federal / CNPJ | `Estabelecimentos` | Establishment identity, status, CNAE, location, opening date, and contact foundation | **Supported** / v0.1 bronze; v0.2 silver and history | Public bulk ZIP download; no access fee | Monthly snapshots; Atlas refresh is operator-triggered | **Review required**; runtime data stays outside Git | [CNPJ open-data catalog and metadata](https://www.gov.br/receitafederal/pt-br/acesso-a-informacao/dados-abertos/cadastros), [official CNPJ layout](https://www.gov.br/receitafederal/dados/cnpj-metadados.pdf/@@download/file) | [Receita CNPJ](specs/datasets/receita-cnpj.md) |
| Receita Federal / CNPJ | `Empresas` | Root-level company identity and legal attributes | **Supported and accepted** / v0.3a | Public bulk ZIP download; no access fee | Monthly snapshots | **Review required** | [CNPJ open-data catalog and metadata](https://www.gov.br/receitafederal/pt-br/acesso-a-informacao/dados-abertos/cadastros), [official CNPJ layout](https://www.gov.br/receitafederal/dados/cnpj-metadados.pdf/@@download/file) | [Company and reference sources](specs/datasets/receita-company-reference-sources.md); [raw](specs/schemas/raw-receita-empresas.md), [bronze](specs/schemas/bronze-receita-empresas.md), and [silver](specs/schemas/silver-company.md) contracts |
| Receita Federal / CNPJ | `Socios` | Partner relationships for company profiles and partner networks | **Supported and accepted with limitations** / v0.3b; masked-person evidence remains internal | Public bulk ZIP download; no access fee | Monthly snapshots | **Review required** | [CNPJ open-data catalog and metadata](https://www.gov.br/receitafederal/pt-br/acesso-a-informacao/dados-abertos/cadastros), [official CNPJ layout](https://www.gov.br/receitafederal/dados/cnpj-metadados.pdf/@@download/file) | [Socios and Simples](specs/datasets/receita-socios-simples.md) |
| Receita Federal / CNPJ | `Simples` | Public tax-regime signals | **Supported and accepted with limitations** / v0.3b | Public bulk ZIP download; no access fee | Monthly snapshots | **Review required** | [CNPJ open-data catalog and metadata](https://www.gov.br/receitafederal/pt-br/acesso-a-informacao/dados-abertos/cadastros), [official CNPJ layout](https://www.gov.br/receitafederal/dados/cnpj-metadados.pdf/@@download/file) | [Socios and Simples](specs/datasets/receita-socios-simples.md) |
| Receita Federal / CNPJ references | `CNAE`, `Municipios`, `Naturezas Juridicas`, `Paises`, `Qualificacoes de Socios`, and `Motivos de Situacao Cadastral` | Decode official activity, municipality, legal-nature, country, responsible-party, and registration-reason codes | **Supported and accepted** / v0.3a | Public bulk download; no access fee | Published with CNPJ snapshots | **Review required** | [CNPJ catalog and official two-field layouts](https://www.gov.br/receitafederal/dados/cnpj-metadados.pdf/@@download/file) | [Source interpretation](specs/datasets/receita-company-reference-sources.md) and [reference dimensions](specs/schemas/receita-reference-dimensions.md) |
| Receita Federal / TOM | `Tabela de Municipios` CSV mapping TOM codes to seven-digit IBGE municipality codes | Bridge Receita municipality identifiers to official IBGE geography | **Supported and accepted** / v0.3a | Public CSV download; no access fee | Publisher-controlled; capture and version with each Atlas bundle; published fields expose no lifecycle or retirement status | **Review required** | [official TOM municipality table](https://www.gov.br/receitafederal/dados/municipios.csv/view), [metadata](https://www.gov.br/receitafederal/dados/municipios-metadados.pdf/view) | [Receita-to-IBGE geography](specs/schemas/receita-ibge-municipality-geography.md) |
| IBGE / Localities | Localities API municipality hierarchy (`/api/v1/localidades/municipios`) | Resolve municipality, immediate/intermediate region, state, and region identifiers and names | **Supported and accepted** / v0.3a | Official JSON API; no access fee | Publisher-controlled; snapshot response for reproducibility | **Review required** | [IBGE Localities API](https://servicodados.ibge.gov.br/api/docs/localidades) | [Receita-to-IBGE geography](specs/schemas/receita-ibge-municipality-geography.md) |
| IBGE / population and boundaries | Exact inputs deferred | Future density measures and spatial products | **Planned** / v0.4 or later | Exact input and access method TBD | TBD | **Review required** | [IBGE territorial meshes](https://www.ibge.gov.br/geociencias/organizacao-do-territorio/malhas-territoriais/15774-malhas.html) | TBD before implementation |
| CGU / sanctions | CEIS | Explainable flags for ineligible and suspended entities | **Planned** / v0.5 | Portal da Transparência bulk download; no access fee | Publisher-controlled; Atlas refresh contract TBD | **Review required** | [CEIS download and data dictionary](https://portaldatransparencia.gov.br/download-de-dados/ceis) | TBD before implementation |
| CGU / sanctions | CNEP | Explainable flags for entities punished under anti-corruption law | **Planned** / v0.5 | Portal da Transparência bulk download; no access fee | Publisher-controlled; Atlas refresh contract TBD | **Review required** | [CNEP download and data dictionary](https://portaldatransparencia.gov.br/download-de-dados/cnep) | TBD before implementation |
| CGU / sanctions | CEPIM | Explainable flags for barred nonprofit entities | **Planned** / v0.5 | Portal da Transparência bulk download; no access fee | Publisher-controlled; Atlas refresh contract TBD | **Review required** | [CEPIM download and data dictionary](https://portaldatransparencia.gov.br/download-de-dados/cepim) | TBD before implementation |
| MGI / public procurement | PNCP | Supplier, buyer, category, contract, date, and value intelligence | **Planned** / v0.6 | Public consultation API and downloadable open data; no login and no access fee | Continuously publisher-fed; Atlas snapshot and refresh contract TBD | **Review required** | [PNCP open data](https://www.gov.br/pncp/pt-br/acesso-a-informacao/dados-abertos), [official manuals](https://www.gov.br/pncp/pt-br/pncp/manuais) | TBD before implementation |
| Ministry of Labour and Employment / PDET | RAIS public, non-identified statistics | Annual formal-employment context only; no employee-level or identified features | **Candidate** / Later | Public non-identified TXT microdata and web tables; no access fee for public downloads | Annual for consolidated RAIS statistics | **Review required** | [official RAIS and CAGED microdata page](https://www.gov.br/trabalho-e-emprego/pt-br/assuntos/estatisticas-trabalho/microdados-rais-e-caged) | TBD before implementation |
| Ministry of Labour and Employment / PDET | CAGED public, non-identified statistics | Historical formal-employment movement context only; no employee-level or identified features | **Candidate** / Later | Public non-identified TXT microdata and web tables; no access fee for public downloads | Historical monthly series; exact Atlas coverage TBD | **Review required** | [official RAIS and CAGED microdata page](https://www.gov.br/trabalho-e-emprego/pt-br/assuntos/estatisticas-trabalho/microdados-rais-e-caged) | TBD before implementation |
| Ministry of Labour and Employment / PDET | Novo CAGED public, non-identified statistics | Current formal-employment flow context only; no employee-level or identified features | **Candidate** / Later | Public non-identified TXT microdata and publications; no access fee | Monthly statistics | **Review required** | [official PDET overview](https://www.gov.br/trabalho-e-emprego/pt-br/assuntos/estatisticas-trabalho), [Novo CAGED definition](https://www.gov.br/trabalho-e-emprego/pt-br/assuntos/estatisticas-trabalho/o-pdet/o-que-e-o-novo-caged) | TBD before implementation |
| MDIC / foreign trade | ComexStat | Sector, municipality, product, and country trade intelligence | **Candidate** / Later | Public CSV downloads and query service; no registration or access fee | Monthly | **Review required** | [official ComexStat open-data files](https://www.gov.br/mdic/pt-br/assuntos/comercio-exterior/estatisticas/base-de-dados-bruta), [official service description](https://www.gov.br/pt-br/servicos/consultar-estatisticas-oficiais-do-comercio-exterior-de-bens-brasileiro) | TBD before implementation |
| CVM / capital markets | Exact public-company dataset **TBD before implementation** | Enrich listed and public companies, not provide financials for all private companies | **Candidate** / Later | Official open-data catalog; exact input and access method TBD before implementation | Dataset-dependent; TBD before implementation | **Review required** | [CVM open-data portal](https://www.gov.br/cvm/pt-br/acesso-a-informacao-cvm/dados-abertos/portal-dados-abertos) | TBD before implementation |
| Banco Central do Brasil / macroeconomic data | Exact SGS or other public series **TBD before implementation** | Macroeconomic and regional credit-market context, not company-level bureau data | **Candidate** / Later | Official open-data downloads/APIs; exact series and access method TBD before implementation | Series-dependent; TBD before implementation | **Review required** | [BCB open-data portal](https://dadosabertos.bcb.gov.br/) | TBD before implementation |
| CNJ / judicial data | Exact DataJud/CNJ input **TBD before implementation** | Potential aggregate judicial context, subject to separate legal, ethical, product-risk, and technical review | **Candidate** / Later | DataJud public API uses a publisher-provided public key; no access fee stated | Publisher-controlled; TBD before implementation | **Review required**; DataJud terms and privacy limits require review | [DataJud public API](https://datajud-wiki.cnj.jus.br/api-publica/), [official access documentation](https://datajud-wiki.cnj.jus.br/api-publica/acesso/) | TBD before implementation |

## Implementation and data boundaries

Receita CNPJ `Estabelecimentos`, the v0.3a company-data atomic silver workflow, and v0.3b
`Socios`/`Simples` company products are implemented.
The company-data May–July foundation was operator-accepted on 2026-07-30. The v0.3b company
products and corporate relationships were operator-accepted with limitations on 2026-08-08;
serving and public query support remain deferred to their roadmap phases. The establishment input,
acquisition, refresh, lineage, and output behavior remains owned by the [Receita CNPJ dataset
specification](specs/datasets/receita-cnpj.md) and its linked schema and quality contracts.

All remaining planned and candidate rows are unsupported. Before one can be implemented, Atlas
must select the exact input where still broad, review access and redistribution terms, define a
versioned dataset specification and refresh contract, analyze compatibility and data movement,
and obtain roadmap authorization for that phase.

Identified RAIS or CAGED data is explicitly out of scope. Atlas may evaluate only public,
non-identified labor statistics; access procedures for identified records do not make their use
an Atlas candidate.
