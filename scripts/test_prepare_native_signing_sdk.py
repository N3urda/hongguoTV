import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from prepare_native_signing_sdk import IDENTITY_SLOTS, prepare, sanitize_metadata


class PrepareNativeSigningSdkTest(unittest.TestCase):
    def metadata(self):
        return ["8662", "", "", "synthetic-license", "", "update", "did", "bdid", "iid", "session",
                "0", "-1", "5", "fetched-did", "1", ["private-extension"], ["private-extension"]]

    def test_removes_all_identity_and_extension_slots_without_mutating_input(self):
        source = self.metadata()
        result = sanitize_metadata(source)
        self.assertTrue(all(result[i] == "" for i in IDENTITY_SLOTS))
        self.assertEqual(result[14:], ["0", [], []])
        self.assertEqual(source[6], "did")

    def test_rejects_unknown_schema_and_application(self):
        wrong_app = self.metadata()
        wrong_app[0] = "1967"
        unsupported_mode = self.metadata()
        unsupported_mode[12] = "99999"
        for value in [[], ["8662"] * 17, wrong_app, unsupported_mode]:
            with self.subTest(value_type=type(value).__name__), self.assertRaises(ValueError):
                sanitize_metadata(value)

    def test_rejects_unreviewed_library_before_writing_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / "synthetic.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("lib/arm64-v8a/libmetasec_ml.so", b"unreviewed")
            metadata = root / "metadata.json"
            metadata.write_text(json.dumps(self.metadata()))
            output = root / "prepared"
            with self.assertRaises(ValueError):
                prepare(apk, metadata, output)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
