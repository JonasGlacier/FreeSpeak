"""Export FreeSpeak format-1 ITN packs, using full upstream NeMo grammars.

The runtime deliberately consumes named vector FSTs instead of relying on the
keys/cache layouts in NeMo's Sparrowhawk exporter. No grammar rules are changed.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
from urllib.parse import urlparse

REVISION = "ddadfb2a38d2bc6b8cc6232c4f915eb60f500688"
LANGUAGES = ("en", "zh", "de", "fr", "es", "ja", "ru", "pt")
SMOKE = {
    "en": "one hundred twenty three",
    "zh": "一百二十三",
    "de": "einhundertdreiundzwanzig",
    "fr": "cent vingt-trois",
    "es": "ciento veintitrés",
    "ja": "百二十三",
    "ru": "сто двадцать три",
    "pt": "cento e vinte e três",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("/output"))
    parser.add_argument("--base-url", required=True, help="Immutable HTTPS directory where the output will be published")
    parser.add_argument("--languages", nargs="+", choices=LANGUAGES, default=list(LANGUAGES))
    parser.add_argument("--nemo-source", type=Path, default=Path("/opt/nemo"))
    args = parser.parse_args()
    if urlparse(args.base_url).scheme != "https" or not urlparse(args.base_url).hostname:
        parser.error("--base-url must be HTTPS")
    actual = subprocess.check_output(["git", "-C", str(args.nemo_source), "rev-parse", "HEAD"], text=True).strip()
    if actual != REVISION:
        raise RuntimeError(f"Expected NeMo {REVISION}, got {actual}")

    import pynini
    from nemo_text_processing.inverse_text_normalization.inverse_normalize import InverseNormalizer

    args.output.mkdir(parents=True, exist_ok=True)
    packs = []
    smoke_results = {}
    for language in dict.fromkeys(args.languages):
        normalizer = InverseNormalizer(lang=language, input_case="cased")
        directory = args.output / language
        directory.mkdir(parents=True, exist_ok=True)
        files = []
        for name, grammar in (("classify.far", normalizer.tagger.fst), ("verbalize.far", normalizer.verbalizer.fst)):
            graph = pynini.convert(grammar, "vector")
            if graph.arc_type() != "standard":
                raise RuntimeError("Android runtime requires tropical/standard arcs")
            graph.set_input_symbols(None)
            graph.set_output_symbols(None)
            graph.arcsort("ilabel")
            path = directory / name
            with pynini.Far(str(path), mode="w", arc_type="standard", far_type="sttable") as far:
                far["grammar"] = graph
            # Verify serialized compatibility in the build environment.
            with pynini.Far(str(path), mode="r") as far:
                if not pynini.equal(graph, far["grammar"]):
                    raise RuntimeError(f"FAR round trip failed: {path}")
            files.append({"name": name, "url": f"{args.base_url.rstrip('/')}/{language}/{name}",
                          "bytes": path.stat().st_size, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
        spoken = SMOKE[language]
        written = normalizer.inverse_normalize(spoken, verbose=False)
        if "123" not in written:
            raise RuntimeError(f"{language} smoke test failed: {spoken!r} -> {written!r}")
        smoke_results[language] = {"input": spoken, "expected": written}
        packs.append({"language": language, "revision": REVISION, "files": files})
        print(f"Exported {language}: {sum(f['bytes'] for f in files):,} bytes", flush=True)

    # Write catalog last, only after ALL requested languages passed export and smoke checks.
    (args.output / "catalog.json").write_text(json.dumps({"format": 1, "packs": packs}, indent=2), encoding="utf-8")
    (args.output / "smoke.json").write_text(json.dumps(smoke_results, ensure_ascii=False, indent=2), encoding="utf-8")
    shutil.copyfile(args.nemo_source / "LICENSE", args.output / "LICENSE-NeMo.txt")
    notice = args.nemo_source / "NOTICE"
    if notice.exists():
        shutil.copyfile(notice, args.output / "NOTICE-NeMo.txt")
    (args.output / "PROVENANCE.txt").write_text(
        f"NVIDIA NeMo Text Processing {REVISION}\nApache-2.0\n"
        f"Full ITN, cased input, standard byte-labelled vector FSTs. Pynini {pynini.__version__}\n",
        encoding="utf-8")


if __name__ == "__main__":
    main()
