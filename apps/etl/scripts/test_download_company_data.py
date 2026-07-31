import importlib.util
import gzip
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch


SCRIPT_DIR = Path(__file__).parent
sys.path.insert(0, str(SCRIPT_DIR))
SPEC = importlib.util.spec_from_file_location(
    "download_company_data", SCRIPT_DIR / "download_company_data.py"
)
download_company_data = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(download_company_data)


def ibge_payload() -> bytes:
    return json.dumps([
        {
            "id": 1,
            "regiao-imediata": {
                "id": 2,
                "regiao-intermediaria": {
                    "id": 3,
                    "UF": {"id": 4, "regiao": {"id": 5}},
                },
            },
        }
    ]).encode()


class DownloadCompanyDataTest(unittest.TestCase):
    def entries(self):
        names = ["Empresas0.zip", "Cnaes.zip", "Municipios.zip", "Naturezas.zip",
                 "Paises.zip", "Qualificacoes.zip", "Motivos.zip", "Socios0.zip",
                 "Simples.zip"]
        return [{"name": name, "collection": False, "size": None} for name in names]

    @staticmethod
    def fake_archive_download(_url, destination, _expected_size, _token):
        destination.parent.mkdir(parents=True, exist_ok=True)
        lower = destination.name.lower()
        fields = 11 if lower.startswith("socios") else 7 if lower.startswith(("empresas", "simples")) else 2
        with zipfile.ZipFile(destination, "w") as archive:
            archive.writestr(destination.stem + ".csv", ";".join(["x"] * fields) + "\n")
        return destination.stat().st_size

    @staticmethod
    def fake_public_download(url, destination):
        destination.parent.mkdir(parents=True, exist_ok=True)
        content = gzip.compress(ibge_payload()) if "ibge" in url else b"a;b;c;d;e\n"
        destination.write_bytes(content)
        return len(content)

    @staticmethod
    def fake_establishment_download(argv):
        month = argv[argv.index("--month") + 1]
        output_root = Path(argv[argv.index("--output-root") + 1])
        status_dir = Path(argv[argv.index("--status-dir") + 1])
        dataset_root = output_root / month / "estabelecimentos"
        archive_path = dataset_root / "archives/Estabelecimentos0.zip"
        archive_path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(archive_path, "w") as archive:
            archive.writestr("K3241.K03200Y0.D60713.ESTABELE", "fixture\n")
        extracted_path = dataset_root / "extracted/K3241.K03200Y0.D60713.ESTABELE"
        extracted_path.parent.mkdir(parents=True, exist_ok=True)
        extracted_path.write_text("fixture\n")
        (dataset_root / "manifest.json").write_text(json.dumps({
            "source": "fixture",
            "month": month,
            "dataset": "estabelecimentos",
            "files": {
                archive_path.name: {
                    "url": "fixture",
                    "bytes": archive_path.stat().st_size,
                    "status": "complete",
                    "extracted": True,
                }
            },
        }))
        status_path = status_dir / f"receita/estabelecimentos/{month}/raw.json"
        status_path.parent.mkdir(parents=True, exist_ok=True)
        status_path.write_text(json.dumps({"status": "success"}))
        return 0

    def test_download_writes_verified_manifest_and_visible_status(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with (
                patch.object(download_company_data.download_receita, "webdav_entries", return_value=self.entries()),
                patch.object(download_company_data.download_receita, "download", side_effect=self.fake_archive_download),
                patch.object(download_company_data, "download_public", side_effect=self.fake_public_download),
                patch.object(
                    download_company_data.download_receita,
                    "main",
                    side_effect=self.fake_establishment_download,
                ) as establishment_main,
            ):
                result = download_company_data.main([
                    "--month", "2026-05", "--output-root", str(root / "raw"),
                    "--status-dir", str(root / "status"), "--tom-encoding", "utf-8",
                ])

            self.assertEqual(result, 0)
            manifest_path = root / "raw/2026-05/company-data/source-manifest.json"
            manifest = json.loads(manifest_path.read_text())
            self.assertEqual({item["logical_name"] for item in manifest["datasets"]}, set(download_company_data.DATASET_PATTERNS))
            self.assertEqual(download_company_data.company_data_manifest.validate_manifest(manifest, manifest_path.parent), [])
            self.assertEqual(manifest["references"]["ibge_localities"]["content_encoding"], "gzip")
            status = json.loads((root / "status/receita/company-data/2026-05/raw.json").read_text())
            self.assertEqual(status["status"], "success")
            self.assertEqual(status["file_count"], 13)
            self.assertEqual(status["output_path"], str(manifest_path))
            establishment_args = establishment_main.call_args.args[0]
            self.assertEqual(establishment_args[establishment_args.index("--month") + 1], "2026-05")
            self.assertIn("--extract", establishment_args)

    def test_missing_group_records_failed_status(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with patch.object(download_company_data.download_receita, "webdav_entries", return_value=[]):
                with self.assertRaisesRegex(RuntimeError, "No empresas"):
                    download_company_data.main([
                        "--month", "2026-05", "--output-root", str(root / "raw"),
                        "--status-dir", str(root / "status"),
                    ])
            status = json.loads((root / "status/receita/company-data/2026-05/raw.json").read_text())
            self.assertEqual(status["status"], "failed")

    def test_establishment_failure_records_failed_company_data_status(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with (
                patch.object(download_company_data.download_receita, "webdav_entries", return_value=self.entries()),
                patch.object(download_company_data.download_receita, "download", side_effect=self.fake_archive_download),
                patch.object(download_company_data, "download_public", side_effect=self.fake_public_download),
                patch.object(
                    download_company_data.download_receita,
                    "main",
                    side_effect=RuntimeError("establishment unavailable"),
                ),
            ):
                with self.assertRaisesRegex(RuntimeError, "establishment unavailable"):
                    download_company_data.main([
                        "--month", "2026-05", "--output-root", str(root / "raw"),
                        "--status-dir", str(root / "status"), "--tom-encoding", "utf-8",
                    ])
            self.assertTrue((root / "raw/2026-05/company-data/source-manifest.json").is_file())
            status = json.loads((root / "status/receita/company-data/2026-05/raw.json").read_text())
            self.assertEqual(status["status"], "failed")

    def test_readiness_rejects_mismatched_establishment_release(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with (
                patch.object(download_company_data.download_receita, "webdav_entries", return_value=self.entries()),
                patch.object(download_company_data.download_receita, "download", side_effect=self.fake_archive_download),
                patch.object(download_company_data, "download_public", side_effect=self.fake_public_download),
                patch.object(
                    download_company_data.download_receita,
                    "main",
                    side_effect=self.fake_establishment_download,
                ),
            ):
                download_company_data.main([
                    "--month", "2026-05", "--output-root", str(root / "raw"),
                    "--status-dir", str(root / "status"), "--tom-encoding", "utf-8",
                ])
            manifest_path = root / "raw/2026-05/estabelecimentos/manifest.json"
            manifest = json.loads(manifest_path.read_text())
            manifest["month"] = "2026-06"
            manifest_path.write_text(json.dumps(manifest))
            with self.assertRaisesRegex(RuntimeError, "expected 2026-05, found 2026-06"):
                download_company_data.validate_coordinated_readiness(
                    release="2026-05", output_root=root / "raw"
                )

    def test_readiness_rejects_missing_extracted_member(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.fake_establishment_download([
                "--month", "2026-05", "--output-root", str(root / "raw"),
                "--status-dir", str(root / "status"),
            ])
            extracted = root / "raw/2026-05/estabelecimentos/extracted/K3241.K03200Y0.D60713.ESTABELE"
            extracted.unlink()
            company_root = root / "raw/2026-05/company-data"
            company_root.mkdir(parents=True)
            with patch.object(
                download_company_data.company_data_manifest,
                "validate_manifest",
                return_value=[],
            ):
                (company_root / "source-manifest.json").write_text(json.dumps({"release": "2026-05"}))
                with self.assertRaisesRegex(RuntimeError, "Missing or incomplete extracted file"):
                    download_company_data.validate_coordinated_readiness(
                        release="2026-05", output_root=root / "raw"
                    )

    def test_readiness_rejects_missing_establishment_archive(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.fake_establishment_download([
                "--month", "2026-05", "--output-root", str(root / "raw"),
                "--status-dir", str(root / "status"),
            ])
            archive = root / "raw/2026-05/estabelecimentos/archives/Estabelecimentos0.zip"
            archive.unlink()
            company_root = root / "raw/2026-05/company-data"
            company_root.mkdir(parents=True)
            with patch.object(
                download_company_data.company_data_manifest,
                "validate_manifest",
                return_value=[],
            ):
                (company_root / "source-manifest.json").write_text(json.dumps({"release": "2026-05"}))
                with self.assertRaisesRegex(RuntimeError, "Missing establishment archive"):
                    download_company_data.validate_coordinated_readiness(
                        release="2026-05", output_root=root / "raw"
                    )


if __name__ == "__main__":
    unittest.main()
