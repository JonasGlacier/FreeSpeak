"""Validate workflow inputs and assemble flat GitHub Release assets. Standard library only."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil

from export_itn import LANGUAGES, expected_spec_for


def parse_languages(value):
    languages = list(LANGUAGES) if value.strip() == "all" else re.split(r"[\s,]+", value.strip())
    if not languages or any(language not in LANGUAGES for language in languages):
        raise ValueError(f"Use 'all' or codes from: {' '.join(LANGUAGES)}")
    return list(dict.fromkeys(languages))


def prepare():
    languages = parse_languages(os.environ["REQUESTED_LANGUAGES"])
    if os.environ.get("PUBLISH") == "true" and os.environ.get("REPOSITORY_PRIVATE") == "true":
        raise ValueError("The app cannot authenticate private release downloads. Use a public repository or disable publishing.")
    # A fresh tag for every run/attempt avoids overwriting previously downloaded packs.
    tag = f"nemo-itn-{os.environ['GITHUB_RUN_ID']}-{os.environ['GITHUB_RUN_ATTEMPT']}"
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        output.write(f"languages={json.dumps(languages)}\ntag={tag}\n")


def assemble(source, output, repository, tag, languages):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Invalid GitHub repository")
    if not re.fullmatch(r"nemo-itn-[0-9]+-[0-9]+", tag):
        raise ValueError("Invalid release tag")
    if not isinstance(languages, list) or not languages or any(lang not in LANGUAGES for lang in languages):
        raise ValueError("Invalid language selection")
    if len(set(languages)) != len(languages):
        raise ValueError("Duplicate languages")
    if output.exists() and any(output.iterdir()):
        raise ValueError("Output directory must be empty")
    output.mkdir(parents=True, exist_ok=True)
    packs, smoke = [], {}
    base = f"https://github.com/{repository}/releases/download/{tag}"
    for language in languages:
        expected_engine, expected_revision = expected_spec_for(language)
        directory = source / f"nemo-itn-{language}"
        catalog = json.loads((directory / "catalog.json").read_text(encoding="utf-8"))
        if catalog.get("format") != 1 or len(catalog.get("packs", [])) != 1:
            raise ValueError(f"Invalid catalog for {language}")
        pack = catalog["packs"][0]
        actual_engine = pack.get("engine", "nemo")
        if pack["language"] != language or pack["revision"] != expected_revision or actual_engine != expected_engine:
            raise ValueError(f"Unexpected language/engine/revision for {language}: {pack}")
        pack["engine"] = expected_engine
        if sorted(file["name"] for file in pack["files"]) != ["classify.far", "verbalize.far"]:
            raise ValueError(f"Invalid FAR pair for {language}")
        for file in pack["files"]:
            path = directory / language / file["name"]
            if path.stat().st_size != file["bytes"] or not 0 < file["bytes"] <= 536_870_912:
                raise ValueError(f"Invalid size for {path}; Android allows at most 512 MiB per file")
            with path.open("rb") as stream:
                digest = hashlib.file_digest(stream, "sha256").hexdigest()
            if digest != file["sha256"]:
                raise ValueError(f"SHA-256 mismatch for {path}")
            asset = f"{language}-{file['name']}"
            shutil.copyfile(path, output / asset)
            # Local names remain unchanged; only HTTP asset names are flattened.
            file["url"] = f"{base}/{asset}"
        examples = json.loads((directory / "smoke.json").read_text(encoding="utf-8"))
        if language not in examples or "123" not in examples[language]["expected"]:
            raise ValueError(f"Missing smoke result for {language}")
        smoke[language] = examples[language]

        has_license = False
        for name in ("LICENSE-NeMo.txt", "LICENSE-WeTextProcessing.txt", "PROVENANCE.txt", "NOTICE-NeMo.txt"):
            path = directory / name
            if not path.exists():
                if name == "PROVENANCE.txt":
                    raise FileNotFoundError(f"Missing {path}")
                continue
            if name.startswith("LICENSE-"):
                has_license = True
            shutil.copyfile(path, output / f"{language}-{name}")
        if not has_license:
            raise FileNotFoundError(f"Missing upstream license file for {language}")
        packs.append(pack)
    # Never advertise a partial language set if validation fails partway through.
    (output / "catalog.json").write_text(json.dumps({"format": 1, "packs": packs}, indent=2), encoding="utf-8")
    (output / "smoke.json").write_text(json.dumps(smoke, ensure_ascii=False, indent=2), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("prepare")
    release = commands.add_parser("assemble")
    release.add_argument("--input", type=Path, required=True)
    release.add_argument("--output", type=Path, required=True)
    release.add_argument("--repository", required=True)
    release.add_argument("--tag", required=True)
    release.add_argument("--languages-json", required=True)
    args = parser.parse_args()
    if args.command == "prepare":
        prepare()
    else:
        assemble(args.input, args.output, args.repository, args.tag, json.loads(args.languages_json))


if __name__ == "__main__":
    main()
