"""Real Pynini integration tests, executed inside the export Docker image."""
import hashlib
from pathlib import Path
import tempfile
import unittest

try:
    import pynini
except ModuleNotFoundError:
    pynini = None

from export_itn import export_far


@unittest.skipIf(pynini is None, "Pynini integration tests require the Linux export image")
class FarExportTests(unittest.TestCase):
    def test_export_and_apply_standard_vector_fst(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "classify.far"
            grammar = pynini.cross("one hundred twenty three", "123")
            original = grammar.copy()
            metadata = export_far(grammar, path)
            self.assertTrue(pynini.equal(original, grammar))
            self.assertEqual(metadata["bytes"], path.stat().st_size)
            self.assertEqual(metadata["sha256"], hashlib.sha256(path.read_bytes()).hexdigest())
            with pynini.Far(str(path), mode="r") as far:
                loaded = far["grammar"]
                self.assertEqual(loaded.fst_type(), "vector")
                self.assertEqual(loaded.arc_type(), "standard")
                self.assertEqual(pynini.shortestpath("one hundred twenty three" @ loaded).string(), "123")

    def test_unicode_round_trip(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "verbalize.far"
            text = "中文 日本語 café 😀"
            export_far(pynini.cross(text, text), path)
            with pynini.Far(str(path), mode="r") as far:
                self.assertEqual(pynini.shortestpath(text @ far["grammar"]).string(), text)


if __name__ == "__main__":
    unittest.main()
