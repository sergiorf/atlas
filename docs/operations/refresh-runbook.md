# Receita refresh runbook

Refreshes are manual and snapshot-based.

1. Confirm the intended `YYYY-MM` snapshot and available disk capacity.
2. From `apps/etl`, download or resume without deleting `.part` files:

   ```bash
   python scripts/download_receita.py --month 2026-06 --extract
   ```

3. Confirm archives and extracted files are under `data/raw/receita/<YYYY-MM>/estabelecimentos`; never edit them in place.
4. Pass `--release YYYY-MM` with the root CLI. Atlas replaces the `YYYY-MM` segment in the configured raw directory with that release, so the default configuration selects `data/raw/receita/<release>/estabelecimentos/extracted`. If `ATLAS_RECEITA_RAW_DIR` uses a custom layout without a date segment, point it explicitly to the intended snapshot. Override `ATLAS_RECEITA_BRONZE_DIR` only when a non-default output root is required.
5. Run the refresh command when maintaining latest current plus compact history:

   ```bash
   ./atlas refresh receita estabelecimentos --release 2026-06
   ```

   Or run the individual sbt ingestion/normalization commands for isolated troubleshooting.
6. Review the bronze JSON and Markdown reports for the resolved raw input path, output path, row count, identifier issues, missing dates, missing CNAEs, and run timestamp. Stop if the input path does not contain the intended release when using the default dated layout.
7. Point `ATLAS_RECEITA_SILVER_DIR` to the intended output when overriding defaults, then run `sbt "runMain atlas.Main normalize-receita-estabelecimentos"`.
8. Review the silver reports. Invalid or duplicate CNPJs reject publication; investigate the bronze snapshot instead of editing raw files or silently deduplicating.
9. Inspect representative bronze, latest silver current, and change-event Parquet records before treating the refresh as usable.

The default write mode is `overwrite`; verify configured destinations before running. Atlas keeps the latest full normalized table and selected field-level deltas instead of full historical silver copies. A snapshot folder name is not proof that every official source archive was published or downloaded completely.

When upgrading a legacy `establishments_current` table that lacks `release` and `record_hash`, refresh recomputes the comparison hash from the contracted tracked fields. Change events leave `from_release` null because the earlier month cannot be recovered reliably from that table; the newly published current table includes both metadata fields.
