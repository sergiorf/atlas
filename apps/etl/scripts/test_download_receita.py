import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "download_receita", Path(__file__).with_name("download_receita.py")
)
download_receita = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(download_receita)


class DownloadReceitaTest(unittest.TestCase):
    def test_integrated_run_writes_raw_success_status(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with (
                patch.object(
                    download_receita,
                    "webdav_entries",
                    return_value=[
                        {"name": "Estabelecimentos0.zip", "collection": False, "size": 12}
                    ],
                ),
                patch.object(download_receita, "download", return_value=12),
                patch.object(download_receita, "extract_restartable"),
            ):
                result = download_receita.main(
                    [
                        "--month", "2026-06", "--extract",
                        "--output-root", str(root / "raw"),
                        "--status-dir", str(root / "status"),
                    ]
                )

            self.assertEqual(result, 0)
            status = json.loads(
                (root / "status/receita/estabelecimentos/2026-06/raw.json").read_text()
            )
            self.assertEqual(status["status"], "success")
            self.assertEqual(status["layer"], "raw")
            self.assertEqual(status["file_count"], 1)
            self.assertEqual(status["byte_count"], 12)
            self.assertEqual(status["extracted_file_count"], 1)

    def test_failed_explicit_release_records_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with patch.object(
                download_receita, "webdav_entries", side_effect=RuntimeError("unavailable")
            ):
                with self.assertRaisesRegex(RuntimeError, "unavailable"):
                    download_receita.main(
                        [
                            "--month", "2026-06",
                            "--output-root", str(root / "raw"),
                            "--status-dir", str(root / "status"),
                        ]
                    )

            status = json.loads(
                (root / "status/receita/estabelecimentos/2026-06/raw.json").read_text()
            )
            self.assertEqual(status["status"], "failed")
            self.assertEqual(status["error_message"], "unavailable")


if __name__ == "__main__":
    unittest.main()
