# Receita refresh runbook

Refreshes are manual and snapshot-based.

1. Confirm the intended `YYYY-MM` snapshot and available disk capacity.
2. From `apps/etl`, download or resume without deleting `.part` files:

   ```bash
   python scripts/download_receita.py --month 2026-06 --extract
   ```

3. Confirm archives and extracted files are under `data/raw/receita/<YYYY-MM>/estabelecimentos`; never edit them in place.
4. Point `ATLAS_RECEITA_RAW_DIR` and, if needed, `ATLAS_RECEITA_BRONZE_DIR` to the intended snapshot/output.
5. Run the ingestion command and retain its console result.
6. Review JSON and Markdown quality reports for paths, row count, identifier issues, missing dates, missing CNAEs, and run timestamp.
7. Inspect representative Parquet records and compare metrics with the previous accepted snapshot before treating the refresh as usable.

The default write mode is `overwrite`; verify the configured destination before running. A snapshot folder name is not proof that every official source archive was published or downloaded completely.
