#!/usr/bin/env python3
"""Acquire and verify the pre-bronze Receita company-data source bundle."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable

import company_data_manifest
import download_receita


DATASET_PATTERNS = {
    "empresas": re.compile(r"^Empresas\d+\.zip$", re.IGNORECASE),
    "cnae": re.compile(r"^Cnaes\.zip$", re.IGNORECASE),
    "municipios": re.compile(r"^Municipios\.zip$", re.IGNORECASE),
    "naturezas": re.compile(r"^Naturezas\.zip$", re.IGNORECASE),
    "paises": re.compile(r"^Paises\.zip$", re.IGNORECASE),
    "qualificacoes": re.compile(r"^Qualificacoes\.zip$", re.IGNORECASE),
    "motivos": re.compile(r"^Motivos\.zip$", re.IGNORECASE),
}
DEFAULT_TOM_URL = "https://www.gov.br/receitafederal/dados/municipios.csv/@@download/file"
DEFAULT_IBGE_URL = "https://servicodados.ibge.gov.br/api/v1/localidades/municipios"
PRODUCER_VERSION = "atlas-company-data-download/1"


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def matching_files(entries: list[dict[str, object]]) -> dict[str, list[tuple[str, int | None]]]:
    result: dict[str, list[tuple[str, int | None]]] = {}
    for logical_name, pattern in DATASET_PATTERNS.items():
        matches = [
            (str(entry["name"]), int(entry["size"]) if entry["size"] is not None else None)
            for entry in entries
            if not entry["collection"] and pattern.fullmatch(str(entry["name"]))
        ]
        if not matches:
            raise RuntimeError(f"No {logical_name} ZIP files were found")
        result[logical_name] = sorted(matches, key=lambda item: item[0].lower())
    return result


def download_public(url: str, destination: Path) -> int:
    """Download a public reference atomically; an existing capture is immutable."""
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        print(f"Already complete: {destination.name} ({download_receita.format_mib(destination.stat().st_size)})")
        return destination.stat().st_size
    partial = destination.with_name(destination.name + ".part")
    offset = partial.stat().st_size if partial.exists() else 0
    headers = {"User-Agent": download_receita.USER_AGENT}
    if offset:
        headers["Range"] = f"bytes={offset}-"
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=120) as response:
        resumed = offset > 0 and response.status == 206
        if offset and not resumed:
            offset = 0
        mode = "ab" if resumed else "wb"
        with partial.open(mode) as output:
            while chunk := response.read(download_receita.CHUNK_SIZE):
                output.write(chunk)
    partial.replace(destination)
    return destination.stat().st_size


def parser(expected_fields: int, encoding: str) -> dict[str, object]:
    return {
        "encoding": encoding,
        "delimiter": ";",
        "header": False,
        "quote": '"',
        "escape": '"',
        "expected_fields": expected_fields,
    }


def save_json(path: Path, value: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(path)


def save_status(
    status_dir: Path,
    *,
    release: str,
    started_at: datetime,
    status: str,
    input_paths: list[str],
    output_path: Path,
    file_count: int | None = None,
    byte_count: int | None = None,
    error: BaseException | None = None,
) -> None:
    finished_at = datetime.now(timezone.utc)
    value: dict[str, object] = {
        "source": "receita",
        "dataset": "company-data",
        "snapshot": release,
        "layer": "raw",
        "status": status,
        "started_at": started_at.isoformat().replace("+00:00", "Z"),
        "finished_at": finished_at.isoformat().replace("+00:00", "Z"),
        "duration_seconds": max((finished_at - started_at).total_seconds(), 0),
        "input_paths": input_paths,
        "output_path": str(output_path),
        "partition_columns": [],
        "application_name": "atlas-etl",
        "job_name": "download-receita-company-data",
    }
    if file_count is not None:
        value["file_count"] = file_count
    if byte_count is not None:
        value["byte_count"] = byte_count
    if error is not None:
        value["error_type"] = f"{type(error).__module__}.{type(error).__qualname__}"
        value["error_message"] = str(error)
    save_json(status_dir / "receita" / "company-data" / release / "raw.json", value)


def parse_args(argv: Iterable[str] | None = None) -> argparse.Namespace:
    parser_ = argparse.ArgumentParser(description="Acquire and verify Receita company-data raw inputs.")
    parser_.add_argument("--month", required=True, help="Snapshot in YYYY-MM format.")
    parser_.add_argument("--output-root", type=Path, default=Path("data/raw/receita"))
    parser_.add_argument("--status-dir", type=Path, default=Path("data/_atlas/status"))
    parser_.add_argument("--base-url", default=download_receita.DEFAULT_BASE_URL)
    parser_.add_argument("--share-token", default=os.environ.get("RECEITA_SHARE_TOKEN", download_receita.DEFAULT_SHARE_TOKEN))
    parser_.add_argument("--tom-url", default=DEFAULT_TOM_URL)
    parser_.add_argument("--ibge-url", default=DEFAULT_IBGE_URL)
    parser_.add_argument("--cnpj-encoding", default="latin-1")
    parser_.add_argument("--tom-encoding", default="latin-1")
    args = parser_.parse_args(argv)
    if not download_receita.MONTH_PATTERN.fullmatch(args.month):
        parser_.error("--month must use YYYY-MM format")
    return args


def main(argv: Iterable[str] | None = None) -> int:
    args = parse_args(argv)
    started_at = datetime.now(timezone.utc)
    release = args.month
    month_url = f"{args.base_url.rstrip('/')}/{release}/"
    raw_root = args.output_root / release / "company-data"
    input_paths = [month_url, args.tom_url, args.ibge_url]
    try:
        groups = matching_files(download_receita.webdav_entries(month_url, args.share_token))
        retrieved_at = utc_now()
        datasets = []
        for logical_name, files in groups.items():
            archives = []
            for filename, expected_size in files:
                source_url = urllib.parse.urljoin(month_url, filename)
                destination = raw_root / "archives" / logical_name / filename
                download_receita.download(source_url, destination, expected_size, args.share_token)
                archives.append(company_data_manifest.inspect_archive(
                    destination, raw_root=raw_root, source_url=source_url, retrieved_at=retrieved_at
                ))
            datasets.append({
                "logical_name": logical_name,
                "release": release,
                "parser": parser(company_data_manifest.REQUIRED_DATASETS[logical_name], args.cnpj_encoding),
                "archives": archives,
            })

        tom_path = raw_root / "references" / "tom" / "municipios.csv"
        ibge_path = raw_root / "references" / "ibge" / "municipios.json"
        download_public(args.tom_url, tom_path)
        download_public(args.ibge_url, ibge_path)
        tom = company_data_manifest.inspect_file(
            tom_path, raw_root=raw_root, source_url=args.tom_url,
            retrieved_at=retrieved_at, content_type="text/csv"
        )
        tom["parser"] = parser(5, args.tom_encoding)
        ibge = company_data_manifest.inspect_file(
            ibge_path, raw_root=raw_root, source_url=args.ibge_url,
            retrieved_at=retrieved_at, content_type="application/json"
        )
        if ibge_path.read_bytes()[:2] == b"\x1f\x8b":
            ibge["content_encoding"] = "gzip"
        manifest: dict[str, object] = {
            "manifest_version": company_data_manifest.MANIFEST_VERSION,
            "release": release,
            "created_at": utc_now(),
            "publisher": "Receita Federal and IBGE",
            "producer_version": PRODUCER_VERSION,
            "discovery_run_id": str(uuid.uuid4()),
            "datasets": datasets,
            "references": {"tom": tom, "ibge_localities": ibge},
        }
        diagnostics = company_data_manifest.validate_manifest(manifest, raw_root)
        if diagnostics:
            details = "\n".join(f"  {item.rule} {item.location}: {item.message}" for item in diagnostics)
            raise RuntimeError(f"Company-data manifest validation failed:\n{details}")
        manifest_path = raw_root / "source-manifest.json"
        save_json(manifest_path, manifest)
        all_files = [raw_root / archive["path"] for dataset in datasets for archive in dataset["archives"]]
        all_files.extend((tom_path, ibge_path))
        save_status(
            args.status_dir, release=release, started_at=started_at, status="success",
            input_paths=input_paths, output_path=manifest_path,
            file_count=len(all_files), byte_count=sum(path.stat().st_size for path in all_files),
        )
        print(f"Verified company-data source manifest: {manifest_path}")
        return 0
    except (OSError, RuntimeError, ValueError, urllib.error.URLError) as error:
        try:
            save_status(
                args.status_dir, release=release, started_at=started_at, status="failed",
                input_paths=input_paths, output_path=raw_root / "source-manifest.json", error=error,
            )
        except OSError as status_error:
            error.add_note(f"Could not write Atlas status: {status_error}")
        raise


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("\nInterrupted; rerun the command to resume.", file=sys.stderr)
        raise SystemExit(130)
    except (OSError, RuntimeError, ValueError, urllib.error.URLError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1)
