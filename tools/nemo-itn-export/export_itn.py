"""Export FreeSpeak format-1 ITN packs:
- Mandarin Chinese (`zh`): WeTextProcessing (`engine = "wetext"`), preserving standalone 0-9,
  protecting idioms/common phrases (一心一意, 一共, etc.), supporting percentages (百分之六十八 -> 68%),
  and keeping spaces in Chinese-English code-switching.
- Other 7 languages (`en`, `de`, `fr`, `es`, `ja`, `ru`, `pt`): NVIDIA NeMo Text Processing
  (`engine = "nemo"`), cased input.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
from urllib.parse import urlparse

NEMO_REVISION = "ddadfb2a38d2bc6b8cc6232c4f915eb60f500688"
WETEXT_REVISION = "94ab845bc83330a4819a799831462d6f7a7b93ab"
REVISION = NEMO_REVISION  # Backwards-compatible alias for NeMo packs
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

ZH_REGRESSION_CASES = (
    ("一百二十三", "123"),
    ("百分之六十八", "68%"),
    ("一心一意", "一心一意"),
    ("一共", "一共"),
    ("一共有一百二十三个苹果", "一共有123个苹果"),
    ("我有三十五个 Apple Watch", "我有35个 Apple Watch"),
)

EN_REGRESSION_CASES = (
    ("There are two hundred fifty people in the town.", "There are 250 people in the town."),
    ("There are thirty five students in the classroom.", "There are 35 students in the classroom."),
    ("There are five students.", "There are five students."),
)


def expected_spec_for(language: str) -> tuple[str, str]:
    """Returns `(engine, upstream_git_revision)` for `language`."""
    if language == "zh":
        return ("wetext", WETEXT_REVISION)
    return ("nemo", NEMO_REVISION)


def export_far(grammar, path: Path) -> dict:
    import pynini

    graph = pynini.convert(grammar.copy(), "vector")
    if graph.arc_type() != "standard":
        raise RuntimeError("Android runtime requires tropical/standard arcs")
    graph.set_input_symbols(None)
    graph.set_output_symbols(None)
    graph.arcsort("ilabel")
    with pynini.Far(str(path), mode="w", arc_type="standard", far_type="sttable") as far:
        far["grammar"] = graph
    with pynini.Far(str(path), mode="r") as far:
        reloaded = far["grammar"]
        if not pynini.equal(graph, reloaded):
            raise RuntimeError(f"FAR round trip failed: {path}")
    return {
        "bytes": path.stat().st_size,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
    }


def write_far_pair(directory: Path, base_url: str, language: str, classify_grammar, verbalize_grammar):
    import pynini

    files = []
    loaded = {}
    for name, grammar in (("classify.far", classify_grammar), ("verbalize.far", verbalize_grammar)):
        path = directory / name
        meta = export_far(grammar, path)
        with pynini.Far(str(path), mode="r") as far:
            loaded[name] = far["grammar"]
        files.append({
            "name": name,
            "url": f"{base_url.rstrip('/')}/{language}/{name}",
            "bytes": meta["bytes"],
            "sha256": meta["sha256"],
        })
    return files, loaded["classify.far"], loaded["verbalize.far"]


def simulate_android_wetext(spoken: str, classify_fst, verbalize_fst) -> str:
    import pynini
    from tn.token_parser import TokenParser

    tagged = pynini.shortestpath(
        pynini.accep(pynini.escape(spoken)) @ classify_fst, nshortest=1, unique=True
    ).string()
    parser = TokenParser("itn")
    parser.parse(tagged)
    pieces = []
    for tok in parser.tokens:
        if tok.name == "char" and list(tok.members.keys()) == ["value"]:
            pieces.append(tok.members["value"])
        else:
            single = tok.string(parser.orders)
            written = pynini.shortestpath(
                pynini.accep(pynini.escape(single)) @ verbalize_fst, nshortest=1, unique=True
            ).string()
            pieces.append(written)
    return "".join(pieces)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("/output"))
    parser.add_argument("--base-url", required=True, help="Immutable HTTPS directory where the output will be published")
    parser.add_argument("--languages", nargs="+", choices=LANGUAGES, default=list(LANGUAGES))
    parser.add_argument("--nemo-source", type=Path, default=Path("/opt/nemo"))
    parser.add_argument("--wetext-source", type=Path, default=Path("/opt/wetext"))
    args = parser.parse_args()
    if urlparse(args.base_url).scheme != "https" or not urlparse(args.base_url).hostname:
        parser.error("--base-url must be HTTPS")

    requested = list(dict.fromkeys(args.languages))
    need_nemo = any(lang != "zh" for lang in requested)
    need_wetext = "zh" in requested

    if need_nemo:
        actual_nemo = subprocess.check_output(
            ["git", "-C", str(args.nemo_source), "rev-parse", "HEAD"], text=True
        ).strip()
        if actual_nemo != NEMO_REVISION:
            raise RuntimeError(f"Expected NeMo {NEMO_REVISION}, got {actual_nemo}")

    if need_wetext:
        actual_wetext = subprocess.check_output(
            ["git", "-C", str(args.wetext_source), "rev-parse", "HEAD"], text=True
        ).strip()
        if actual_wetext != WETEXT_REVISION:
            raise RuntimeError(f"Expected WeTextProcessing {WETEXT_REVISION}, got {actual_wetext}")

    import pynini

    args.output.mkdir(parents=True, exist_ok=True)
    packs = []
    smoke_results = {}

    for language in requested:
        engine, rev = expected_spec_for(language)
        directory = args.output / language
        directory.mkdir(parents=True, exist_ok=True)

        if language == "zh":
            from itn.chinese.inverse_normalizer import InverseNormalizer as WeTextZhInverseNormalizer

            normalizer = WeTextZhInverseNormalizer(
                cache_dir=False,
                overwrite_cache=False,
                remove_interjections=False,
                enable_standalone_number=True,
                enable_0_to_9=False,
                enable_million=False,
            )
            files, classify_fst, verbalize_fst = write_far_pair(
                directory, args.base_url, language, normalizer.tagger, normalizer.verbalizer
            )
            for case_in, case_expected in ZH_REGRESSION_CASES:
                upstream_out = normalizer.normalize(case_in)
                android_out = simulate_android_wetext(case_in, classify_fst, verbalize_fst)
                if upstream_out != case_expected or android_out != case_expected:
                    raise RuntimeError(
                        f"zh regression failed on {case_in!r}: upstream={upstream_out!r}, "
                        f"android_sim={android_out!r}, expected={case_expected!r}"
                    )
            spoken = SMOKE[language]
            written = simulate_android_wetext(spoken, classify_fst, verbalize_fst)
        else:
            from nemo_text_processing.inverse_text_normalization.inverse_normalize import InverseNormalizer

            normalizer = InverseNormalizer(lang=language, input_case="cased")
            files, _, _ = write_far_pair(
                directory, args.base_url, language, normalizer.tagger.fst, normalizer.verbalizer.fst
            )
            spoken = SMOKE[language]
            written = normalizer.inverse_normalize(spoken, verbose=False)
            if language == "en":
                for case_in, case_expected in EN_REGRESSION_CASES:
                    got = normalizer.inverse_normalize(case_in, verbose=False)
                    if got != case_expected:
                        raise RuntimeError(f"en regression failed on {case_in!r}: got={got!r}, expected={case_expected!r}")

        if "123" not in written:
            raise RuntimeError(f"{language} smoke test failed: {spoken!r} -> {written!r}")
        smoke_results[language] = {"input": spoken, "expected": written, "engine": engine}
        packs.append({"language": language, "revision": rev, "engine": engine, "files": files})
        print(f"Exported {language} ({engine}): {sum(f['bytes'] for f in files):,} bytes", flush=True)

    # Write catalog last, only after ALL requested languages passed export and smoke checks.
    (args.output / "catalog.json").write_text(json.dumps({"format": 1, "packs": packs}, indent=2), encoding="utf-8")
    (args.output / "smoke.json").write_text(json.dumps(smoke_results, ensure_ascii=False, indent=2), encoding="utf-8")

    provenance_lines = [f"Pynini {pynini.__version__}\n"]
    if need_nemo:
        shutil.copyfile(args.nemo_source / "LICENSE", args.output / "LICENSE-NeMo.txt")
        notice = args.nemo_source / "NOTICE"
        if notice.exists():
            shutil.copyfile(notice, args.output / "NOTICE-NeMo.txt")
        provenance_lines.append(f"NVIDIA NeMo Text Processing {NEMO_REVISION} (Apache-2.0)\n")
    if need_wetext:
        shutil.copyfile(args.wetext_source / "LICENSE", args.output / "LICENSE-WeTextProcessing.txt")
        provenance_lines.append(f"WeTextProcessing {WETEXT_REVISION} (Apache-2.0)\n")

    (args.output / "PROVENANCE.txt").write_text("".join(provenance_lines), encoding="utf-8")


if __name__ == "__main__":
    main()
