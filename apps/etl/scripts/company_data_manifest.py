"""Validate a planned Receita company-data source manifest against local raw inputs.

This module deliberately has no downloader, Spark integration, bronze writer, or public CLI.
It verifies immutable local inputs before those capabilities are implemented.
"""

from __future__ import annotations

import csv
import gzip
import hashlib
import io
import json
import re
import zipfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, BinaryIO


MANIFEST_VERSION = 1
RELEASE_PATTERN = re.compile(r"^\d{4}-\d{2}$")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
REQUIRED_DATASETS = {
    "empresas": 7,
    "cnae": 2,
    "municipios": 2,
    "naturezas": 2,
    "paises": 2,
    "qualificacoes": 2,
    "motivos": 2,
}
REFERENCE_INPUTS = {"tom": 5, "ibge_localities": None}
CHUNK_SIZE = 1024 * 1024


@dataclass(frozen=True)
class Diagnostic:
    rule: str
    location: str
    message: str


def sha256_stream(stream: BinaryIO) -> str:
    digest = hashlib.sha256()
    while chunk := stream.read(CHUNK_SIZE):
        digest.update(chunk)
    return digest.hexdigest()


def sha256_file(path: Path) -> str:
    with path.open("rb") as stream:
        return sha256_stream(stream)


def inspect_archive(path: Path, *, raw_root: Path, source_url: str, retrieved_at: str) -> dict[str, Any]:
    """Return immutable archive evidence without extracting or modifying the ZIP."""
    relative = path.resolve().relative_to(raw_root.resolve()).as_posix()
    members = []
    with zipfile.ZipFile(path) as source:
        for info in source.infolist():
            if info.is_dir():
                continue
            if not _safe_zip_member(info.filename):
                raise ValueError(f"unsafe ZIP member path: {info.filename}")
            with source.open(info) as stream:
                member_hash = sha256_stream(stream)
            members.append(
                {
                    "path": info.filename,
                    "bytes": info.file_size,
                    "crc32": f"{info.CRC:08x}",
                    "sha256": member_hash,
                }
            )
    if not members:
        raise ValueError(f"archive has no data members: {path}")
    return {
        "source_url": source_url,
        "filename": path.name,
        "path": relative,
        "bytes": path.stat().st_size,
        "sha256": sha256_file(path),
        "retrieved_at": retrieved_at,
        "members": members,
    }


def inspect_file(path: Path, *, raw_root: Path, source_url: str, retrieved_at: str, content_type: str) -> dict[str, Any]:
    """Return provenance evidence for an immutable TOM or IBGE capture."""
    relative = path.resolve().relative_to(raw_root.resolve()).as_posix()
    return {
        "source_url": source_url,
        "path": relative,
        "retrieved_at": retrieved_at,
        "content_type": content_type,
        "bytes": path.stat().st_size,
        "sha256": sha256_file(path),
    }


def _safe_local_path(raw_root: Path, value: Any, location: str) -> tuple[Path | None, list[Diagnostic]]:
    if not isinstance(value, str) or not value:
        return None, [Diagnostic("CDM_PATH", location, "path must be a non-empty string")]
    relative = Path(value)
    if relative.is_absolute():
        return None, [Diagnostic("CDM_PATH", location, "path must be relative to raw_root")]
    root = raw_root.resolve()
    target = (root / relative).resolve()
    try:
        target.relative_to(root)
    except ValueError:
        return None, [Diagnostic("CDM_PATH", location, "path escapes raw_root")]
    return target, []


def _safe_zip_member(name: str) -> bool:
    path = PurePosixPath(name.replace("\\", "/"))
    return not path.is_absolute() and ".." not in path.parts and bool(path.parts)


def _require_string(mapping: dict[str, Any], key: str, location: str, diagnostics: list[Diagnostic]) -> None:
    if not isinstance(mapping.get(key), str) or not mapping[key]:
        diagnostics.append(Diagnostic("CDM_REQUIRED", f"{location}.{key}", "non-empty string is required"))


def _verify_size_hash(path: Path, item: dict[str, Any], location: str, diagnostics: list[Diagnostic]) -> None:
    if not path.is_file():
        diagnostics.append(Diagnostic("CDM_FILE_MISSING", location, f"file does not exist: {path}"))
        return
    expected_bytes = item.get("bytes")
    if not isinstance(expected_bytes, int) or expected_bytes < 0:
        diagnostics.append(Diagnostic("CDM_BYTES", f"{location}.bytes", "non-negative integer is required"))
    elif path.stat().st_size != expected_bytes:
        diagnostics.append(Diagnostic("CDM_BYTES", location, f"expected {expected_bytes} bytes, found {path.stat().st_size}"))
    expected_hash = item.get("sha256")
    if not isinstance(expected_hash, str) or not SHA256_PATTERN.fullmatch(expected_hash):
        diagnostics.append(Diagnostic("CDM_SHA256", f"{location}.sha256", "lowercase SHA-256 is required"))
    elif sha256_file(path) != expected_hash:
        diagnostics.append(Diagnostic("CDM_SHA256", location, "SHA-256 does not match"))


def _parser(item: dict[str, Any], expected_fields: int, location: str, diagnostics: list[Diagnostic]) -> dict[str, Any] | None:
    parser = item.get("parser")
    if not isinstance(parser, dict):
        diagnostics.append(Diagnostic("CDM_PARSER", f"{location}.parser", "parser object is required"))
        return None
    for key in ("encoding", "delimiter", "quote", "escape"):
        _require_string(parser, key, f"{location}.parser", diagnostics)
    if parser.get("header") is not False:
        diagnostics.append(Diagnostic("CDM_PARSER", f"{location}.parser.header", "must be false"))
    if parser.get("expected_fields") != expected_fields:
        diagnostics.append(Diagnostic("CDM_FIELD_COUNT", f"{location}.parser.expected_fields", f"must be {expected_fields}"))
    for key in ("delimiter", "quote", "escape"):
        if isinstance(parser.get(key), str) and len(parser[key]) != 1:
            diagnostics.append(Diagnostic("CDM_PARSER", f"{location}.parser.{key}", "must be one character"))
    return parser


def _verify_csv(stream: BinaryIO, parser: dict[str, Any], expected_fields: int, location: str, diagnostics: list[Diagnostic]) -> None:
    text = None
    try:
        text = io.TextIOWrapper(stream, encoding=parser["encoding"], errors="strict", newline="")
        reader = csv.reader(
            text,
            delimiter=parser["delimiter"],
            quotechar=parser["quote"],
            # Spark/Univocity represents doubled-quote escaping as escape == quote.
            # Python's csv module represents the same convention with no escapechar.
            escapechar=None if parser["escape"] == parser["quote"] else parser["escape"],
            doublequote=parser["escape"] == parser["quote"],
            strict=True,
        )
        count = 0
        for number, row in enumerate(reader, start=1):
            count += 1
            if len(row) != expected_fields:
                diagnostics.append(Diagnostic("CDM_FIELD_COUNT", location, f"record {number} has {len(row)} fields; expected {expected_fields}"))
                break
        if count == 0:
            diagnostics.append(Diagnostic("CDM_EMPTY", location, "data file contains no records"))
    except (UnicodeError, csv.Error, LookupError, KeyError) as error:
        diagnostics.append(Diagnostic("CDM_PARSE", location, str(error)))
    finally:
        if text is not None:
            text.detach()


def _verify_archive(raw_root: Path, archive: dict[str, Any], parser: dict[str, Any] | None, expected_fields: int, location: str, diagnostics: list[Diagnostic], seen_members: set[str]) -> None:
    path, path_diagnostics = _safe_local_path(raw_root, archive.get("path"), f"{location}.path")
    diagnostics.extend(path_diagnostics)
    for key in ("source_url", "filename", "retrieved_at"):
        _require_string(archive, key, location, diagnostics)
    if path is None:
        return
    _verify_size_hash(path, archive, location, diagnostics)
    if not path.is_file():
        return
    declared = archive.get("members")
    if not isinstance(declared, list) or not declared:
        diagnostics.append(Diagnostic("CDM_MEMBERS", f"{location}.members", "non-empty member list is required"))
        return
    try:
        with zipfile.ZipFile(path) as source:
            actual_items = [item for item in source.infolist() if not item.is_dir()]
            actual = {item.filename: item for item in actual_items}
            if len(actual) != len(actual_items):
                diagnostics.append(Diagnostic("CDM_MEMBER_DUPLICATE", location, "ZIP contains duplicate member paths"))
            declared_names = {item.get("path") for item in declared if isinstance(item, dict)}
            if set(actual) != declared_names:
                diagnostics.append(Diagnostic("CDM_MEMBERS", location, "declared and actual ZIP members differ"))
            for index, member in enumerate(declared):
                member_location = f"{location}.members[{index}]"
                if not isinstance(member, dict):
                    diagnostics.append(Diagnostic("CDM_MEMBERS", member_location, "member must be an object"))
                    continue
                name = member.get("path")
                if not isinstance(name, str) or not _safe_zip_member(name):
                    diagnostics.append(Diagnostic("CDM_ZIP_PATH", member_location, "unsafe or missing ZIP member path"))
                    continue
                if name in seen_members:
                    diagnostics.append(Diagnostic("CDM_MEMBER_DUPLICATE", member_location, f"duplicate member path: {name}"))
                seen_members.add(name)
                info = actual.get(name)
                if info is None:
                    continue
                if member.get("bytes") != info.file_size:
                    diagnostics.append(Diagnostic("CDM_BYTES", member_location, f"expected member size {info.file_size}"))
                if member.get("crc32") != f"{info.CRC:08x}":
                    diagnostics.append(Diagnostic("CDM_CRC32", member_location, f"expected CRC32 {info.CRC:08x}"))
                with source.open(info) as member_stream:
                    actual_hash = sha256_stream(member_stream)
                if member.get("sha256") != actual_hash:
                    diagnostics.append(Diagnostic("CDM_SHA256", member_location, "member SHA-256 does not match"))
                if parser is not None:
                    with source.open(info) as member_stream:
                        _verify_csv(member_stream, parser, expected_fields, member_location, diagnostics)
    except (OSError, zipfile.BadZipFile) as error:
        diagnostics.append(Diagnostic("CDM_ZIP", location, str(error)))


def _verify_reference(raw_root: Path, name: str, item: Any, diagnostics: list[Diagnostic]) -> None:
    location = f"references.{name}"
    if not isinstance(item, dict):
        diagnostics.append(Diagnostic("CDM_REFERENCE", location, "reference object is required"))
        return
    for key in ("source_url", "retrieved_at", "content_type"):
        _require_string(item, key, location, diagnostics)
    path, path_diagnostics = _safe_local_path(raw_root, item.get("path"), f"{location}.path")
    diagnostics.extend(path_diagnostics)
    if path is None:
        return
    _verify_size_hash(path, item, location, diagnostics)
    if not path.is_file():
        return
    expected_fields = REFERENCE_INPUTS[name]
    if expected_fields is not None:
        parser = _parser(item, expected_fields, location, diagnostics)
        if parser is not None:
            with path.open("rb") as stream:
                _verify_csv(stream, parser, expected_fields, location, diagnostics)
    else:
        try:
            content = path.read_bytes()
            is_gzip = content.startswith(b"\x1f\x8b")
            declared_encoding = item.get("content_encoding")
            if declared_encoding not in (None, "gzip"):
                raise ValueError("content_encoding must be gzip when present")
            if is_gzip != (declared_encoding == "gzip"):
                raise ValueError("content_encoding does not match the captured bytes")
            if is_gzip:
                content = gzip.decompress(content)
            value = json.loads(content.decode("utf-8"))
            if not isinstance(value, list) or not value:
                raise ValueError("top-level value must be a non-empty array")
            for index, municipality in enumerate(value):
                immediate = municipality.get("regiao-imediata") if isinstance(municipality, dict) else None
                intermediate = immediate.get("regiao-intermediaria") if isinstance(immediate, dict) else None
                state = intermediate.get("UF") if isinstance(intermediate, dict) else None
                region = state.get("regiao") if isinstance(state, dict) else None
                if not all(isinstance(part, dict) and part.get("id") is not None for part in (municipality, immediate, intermediate, state, region)):
                    diagnostics.append(Diagnostic("CDM_IBGE_HIERARCHY", f"{location}[{index}]", "municipality or parent hierarchy identifier is missing"))
                    break
        except (OSError, UnicodeError, gzip.BadGzipFile, json.JSONDecodeError, ValueError) as error:
            diagnostics.append(Diagnostic("CDM_IBGE_JSON", location, str(error)))


def validate_manifest(manifest: dict[str, Any], raw_root: Path) -> list[Diagnostic]:
    """Return deterministic diagnostics; an empty result means the manifest is valid."""
    diagnostics: list[Diagnostic] = []
    if manifest.get("manifest_version") != MANIFEST_VERSION:
        diagnostics.append(Diagnostic("CDM_VERSION", "manifest_version", f"must be {MANIFEST_VERSION}"))
    release = manifest.get("release")
    if not isinstance(release, str) or not RELEASE_PATTERN.fullmatch(release):
        diagnostics.append(Diagnostic("CDM_RELEASE", "release", "must use YYYY-MM"))
    for key in ("created_at", "publisher", "producer_version", "discovery_run_id"):
        _require_string(manifest, key, "manifest", diagnostics)

    datasets = manifest.get("datasets")
    if not isinstance(datasets, list):
        diagnostics.append(Diagnostic("CDM_DATASETS", "datasets", "dataset list is required"))
        datasets = []
    by_name: dict[str, dict[str, Any]] = {}
    for index, item in enumerate(datasets):
        if not isinstance(item, dict) or item.get("logical_name") not in REQUIRED_DATASETS:
            diagnostics.append(Diagnostic("CDM_DATASET_NAME", f"datasets[{index}]", "unknown or missing logical_name"))
            continue
        name = item["logical_name"]
        if name in by_name:
            diagnostics.append(Diagnostic("CDM_DATASET_DUPLICATE", f"datasets[{index}]", f"duplicate dataset: {name}"))
        by_name[name] = item
    for name in REQUIRED_DATASETS:
        if name not in by_name:
            diagnostics.append(Diagnostic("CDM_DATASET_MISSING", "datasets", f"missing required dataset: {name}"))

    seen_members: set[str] = set()
    for name, expected_fields in REQUIRED_DATASETS.items():
        item = by_name.get(name)
        if item is None:
            continue
        location = f"datasets.{name}"
        if item.get("release") != release:
            diagnostics.append(Diagnostic("CDM_MIXED_RELEASE", f"{location}.release", "must equal manifest release"))
        parser = _parser(item, expected_fields, location, diagnostics)
        archives = item.get("archives")
        if not isinstance(archives, list) or not archives:
            diagnostics.append(Diagnostic("CDM_ARCHIVES", f"{location}.archives", "non-empty archive list is required"))
            continue
        for index, archive in enumerate(archives):
            archive_location = f"{location}.archives[{index}]"
            if not isinstance(archive, dict):
                diagnostics.append(Diagnostic("CDM_ARCHIVES", archive_location, "archive must be an object"))
                continue
            _verify_archive(raw_root, archive, parser, expected_fields, archive_location, diagnostics, seen_members)

    references = manifest.get("references")
    if not isinstance(references, dict):
        diagnostics.append(Diagnostic("CDM_REFERENCES", "references", "reference object is required"))
        references = {}
    for name in REFERENCE_INPUTS:
        _verify_reference(raw_root, name, references.get(name), diagnostics)
    return sorted(diagnostics, key=lambda item: (item.location, item.rule, item.message))
