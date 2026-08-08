# Company query contract

- **Status:** Planned
- **Contract version:** `v1`
- **Milestone:** v0.4 serving foundation
- **Owner:** `apps/indexer`

This contract defines the internal company-discovery, profile, lead, and bounded-export behavior
that the v0.4 serving projection must implement before an API or application may expose it. Gold
remains authoritative. The serving layer may normalize search keys, join named gold products, and
duplicate values for measured access patterns; it must not invent company facts.

## Operations and grain

### Profile lookup

`getCompany(cnpjRoot)` accepts a masked or unmasked CNPJ root under the existing CNPJ
normalization contract. The canonical result is an eight-character uppercase alphanumeric string.
Malformed values are rejected. The operation returns zero or one `company_profiles_current` row
and never falls back to establishment, silver, or raw data.

### Company discovery

Company discovery returns one result per `cnpj_root`. It supports exact CNPJ and judged,
relevance-ranked matching over legal names and trade-name evidence. Trade names are
establishment-grained evidence obtained from contracted lead rows; they are not promoted to the
canonical company name.

The initial match classes, from strongest to weakest, are:

1. exact CNPJ root;
2. exact normalized legal name;
3. exact normalized trade name;
4. normalized legal-name prefix;
5. normalized trade-name prefix;
6. contracted typographical legal-name match;
7. contracted typographical trade-name match.

The judged fixture owns required inclusions, exclusions, and ordering. A storage engine may assign
internal scores, but `match_class`, `matched_field`, and matched source evidence must be returned.
Opaque or personalized ranking, semantic/vector search, arbitrary keywords, and partner-name
search are unsupported.

Direct company filters are exact legal-nature code, company-size code, and explicit Simples and
MEI tri-state values `TRUE`, `FALSE`, or `UNKNOWN`. Registration status, geography, opening date,
and CNAE are not company-profile facts. A later company-discovery filter may use explicitly
documented existential lead semantics; v1 does not silently collapse establishment values into a
company value.

### Lead search

Lead search preserves the grain of `leads_new_companies_current`: one active establishment and
business-group match. It supports:

- business group;
- state abbreviation;
- Receita municipality code or IBGE municipality code as distinct fields;
- a half-open opening-date range `[openedFrom, openedBefore)`;
- CNAE match source `PRIMARY` or `SECONDARY`;
- company-size and legal-nature codes;
- Simples and MEI tri-state values.

The last four company attributes are supplied by an exact `cnpj_root` join from
`company_profiles_current`. Missing profile coverage is a blocking projection failure. Gold leads
are already restricted to active establishments; v1 does not offer inactive lead discovery.

Repeated values within one dimension use OR. Different dimensions use AND. An omitted dimension
does not filter. An explicitly empty value list is invalid. Unknown tax state is selectable and
never behaves as false.

### Bounded export

Export uses exactly the lead-search predicate, null interpretation, and order. Page size does not
apply, but the accepted row ceiling is 1,000,000. The implementation must stream results without
materializing the complete result in application memory.

## Normalization

Search normalization is versioned independently of the query contract. Version 1 must specify and
test Unicode normalization, case folding, diacritic handling, punctuation, and whitespace before
implementation acceptance. It must preserve original display values. Empty normalized input is
invalid. Typographical matching is accepted only against the judged fixture and may not broaden
into undocumented substring or fuzzy matching.

## Ordering and pagination

Lead order is:

```text
opening_date DESC, cnpj_full ASC, business_group ASC
```

Company discovery orders by contracted match-class precedence, then normalized display name, then
`cnpj_root`. Exact internal scores are not a compatibility promise.

Pagination uses keyset cursors. A cursor contains cursor-format version, query-contract version,
operation, serving generation ID, canonical sort, final sort tuple, canonical filter fingerprint,
and issuance time. It is URL-safe, opaque, and authenticated with HMAC. It contains no local path.
Malformed, tampered, unsupported-version, query-mismatched, and unavailable-generation cursors
produce distinct validation errors.

Default page size is 50 and maximum page size is 200. A retained validated generation may serve a
cursor created against it. Once that generation is removed, the cursor is stale.

## Result envelope

Every result includes or is accompanied by:

- serving generation ID;
- source bundle ID;
- Receita release;
- taxonomy version where applicable;
- calculation, normalization, and relevance versions where applicable;
- serving build completion time;
- query-contract version;
- material limitation codes.

Freshness means source release and serving build time. It does not mean the last legal or
commercial change to a company.

## Error behavior

Stable error categories are `INVALID_ARGUMENT`, `INVALID_IDENTIFIER`, `INVALID_CURSOR`,
`TAMPERED_CURSOR`, `STALE_CURSOR`, `UNSUPPORTED_CONTRACT_VERSION`, and
`GENERATION_UNAVAILABLE`. Query values are always parameters; injection-shaped text is data.

## Representative examples

The committed judged cases under `apps/indexer/src/test/resources/fixtures` define exact expected
match evidence and order. The first commercial lead query combines business group
`software_services`, state `PE`, Recife's contracted municipality code, and a half-open monthly
opening window.
