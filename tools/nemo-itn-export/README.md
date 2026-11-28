# NeMo ITN language packs

The app uses **full NVIDIA NeMo ITN**, not hand-written numeric substitutions.
Python/Pynini run only when building packs. On Android, a private OpenFST runtime
loads two FAR archives and applies classification and verbalization offline.

## Build and publish

### GitHub Actions (no local Docker or WSL required)

1. Push `.github/workflows/nemo-itn-packs.yml` and the exporter scripts to GitHub.
   The workflow must be on the repository's **default branch** for the manual
   **Run workflow** button to appear.
2. Open **Actions → Build NeMo ITN packs → Run workflow**.
3. Set **languages** to `en` for a first build, `en zh de` (commas also work) for
   a subset, or `all` for all eight languages.
4. Enable **publish** to create a GitHub Release with downloadable packs. This
   requires a **public repository**: the app cannot authenticate private release
   URLs. Publishing uses the built-in `GITHUB_TOKEN`; no additional secret is
   needed. Repository/organization policy must allow `contents: write` for the
   publishing job.
5. To ship a new pack release, replace `app/src/main/assets/itn/catalog.json`
   with the release's verified catalog and rebuild the app. Users open
   **Settings → Number & text formatting**, tap the download icon for a language,
   and enable formatting. The pack list works without a network request.

The workflow runs only when manually triggered. Each language builds in its own
Ubuntu runner, with at most two language jobs running simultaneously and a
three-hour limit per job. All requested languages must pass the exporter checks
before the combined catalog can be published. Individual successful language
artifacts remain available if another language fails. Builds use GitHub-hosted
runner disk/RAM and Actions minutes; no grammar compilation runs on your PC.

With **publish disabled** (the default), download `nemo-itn-release` from the run's
**Artifacts** section. Artifacts expire after seven days. Their catalog URLs
point to the planned release and **are not live**; for immediate app downloads,
run again with publishing enabled. Individual `nemo-itn-<language>` artifacts are
intermediate exports, not the final GitHub Release layout.

Published packs use a unique tag `nemo-itn-<run-id>-<attempt>` and are marked as a
prerelease so they do not replace the app's latest release. Uploads happen to a
draft first; the release becomes public only after all assets upload. GitHub
Release assets cannot contain subdirectories, so the assembly script names them
`en-classify.far`, `en-verbalize.far`, etc., and rewrites the catalog URLs while
preserving the local file names expected by Android. Sizes and SHA-256 hashes
are rechecked during assembly, including Android's 512 MiB per-file limit.

An out-of-memory error or timeout means that grammar needs a larger runner or
further export optimization; the workflow does not bypass failed checks. These
export checks validate NeMo/FAR generation, not real-device language coverage.

### Local Docker alternative

Run with a Linux Docker daemon (Docker Desktop Linux containers works):

```sh
docker build -t freespeak-nemo-itn tools/nemo-itn-export
mkdir -p itn-output
docker run --rm -v "$PWD/itn-output:/output" freespeak-nemo-itn \
  --base-url https://YOUR-HOST/immutable-release-directory
```

Replace `--base-url` with the real HTTPS publishing directory before exporting.
For a quick first export add `--languages en`; by default the exporter attempts
en, zh, de, fr, es, ja, ru and pt. Compilation can consume substantial RAM/time;
these grammar sizes are not assumed to be small. Unsupported grammar behavior or
failed smoke tests fail the export rather than advertising a broken pack.

Upload the output directory **with paths preserved**, including `catalog.json`,
the language directories and license/provenance files. Use immutable URLs (e.g.
a Hugging Face commit revision). No upload is performed by the export script.
To distribute the result in the app, verify the published assets and update
the bundled `app/src/main/assets/itn/catalog.json` before building Android.
Two individual FAR files are downloaded instead of a tar archive to avoid an
archive extraction dependency in this path. Both size and SHA-256 are verified
before the pair becomes visible to the processor. Cancelled downloads restart.

## Format and runtime

Catalog schema: `format: 1`, `packs: [{language, revision, files}]`; each file has
`name`, `url`, `bytes`, `sha256`. Names must be `classify.far` and `verbalize.far`.
Each FAR is `sttable` containing a single `grammar` standard-arc vector FST with
byte labels. These are **FreeSpeak exports**, not arbitrary Sparrowhawk caches.
The exporter pins NeMo commit `ddadfb2a38d2bc6b8cc6232c4f915eb60f500688`
and Pynini `2.1.6.post1`. Transitive Python dependencies are resolved by pip;
retain the built image for identical rebuild environments.

Classification selects the lowest-cost path. The processor tries the original
tag order, then lazily permutes nested fields (respecting `preserve_order` and
keeping top-level tokens fixed), bounded to 729 candidates. It does not run
Moses punctuation post-processing or lowercase the user's entire transcript.
NeMo's `cased` flag is not a guarantee of arbitrary punctuation/case support:
coverage and written conventions are language-specific. An unmatched or failed
rewrite leaves the original utterance intact. Inputs over 2048 UTF-16 code units
are left unchanged, avoiding semantic changes from arbitrary chunking. Each
composition is bounded by states/arcs and a materialization deadline; this is
not a hard real-time deadline for OpenFST shortest-path execution.

Automatic language selection uses the model's actual output/translation language.
When it is unavailable, select a formatting language explicitly. No language is
guessed from shared scripts; automatic code-switch span detection is not provided.
Chinese ITN runs before OpenCC. Traditional Chinese input is not silently
simplified just for ITN, so some Traditional expressions may remain unconverted.
The global toggle is opt-in and applies even to models with native formatting:
full ITN includes more than digits. One language is loaded at a time and graphs
are released when the ASR engine is unloaded.

## Validation

The Docker build runs real Pynini integration tests against the shared FAR export
function (archive round-trip, applying the exported graph, and Unicode). These
checks run before language compilation to catch API/serialization errors early.
They are skipped by local Python test discovery when Pynini is not installed.

The exporter round-trips each FAR and checks a `123` example against upstream
NeMo. It emits `smoke.json` for device testing. Build/unit tests:

```sh
./gradlew testDebugUnitTest assembleDebug
```

Before publishing, run real-device comparisons against upstream NeMo on full
sentences: cardinals, decimals, dates, currency, time, ordinals, already formatted
digits, punctuation, quotes, emoji, and mixed language. Verify unchanged text on
missing packs/errors and repeated load/unload/delete cycles. A compiled Android
library alone does not validate real grammar outputs or mobile performance.

An explicit CMake `itn_runtime_test` target is also available in
`engines/nemo-itn-jni/src/main/cpp`. Build it from the Gradle-generated ARM64
CMake build directory and run it on Android with a writable temporary directory
as its only argument. It checks classification, verbalization, unmatched input,
Unicode byte labels (including emoji and supplementary Han), limits and FAR
loading with **synthetic graphs**. It is excluded from default builds and APKs.
Real NeMo pack testing is still required in addition to these runtime checks.
