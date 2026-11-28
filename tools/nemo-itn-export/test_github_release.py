import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from export_itn import LANGUAGES, expected_spec_for
from github_release import assemble, parse_languages


class ReleaseTests(unittest.TestCase):
    def test_language_selection(self):
        self.assertEqual(parse_languages("all"), list(LANGUAGES))
        self.assertEqual(parse_languages("en, zh en"), ["en", "zh"])
        for invalid in ("", "xx", "en;pwd", "all en"):
            with self.assertRaises(ValueError):
                parse_languages(invalid)

    def fixture(self, root, language):
        engine, revision = expected_spec_for(language)
        directory = root / f"nemo-itn-{language}"
        (directory / language).mkdir(parents=True)
        files = []
        for name in ("classify.far", "verbalize.far"):
            data = f"fixture-{language}-{name}".encode()
            (directory / language / name).write_bytes(data)
            files.append({
                "name": name,
                "url": "https://example.com/old",
                "bytes": len(data),
                "sha256": hashlib.sha256(data).hexdigest(),
            })
        (directory / "catalog.json").write_text(json.dumps({
            "format": 1,
            "packs": [{"language": language, "revision": revision, "engine": engine, "files": files}],
        }))
        (directory / "smoke.json").write_text(json.dumps({
            language: {"input": "example", "expected": "123", "engine": engine}
        }))
        license_name = "LICENSE-WeTextProcessing.txt" if engine == "wetext" else "LICENSE-NeMo.txt"
        for name in (license_name, "PROVENANCE.txt"):
            (directory / name).write_text("fixture")
        return directory

    def test_combines_and_rewrites_flat_release_urls(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for language in ("en", "zh"):
                self.fixture(root, language)
            assemble(root, root / "release", "owner/repo", "nemo-itn-123-1", ["en", "zh"])
            catalog = json.loads((root / "release/catalog.json").read_text())
            self.assertEqual(len(catalog["packs"]), 2)
            by_lang = {p["language"]: p for p in catalog["packs"]}
            self.assertEqual(by_lang["en"]["engine"], "nemo")
            self.assertEqual(by_lang["zh"]["engine"], "wetext")
            for pack in catalog["packs"]:
                for file in pack["files"]:
                    asset = f"{pack['language']}-{file['name']}"
                    self.assertEqual(file["url"], f"https://github.com/owner/repo/releases/download/nemo-itn-123-1/{asset}")
                    self.assertTrue((root / "release" / asset).is_file())

    def test_rejects_corruption_without_catalog(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            directory = self.fixture(root, "en")
            path = directory / "en/classify.far"
            data = path.read_bytes()
            path.write_bytes(b"X" + data[1:])
            with self.assertRaisesRegex(ValueError, "SHA-256"):
                assemble(root, root / "release", "owner/repo", "nemo-itn-123-1", ["en"])
            self.assertFalse((root / "release/catalog.json").exists())

    def test_missing_language_prevents_partial_catalog(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.fixture(root, "en")
            with self.assertRaises(FileNotFoundError):
                assemble(root, root / "release", "owner/repo", "nemo-itn-123-1", ["en", "zh"])
            self.assertFalse((root / "release/catalog.json").exists())


if __name__ == "__main__":
    unittest.main()
