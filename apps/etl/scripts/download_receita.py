#!/usr/bin/env python3
"""Restartable downloader for Receita Federal CNPJ Estabelecimentos files."""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import shutil
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable
from xml.etree import ElementTree

DEFAULT_BASE_URL = (
    "https://arquivos.receitafederal.gov.br/public.php/webdav"
)
DEFAULT_SHARE_TOKEN = "YggdBLfdninEJX9"
MONTH_PATTERN = re.compile(r"^\d{4}-\d{2}$")
FILE_PATTERN = re.compile(r"^Estabelecimentos(\d+)\.zip$", re.IGNORECASE)
CHUNK_SIZE = 4 * 1024 * 1024
REPORT_EVERY = 128 * 1024 * 1024
PROGRESS_WIDTH = 30
USER_AGENT = "atlas-etl/0.1 (+private-atlas-monorepo)"


def request(
    url: str,
    *,
    token: str,
    method: str = "GET",
    headers: dict[str, str] | None = None,
):
    credentials = base64.b64encode(f"{token}:".encode("ascii")).decode("ascii")
    request_headers = {
        "User-Agent": USER_AGENT,
        "Authorization": f"Basic {credentials}",
    }
    request_headers.update(headers or {})
    return urllib.request.Request(url, method=method, headers=request_headers)


def webdav_entries(url: str, token: str) -> list[dict[str, object]]:
    propfind = request(url, token=token, method="PROPFIND", headers={"Depth": "1"})
    with urllib.request.urlopen(propfind, timeout=60) as response:
        root = ElementTree.fromstring(response.read())

    entries: list[dict[str, object]] = []
    for item in root.findall("{DAV:}response"):
        href = item.findtext("{DAV:}href")
        if not href:
            continue
        path = urllib.parse.unquote(urllib.parse.urlparse(href).path).rstrip("/")
        name = path.rsplit("/", 1)[-1]
        is_collection = item.find(".//{DAV:}resourcetype/{DAV:}collection") is not None
        size_text = item.findtext(".//{DAV:}getcontentlength")
        entries.append(
            {
                "name": name,
                "collection": is_collection,
                "size": int(size_text) if size_text else None,
            }
        )
    return entries


def latest_month(entries: list[dict[str, object]]) -> str:
    months = {
        str(entry["name"])
        for entry in entries
        if entry["collection"] and MONTH_PATTERN.fullmatch(str(entry["name"]))
    }
    if not months:
        raise RuntimeError("No YYYY-MM snapshot directories were found")
    return max(months)


def estabelecimento_files(entries: list[dict[str, object]]) -> list[tuple[str, int | None]]:
    files = {
        str(entry["name"]): entry["size"]
        for entry in entries
        if not entry["collection"] and FILE_PATTERN.fullmatch(str(entry["name"]))
    }
    if not files:
        raise RuntimeError("No Estabelecimentos ZIP files were found")
    names = sorted(files, key=lambda name: int(FILE_PATTERN.fullmatch(name).group(1)))
    return [(name, int(files[name]) if files[name] is not None else None) for name in names]


def format_mib(size: int) -> str:
    return f"{size / (1024 * 1024):,.1f} MiB"


def show_progress(
    current: int,
    expected: int | None,
    started_at: float,
    session_start: int,
) -> None:
    elapsed = max(time.monotonic() - started_at, 0.001)
    bytes_per_second = max(current - session_start, 0) / elapsed
    speed = f"{bytes_per_second / (1024 * 1024):,.1f} MiB/s"
    if expected:
        ratio = min(current / expected, 1.0)
        completed = int(PROGRESS_WIDTH * ratio)
        bar = "#" * completed + "-" * (PROGRESS_WIDTH - completed)
        text = (
            f"\r  [{bar}] {ratio * 100:6.2f}%  "
            f"{format_mib(current)} / {format_mib(expected)}  {speed}"
        )
    else:
        text = f"\r  {format_mib(current)} received  {speed}"
    sys.stdout.write(text)
    sys.stdout.flush()


def download(
    url: str, destination: Path, expected_size: int | None, token: str
) -> int:
    partial = destination.with_name(destination.name + ".part")
    destination.parent.mkdir(parents=True, exist_ok=True)

    if destination.exists():
        size = destination.stat().st_size
        if expected_size is None or size == expected_size:
            print(f"Already complete: {destination.name} ({format_mib(size)})")
            return size
        if expected_size is not None and size < expected_size:
            destination.replace(partial)
        else:
            raise RuntimeError(f"Existing file is larger than the remote file: {destination}")

    offset = partial.stat().st_size if partial.exists() else 0
    if expected_size is not None and offset > expected_size:
        raise RuntimeError(f"Partial file is larger than the remote file: {partial}")
    if expected_size is not None and offset == expected_size:
        partial.replace(destination)
        print(f"Already complete: {destination.name} ({format_mib(offset)})")
        return offset

    headers = {"Range": f"bytes={offset}-"} if offset else {}
    if offset:
        print(f"Resuming {destination.name} at {format_mib(offset)}")
    else:
        print(f"Downloading {destination.name}")

    with urllib.request.urlopen(
        request(url, token=token, headers=headers), timeout=120
    ) as response:
        resumed = offset > 0 and response.status == 206
        if offset and not resumed:
            print("  Server did not accept the range request; restarting this file")
            offset = 0
        mode = "ab" if resumed else "wb"
        total = offset
        session_start = offset
        started_at = time.monotonic()
        next_report = total + REPORT_EVERY
        show_progress(total, expected_size, started_at, session_start)
        with partial.open(mode) as output:
            while True:
                chunk = response.read(CHUNK_SIZE)
                if not chunk:
                    break
                output.write(chunk)
                total += len(chunk)
                if sys.stdout.isatty() or total >= next_report:
                    show_progress(total, expected_size, started_at, session_start)
                    next_report = total + REPORT_EVERY
        show_progress(total, expected_size, started_at, session_start)
        print()

    actual_size = partial.stat().st_size
    if expected_size is not None and actual_size != expected_size:
        raise RuntimeError(
            f"Incomplete download for {destination.name}: expected {expected_size} bytes, "
            f"received {actual_size} bytes"
        )
    partial.replace(destination)
    return actual_size


def safe_member_path(root: Path, member_name: str) -> Path:
    root = root.resolve()
    target = (root / member_name).resolve()
    try:
        target.relative_to(root)
    except ValueError as error:
        raise RuntimeError(f"Unsafe ZIP entry: {member_name}") from error
    return target


def extract_restartable(archive: Path, destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as source:
        for member in source.infolist():
            if member.is_dir():
                continue
            target = safe_member_path(destination, member.filename)
            target.parent.mkdir(parents=True, exist_ok=True)
            if target.exists() and target.stat().st_size == member.file_size:
                continue
            partial = target.with_name(target.name + ".part")
            with source.open(member) as input_stream, partial.open("wb") as output:
                shutil.copyfileobj(input_stream, output, CHUNK_SIZE)
            if partial.stat().st_size != member.file_size:
                raise RuntimeError(f"Incomplete extraction: {member.filename}")
            partial.replace(target)


def save_manifest(path: Path, manifest: dict) -> None:
    manifest["updated_at_utc"] = datetime.now(timezone.utc).isoformat()
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    temporary.replace(path)


def parse_args(argv: Iterable[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Download restartable Receita CNPJ Estabelecimentos snapshots."
    )
    parser.add_argument(
        "--month",
        help="Snapshot in YYYY-MM format. Omit to discover the latest available month.",
    )
    parser.add_argument("--output-root", type=Path, default=Path("data/raw/receita"))
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument(
        "--share-token",
        default=os.environ.get("RECEITA_SHARE_TOKEN", DEFAULT_SHARE_TOKEN),
        help="Receita public-share token (normally no override is needed).",
    )
    parser.add_argument(
        "--extract", action="store_true", help="Extract each completed ZIP archive."
    )
    args = parser.parse_args(argv)
    if args.month and not MONTH_PATTERN.fullmatch(args.month):
        parser.error("--month must use YYYY-MM format")
    return args


def main(argv: Iterable[str] | None = None) -> int:
    args = parse_args(argv)
    base_url = args.base_url.rstrip("/")
    month = args.month
    if month is None:
        month = latest_month(webdav_entries(base_url + "/", args.share_token))
        print(f"Latest Receita snapshot: {month}")

    month_url = f"{base_url}/{month}/"
    files = estabelecimento_files(webdav_entries(month_url, args.share_token))
    dataset_root = args.output_root / month / "estabelecimentos"
    archive_root = dataset_root / "archives"
    extract_root = dataset_root / "extracted"
    archive_root.mkdir(parents=True, exist_ok=True)
    if args.extract:
        extract_root.mkdir(parents=True, exist_ok=True)

    manifest = {
        "source": month_url,
        "month": month,
        "dataset": "estabelecimentos",
        "updated_at_utc": None,
        "files": {},
    }
    manifest_path = dataset_root / "manifest.json"

    for filename, expected_size in files:
        url = urllib.parse.urljoin(month_url, filename)
        archive = archive_root / filename
        size = download(url, archive, expected_size, args.share_token)
        manifest["files"][filename] = {
            "url": url,
            "bytes": size,
            "status": "complete",
            "extracted": False,
        }
        if args.extract:
            print(f"Extracting {filename}")
            extract_restartable(archive, extract_root)
            manifest["files"][filename]["extracted"] = True
        save_manifest(manifest_path, manifest)

    print(f"Completed {len(files)} archive(s) for {month}.")
    print(f"Spark input: {extract_root}{os.sep}*")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("\nInterrupted; rerun the command to resume.", file=sys.stderr)
        raise SystemExit(130)
    except (OSError, RuntimeError, urllib.error.URLError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1)

