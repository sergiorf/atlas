import hashlib
import importlib.util
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


SPEC = importlib.util.spec_from_file_location(
    "company_data_manifest", Path(__file__).with_name("company_data_manifest.py")
)
company_data_manifest = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = company_data_manifest
SPEC.loader.exec_module(company_data_manifest)


class CompanyDataManifestTest(unittest.TestCase):
    def fixture(self, root: Path) -> dict:
        datasets = []
        for name, fields in company_data_manifest.REQUIRED_DATASETS.items():
            rows = (
                '12AB5678;"ACME; BRASIL";2062;49;123,45;03;\n'
                if name == "empresas"
                else f'{name[:3]};"Description; {name}"\n'
            ).encode("utf-8")
            archive_path = root / "archives" / f"{name}.zip"
            archive_path.parent.mkdir(parents=True, exist_ok=True)
            member_name = f"{name}/{name}.csv"
            with zipfile.ZipFile(archive_path, "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr(member_name, rows)
            with zipfile.ZipFile(archive_path) as archive:
                info = archive.getinfo(member_name)
            datasets.append(
                {
                    "logical_name": name,
                    "release": "2026-07",
                    "parser": self.parser(fields),
                    "archives": [
                        {
                            "source_url": f"https://example.test/2026-07/{name}.zip",
                            "filename": f"{name}.zip",
                            "path": f"archives/{name}.zip",
                            "bytes": archive_path.stat().st_size,
                            "sha256": self.digest(archive_path.read_bytes()),
                            "retrieved_at": "2026-07-20T12:00:00Z",
                            "members": [
                                {
                                    "path": member_name,
                                    "bytes": info.file_size,
                                    "crc32": f"{info.CRC:08x}",
                                    "sha256": self.digest(rows),
                                }
                            ],
                        }
                    ],
                }
            )

        tom = root / "references/tom.csv"
        tom.parent.mkdir(parents=True)
        tom.write_text("7107;3550308;SAO PAULO;São Paulo;SP\n", encoding="utf-8")
        ibge = root / "references/ibge.json"
        ibge.write_text(
            json.dumps(
                [
                    {
                        "id": 3550308,
                        "nome": "São Paulo",
                        "regiao-imediata": {
                            "id": 350001,
                            "regiao-intermediaria": {
                                "id": 3501,
                                "UF": {
                                    "id": 35,
                                    "regiao": {"id": 3},
                                },
                            },
                        },
                    }
                ]
            ),
            encoding="utf-8",
        )
        return {
            "manifest_version": 1,
            "release": "2026-07",
            "created_at": "2026-07-20T12:10:00Z",
            "publisher": "receita-federal",
            "producer_version": "atlas-discovery/1",
            "discovery_run_id": "synthetic-2026-07",
            "datasets": datasets,
            "references": {
                "tom": self.reference(tom, "references/tom.csv", "text/csv", self.parser(5)),
                "ibge_localities": self.reference(
                    ibge, "references/ibge.json", "application/json"
                ),
            },
        }

    @staticmethod
    def parser(fields: int) -> dict:
        return {
            "encoding": "utf-8",
            "delimiter": ";",
            "header": False,
            "quote": '"',
            "escape": "\\",
            "expected_fields": fields,
        }

    def reference(self, path: Path, relative: str, content_type: str, parser=None) -> dict:
        value = {
            "source_url": f"https://example.test/{path.name}",
            "path": relative,
            "retrieved_at": "2026-07-20T12:00:00Z",
            "content_type": content_type,
            "bytes": path.stat().st_size,
            "sha256": self.digest(path.read_bytes()),
        }
        if parser is not None:
            value["parser"] = parser
        return value

    @staticmethod
    def digest(value: bytes) -> str:
        return hashlib.sha256(value).hexdigest()

    def test_accepts_complete_manifest_and_quoted_delimiters(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            diagnostics = company_data_manifest.validate_manifest(self.fixture(root), root)
            self.assertEqual(diagnostics, [])

    def test_inspection_builds_archive_and_reference_evidence_without_extracting(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = self.fixture(root)
            archive_path = root / "archives/empresas.zip"
            inspected = company_data_manifest.inspect_archive(
                archive_path,
                raw_root=root,
                source_url="https://example.test/2026-07/empresas.zip",
                retrieved_at="2026-07-20T12:00:00Z",
            )
            self.assertEqual(inspected, manifest["datasets"][0]["archives"][0])
            self.assertFalse((root / "empresas/empresas.csv").exists())

            tom = root / "references/tom.csv"
            inspected_tom = company_data_manifest.inspect_file(
                tom,
                raw_root=root,
                source_url="https://example.test/tom.csv",
                retrieved_at="2026-07-20T12:00:00Z",
                content_type="text/csv",
            )
            self.assertEqual(inspected_tom["sha256"], self.digest(tom.read_bytes()))

    def test_rejects_missing_group_mixed_release_and_missing_parser_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = self.fixture(root)
            manifest["datasets"] = [
                item for item in manifest["datasets"] if item["logical_name"] != "motivos"
            ]
            manifest["datasets"][0]["release"] = "2026-06"
            del manifest["datasets"][1]["parser"]["encoding"]
            rules = {item.rule for item in company_data_manifest.validate_manifest(manifest, root)}
            self.assertTrue({"CDM_DATASET_MISSING", "CDM_MIXED_RELEASE", "CDM_REQUIRED"} <= rules)

    def test_rejects_hash_mismatch_wrong_field_count_and_unsafe_member(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = self.fixture(root)
            archive = manifest["datasets"][0]["archives"][0]
            archive["sha256"] = "0" * 64
            archive["members"][0]["path"] = "../escape.csv"
            manifest["references"]["tom"]["parser"]["expected_fields"] = 4
            rules = {item.rule for item in company_data_manifest.validate_manifest(manifest, root)}
            self.assertTrue({"CDM_SHA256", "CDM_ZIP_PATH", "CDM_FIELD_COUNT"} <= rules)

    def test_rejects_path_escape_and_incomplete_ibge_hierarchy(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = self.fixture(root)
            manifest["datasets"][0]["archives"][0]["path"] = "../outside.zip"
            ibge = root / "references/ibge.json"
            ibge.write_text('[{"id": 3550308}]', encoding="utf-8")
            reference = manifest["references"]["ibge_localities"]
            reference["bytes"] = ibge.stat().st_size
            reference["sha256"] = self.digest(ibge.read_bytes())
            rules = {item.rule for item in company_data_manifest.validate_manifest(manifest, root)}
            self.assertTrue({"CDM_PATH", "CDM_IBGE_HIERARCHY"} <= rules)

    def test_rejects_invalid_declared_encoding_and_malformed_ibge_json(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = self.fixture(root)
            tom = root / "references/tom.csv"
            tom.write_bytes(b"7107;3550308;S\xffO PAULO;Sao Paulo;SP\n")
            manifest["references"]["tom"]["bytes"] = tom.stat().st_size
            manifest["references"]["tom"]["sha256"] = self.digest(tom.read_bytes())
            ibge = root / "references/ibge.json"
            ibge.write_text("{", encoding="utf-8")
            manifest["references"]["ibge_localities"]["bytes"] = ibge.stat().st_size
            manifest["references"]["ibge_localities"]["sha256"] = self.digest(
                ibge.read_bytes()
            )
            rules = {item.rule for item in company_data_manifest.validate_manifest(manifest, root)}
            self.assertTrue({"CDM_PARSE", "CDM_IBGE_JSON"} <= rules)


if __name__ == "__main__":
    unittest.main()
