import importlib.util
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
                 "Paises.zip", "Qualificacoes.zip", "Motivos.zip"]
        return [{"name": name, "collection": False, "size": None} for name in names]

    @staticmethod
    def fake_archive_download(_url, destination, _expected_size, _token):
        destination.parent.mkdir(parents=True, exist_ok=True)
        fields = 7 if destination.name.lower().startswith("empresas") else 2
        with zipfile.ZipFile(destination, "w") as archive:
            archive.writestr(destination.stem + ".csv", ";".join(["x"] * fields) + "\n")
        return destination.stat().st_size

    @staticmethod
    def fake_public_download(url, destination):
        destination.parent.mkdir(parents=True, exist_ok=True)
        content = ibge_payload() if "ibge" in url else b"a;b;c;d;e\n"
        destination.write_bytes(content)
        return len(content)

    def test_download_writes_verified_manifest_and_visible_status(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with (
                patch.object(download_company_data.download_receita, "webdav_entries", return_value=self.entries()),
                patch.object(download_company_data.download_receita, "download", side_effect=self.fake_archive_download),
                patch.object(download_company_data, "download_public", side_effect=self.fake_public_download),
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
            status = json.loads((root / "status/receita/company-data/2026-05/raw.json").read_text())
            self.assertEqual(status["status"], "success")
            self.assertEqual(status["file_count"], 9)
            self.assertEqual(status["output_path"], str(manifest_path))

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


if __name__ == "__main__":
    unittest.main()
