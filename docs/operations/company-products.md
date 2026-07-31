# Company products and lead exports

- **Status:** Implemented
- **Applies to:** v0.3b

```bash
./atlas download receita company-data --release 2026-07
./atlas refresh receita company-data --release 2026-07
./atlas releases inspect-bundle
```

The downloader requires `Socios` and `Simples`. Refresh performs no network access and publishes
the new bronze, silver, relationship, gold, and quality components only through the atomic bundle.
A failed candidate leaves the current bundle unchanged.

```bash
./atlas export-leads \
  --group software_services \
  --state PE \
  --municipality-code 2531 \
  --opened-from 2026-07-01 \
  --opened-before 2026-08-01 \
  --format csv \
  --limit 100000 \
  --output /tmp/atlas-recife-software-leads
```

The output is a Spark directory. A sibling `.manifest.json` records filters, source, row count, and
hash. Existing output is rejected unless `--force` is explicit; forced replacement moves the old
output aside.

Before accepting a national release, inspect source/bronze reconciliation, tax domains, partner
types and resolution, graph components/cycles/path growth, gold uniqueness, lead counts, runtime,
shuffle/spill, and storage. Never commit generated data, exports, or record-level diagnostics.
