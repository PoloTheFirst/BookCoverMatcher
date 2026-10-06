# BookCover Matcher — v2.0.0

A native Android app (Kotlin · Jetpack Compose · CameraX) that scans a book cover with the phone
camera and finds it inside an Excel spreadsheet by **image similarity**.

No accounts. No server. **No internet permission at all** — everything runs on the device.

> v2.0.0 replaces the v1 WebView app. The Excel workflow is unchanged, the 64-bit pHash matcher is
> replaced by **OpenCLIP embeddings + an exact inner-product (FAISS-style) index**, all online
> searching has been removed, and the UI (especially Auto Scan) has been reworked.
> See [CHANGELOG.md](CHANGELOG.md).

---

## Table of contents

- [What it does](#what-it-does)
- [Honest status of this release](#honest-status-of-this-release)
- [Quick start](#quick-start)
- [How to use](#how-to-use)
- [The Excel workflow (unchanged)](#the-excel-workflow-unchanged)
- [Why OpenCLIP + FAISS](#why-openclip--faiss)
- [Auto Scan](#auto-scan)
- [Reading a match](#reading-a-match)
- [Configuration](#configuration)
- [Project layout](#project-layout)
- [Desktop tools](#desktop-tools)
- [Tests](#tests)
- [Limitations](#limitations)
- [Troubleshooting](#troubleshooting)
- [Security and privacy](#security-and-privacy)

---

## What it does

1. **Scan a book cover** with the camera, or pick a photo from the device.
2. The app turns the cover into a **512-number embedding** with an OpenCLIP image encoder
   (ONNX Runtime, on-device).
3. You pick an **`.xlsx` / `.xlsm`** workbook and a **cell range** such as `A1:B20`.
4. The app extracts the pictures embedded in that range (see
   [The Excel workflow](#the-excel-workflow-unchanged)), embeds each one, and ranks them by cosine
   similarity against your scan.
5. It shows the **top 5 matches** with cell address, thumbnail, similarity percentage and raw cosine.
6. You can save the scan and its matches to a **local wishlist**.

Embeddings of a workbook are cached on disk, so the second search against the same workbook and
range is nearly instant.

---

## Honest status of this release

Read this before you build.

| Area | Status |
| :--- | :--- |
| Excel extraction, Auto Scan engine, search index, wishlist storage, preprocessing (`core/` package, plain Kotlin) | **Compiled and unit-tested** — 67 tests pass. The Excel port was differential-tested against the original `excel.js` on 102 generated workbooks with identical results. |
| Android layer: CameraX, ONNX Runtime wrapper, ViewModel, Jetpack Compose UI, Gradle build | **Written without a compiler in the authoring environment** (no access to Google Maven / Maven Central) and without an independent code review. The project was then moved to AGP 9.4.1 / Gradle 9.8.0 and built by the maintainer; any fixes needed for that are part of the repository. |
| OpenCLIP accuracy | **Not measured on real weights or real covers.** The model hub was unreachable from the build environment. The case for OpenCLIP below is reasoned plus a synthetic baseline; use `tools/benchmark.py` on your own catalogue to confirm it. |
| int8 quantisation drift | **Unmeasured.** The export script measures it for you and refuses to write a model that drifted too far (see below). |
| Match thresholds (High ≥ 70 %, Mid ≥ 45 %) | **Heuristic starting points**, not validated. |

---

## Quick start

### 1. Requirements

- Android Studio recent enough to support **Android Gradle Plugin 9.4.1** (the project builds with **Gradle 9.8.0**), and **JDK 17 or newer** (the minimum required by AGP 9 / Gradle 9)
- A phone with Android 8.0+ (API 26+); 64-bit ARM recommended (the emulator uses x86_64)
- Python 3.10+ for the one-off model export

### 2. Create the model file (once)

The OpenCLIP image encoder is **not** included in this repository (it is ~100 MB).

```bash
pip install -r tools/requirements.txt
python tools/export_openclip_onnx.py --check-images path/to/some/covers/
```

This downloads OpenCLIP **ViT-B-32 / laion2b_s34b_b79k**, exports the image tower to ONNX
(~97 MB after int8 quantisation), verifies it against PyTorch, and checks the int8 model against
fp32 on your sample covers. It writes `app/src/main/assets/models/openclip_image.onnx`.

Useful variants:

```bash
python tools/export_openclip_onnx.py --precision fp32          # ~350 MB, exact
python tools/export_openclip_onnx.py --model ViT-B-16 --pretrained laion2b_s34b_b88k
python tools/export_openclip_onnx.py --out ~/openclip_image.onnx   # don't bundle; import in the app
```

### 3. Build and install

```bash
./gradlew assembleDebug          # Gradle 9.8.0 via the wrapper; app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # with a phone/emulator attached
./gradlew testDebugUnitTest      # unit tests
```

Or open the project folder in Android Studio and press **Run**.

### 4. Model on the device

- **Bundled** (step 2 default): the app copies the model out of the APK on first launch.
- **Imported**: if the model was not bundled, the app shows a setup screen with **Import model file**;
  copy the `.onnx` to the phone and pick it. It is stored privately and reused afterwards.

> The `.gitignore` excludes `*.onnx`. A bundled model makes the APK ~100 MB larger.

---

## How to use

1. Open the app and allow the camera.
2. Frame the cover inside the dashed guide and tap **Scan Book Cover** (or switch on the **Auto-scan** toggle next to it).
3. Under **1 · Spreadsheet & range** tap **Choose file** (`.xlsx` / `.xlsm`) and type a range such as `A1:B20`.
4. Tap **Link & Find Top 5**. Progress is shown (*Reading spreadsheet…*, *Embedding images… i/N*,
   *Ranking…*) and can be cancelled.
5. Inspect the ranked matches.
6. Tap **Add to Wishlist** to save. A green prompt shows for 5 s with **Remove** (undo) and **✕**.
7. The ⭐ button in the header opens the wishlist: rename (pen), expand to see the top 5 (arrow),
   delete (red ✕, with a confirmation dialog).

You can also use **Choose image** ("…or use an image from your device") to load a photo instead of using the camera.
Tap the camera preview to focus.

---

## The Excel workflow (unchanged)

As requested, the workflow is the one from v1 and was ported exactly, not redesigned:

- The workbook is read as a ZIP package; no spreadsheet library is involved.
- Cell-note pictures are read from `xl/drawings/vmlDrawing*.vml` (case-insensitive, visited in the
  ZIP's central-directory order). Each `<v:shape>` gives its cell via `<x:Row>` / `<x:Column>`;
  the picture relation id is looked up in this order: `v:fill o:relid`, `v:imagedata r:id`,
  `v:fill r:id`, `v:imagedata o:relid`; relationships come from
  `xl/drawings/_rels/<vml>.rels`.
- The range is applied to each picture's cell; the range text accepts `A1:B20`, any case, `$` allowed.
- Duplicate cell addresses across sheets collapse to the first one, exactly as in v1.
- Hard cap of 300 extracted images per run.

**One thing to check.** You described the workflow as extracting *only images embedded in cell
notes*. The v1 code also read **floating (anchored) pictures of the first sheet**, and because the
port is meant to be identical, v2 keeps doing so. If you want cell notes only, set

```kotlin
// app/src/main/java/com/bookcovermatcher/core/Config.kt
const val EXTRACT_FLOATING_IMAGES = false
```

---

## Why OpenCLIP + FAISS

**The problem with v1.** A 64-bit pHash reduces a cover to the low-frequency pattern of a 32×32
grey image. That works for near-identical files, but a phone photo differs from a catalogue image
in perspective, glare, white balance and cropping, and many covers share the same big-colour-block
layout. Colour is discarded entirely, and the score lives on a coarse 0–64 scale.

**What changes.** An OpenCLIP encoder maps each picture to a 512-dimensional vector that describes
its content (artwork, colours, large lettering) and is trained to be robust to exactly the kind of
variation a phone introduces. Matching is then a cosine similarity (inner product of unit vectors)
over all the workbook's vectors.

**What was measured, and what was not.**

- Measured here: on 90 simulated phone photos of a synthetic catalogue (perspective, rotation,
  lighting, glare, blur, noise, JPEG), the v1 pHash pipeline reached **top-1 ≈ 44 %, top-5 ≈ 74 %**.
  That is consistent with the unreliability you described, but it is a synthetic test.
- **Not measured here:** OpenCLIP on real weights. Treat "OpenCLIP is more accurate" as a
  well-grounded expectation, not a result. `tools/benchmark.py` runs both engines on your own
  catalogue and prints top-1 / top-5 / MRR so you can check; it also prints the score distributions
  used to tune the High/Mid/Low thresholds.

**FAISS on Android.** There is no official FAISS build for Android. The app uses `FlatIpIndex`,
a Kotlin implementation of exactly what `faiss.IndexFlatIP` does (exact brute-force inner product
over L2-normalised vectors); results were checked against real `faiss` for identical ranking.
For a spreadsheet of hundreds of covers exact search is instantaneous, and an approximate index
(HNSW/IVF) would only add recall loss. The benchmark script uses real `faiss.IndexFlatIP`.

**Pipeline details.**

- Preprocessing matches Pillow's bicubic anti-aliased resize to 224×224 (whole image, not
  centre-cropped, so title and author at the edges are kept), then CLIP mean/std normalisation.
- Transparent pictures are flattened onto white.
- Cover embeddings are cached in `cacheDir/index`, keyed by workbook SHA-256 + range + model
  fingerprint + floating-image flag (8 workbooks kept, least-recently-used).

---

## Auto Scan

The layout is the same as v1 (**Scan Book Cover** button + **Auto-scan** toggle + restart button beneath the camera card). What is new:

- A phase badge in the camera card: *Hold still…*, *Too dark*, *Too bright*, *Stabilizing… n/3*,
  *Same cover — waiting*, *Captured ✓*.
- The guide and corner brackets change colour with the phase, a scan line sweeps while Auto is on,
  three pips fill as the frame steadies, and **Sharp / Light / Steady** chips show which condition
  is failing.
- Capture flash and haptic tick on success.
- A single shaky frame no longer resets the stability streak (`graceFrames = 1`).
- **Bug fix:** in v1 the saved "Auto on" preference showed *on* after a restart but never armed the
  timer. In v2 it arms immediately.

Decision logic is unchanged and ported 1:1 (400 ms interval; 3 steady frames; stable distance 4;
duplicate distance 8; 1.5 s cooldown; blur ≥ 40; brightness 45–215; reset after 5 bad frames).
Auto Scan uses a small perceptual signature only to detect "steady" and "same cover as last time";
**matching** always uses the OpenCLIP embedding.

---

## Reading a match

- **Similarity %** — relative to *your workbook*. Raw cosine values depend on the model, so the app
  computes the median cosine between different pictures in the workbook (the "baseline") and shows
  `(cos − baseline) / (1 − baseline)`, clamped to 0–100 %. Unrelated covers sit near 0 %, a
  perfect match at 100 %.
- **Cosine** — the raw score, shown as small text, for tuning.
- **Match level** (colour of the bar):
  - **High (green)** ≥ 70 %
  - **Mid (accent)** ≥ 45 %
  - **Low (warn)** otherwise — treat as a weak suggestion.
  These cut-offs are starting points; adjust `ScoreScale` after running the benchmark.
- **Source** — `floating` for anchored pictures, `comment-fill` for pictures stored in cell notes.

With very small ranges (fewer than 3 pictures) a default baseline of 0.5 is used.

---

## Configuration

| Setting | Default | Where | Purpose |
| :--- | :--- | :--- | :--- |
| `GUIDE_W` / `GUIDE_H` | `0.70` / `0.78` | `core/Config.kt` | Guide rectangle as a fraction of the camera view (the Compose guide uses the same values). |
| `THUMB_SCAN_W/H` | `112 × 152` | `Config.kt` | Scan thumbnail (logical px). |
| `THUMB_MATCH_W/H` | `88 × 118` | `Config.kt` | Match thumbnail (logical px). |
| `THUMB_PIXEL_SCALE` | `2` | `Config.kt` | **New.** Thumbnails are rendered at 2× so they stay sharp. |
| `MAX_IMAGE_DECODE` | `800` | `Config.kt` | Max long edge when decoding a user photo. |
| `MAX_BUFFER_DECODE` | `640` | `Config.kt` | Max long edge when decoding a workbook picture. |
| `MAX_EXTRACTED_IMAGES` | `300` | `Config.kt` | Hard cap on pictures per run. |
| `TOP_MATCHES` | `5` | `Config.kt` | Results shown. |
| `WISHLIST_PROMPT_MS` | `5000` | `Config.kt` | Green prompt duration. |
| `DEFAULT_RANGE` | `A1:B20` | `Config.kt` | Initial range text. |
| `EXTRACT_FLOATING_IMAGES` | `true` | `Config.kt` | **New.** `false` = cell-note pictures only. |
| `AutoScanConfig.*` | see above | `core/autoscan/AutoScan.kt` | Auto Scan thresholds; `graceFrames` is new. |
| `ScoreScale.HIGH_PERCENT` / `MID_PERCENT` | `70` / `45` | `core/search/CatalogIndex.kt` | Match-level thresholds. |

Removed from v1: `OPENCV_URL`, `EXCELJS_URL`, `JSZIP_URL`, `WISHLIST_STORAGE_KEY`.

---

## Project layout

```
BookCoverMatcher/
├─ app/src/main/java/com/bookcovermatcher/
│  ├─ core/                   plain Kotlin/JVM, no android.* imports (unit-tested)
│  │  ├─ Config.kt
│  │  ├─ excel/               CellRange, ImageMime, ZipSource, WorkbookImageExtractor   (v1 excel.js)
│  │  ├─ vision/              ImageOps, ClipPreprocessor, PixelImage (+ codec/embedder interfaces)
│  │  ├─ search/              FlatIpIndex, CatalogIndex (+ScoreScale), CatalogBuilder, IndexCache
│  │  ├─ autoscan/            AutoScan engine, ScanBadge texts                         (v1 auto-scan.js)
│  │  ├─ wishlist/            MiniJson, WishlistRepository                             (v1 wishlist.js)
│  │  └─ format/              Formatting
│  ├─ data/                   ModelStore, Prefs, WorkbookFiles
│  ├─ vision/                 AndroidImageCodec (Bitmap + EXIF), OrtClipEmbedder (ONNX Runtime)
│  ├─ camera/                 CameraController (CameraX preview, guide crop, Auto Scan frames)
│  ├─ ui/                     MainScreen, CameraCard, WishlistOverlay, Overlays, SetupScreen,
│  │                          AppScreen, components/Common, theme/Theme
│  ├─ MainViewModel.kt, UiState.kt, MainActivity.kt
│  └─ (res/ manifest, launcher icon, theme)
├─ app/src/test/              67 JVM unit tests
├─ tools/                     export_openclip_onnx.py, benchmark.py, make_sample_workbook.py, synthetic.py
├─ gradle/libs.versions.toml  dependency versions (AGP 9.4.1)
├─ README.md   CHANGELOG.md
```

| v1 (JavaScript) | v2 (Kotlin) |
| :--- | :--- |
| `config.js` | `core/Config.kt` |
| `excel.js`, `libraries.js` (ExcelJS/JSZip) | `core/excel/*` (plain ZIP + regex, no libraries) |
| `phash.js`, `matcher.js`, `image.js` | `core/vision/*`, `core/search/*`, `vision/*` |
| `camera.js` | `camera/CameraController.kt` |
| `auto-scan.js` | `core/autoscan/AutoScan.kt` + UI in `ui/CameraCard.kt` |
| `wishlist.js` | `core/wishlist/*` + `ui/WishlistOverlay.kt`, `ui/Overlays.kt` |
| `ui.js`, `dom.js`, `state.js`, `utils.js`, `main.js`, `index.html`, `app.css` | `MainViewModel`, `UiState`, `ui/*` |
| `online.js` | **removed** |

---

## Desktop tools

All in `tools/`; none are needed to run the app.

```bash
pip install -r tools/requirements.txt

# 1. Export the model (see Quick start)
python tools/export_openclip_onnx.py --check-images covers/

# 2. A sample workbook with covers stored as cell-note picture fills, plus simulated phone photos
python tools/make_sample_workbook.py --count 24 --series 3 --out sample_catalog.xlsx --covers-dir sample_covers

# 3. Compare engines on YOUR catalogue
python tools/benchmark.py --xlsx catalog.xlsx --range A1:B40 \
       --onnx app/src/main/assets/models/openclip_image.onnx --trials 5
```

`benchmark.py` simulates phone photos of every cover, runs the v1 pHash pipeline and OpenCLIP
(through `faiss.IndexFlatIP`), and reports top-1 / top-5 / MRR, speed, and the genuine-vs-impostor
score distributions that `ScoreScale` should be tuned against. `--series N` generates look-alike
volumes, which is the hardest case for any global image embedding.

The sample workbook was read back with ExcelJS, openpyxl and the app's extractor; it has not been
opened in desktop Excel.

---

## Tests

```bash
./gradlew testDebugUnitTest
```

67 tests cover: cell-range parsing, MIME sniffing, VML/relationship resolution and ordering,
floating-vs-note precedence and the 300-image cap, FlatIpIndex ranking, score scaling, Pillow-parity
resizing, Auto Scan state transitions and timing, guide geometry, JSON round-trips, the wishlist
repository, and the catalogue builder with its disk cache. They run on a plain JVM and do not need
an emulator.

---

## Limitations

- **Series covers.** Volumes of one series with the same layout can embed very close to each other.
  Expect the right title in the top 5 more often than at rank 1. This is inherent to global image
  embeddings.
- **Pictures in the range only.** Anything that is not an embedded picture (text-only cells, linked
  pictures, pictures in later sheets' drawings) is invisible to the search.
- **Duplicate addresses across sheets** collapse to the first (v1 behaviour, kept).
- **First embedding is slower.** A workbook of 300 pictures needs 300 model runs the first time
  (seconds to tens of seconds depending on the phone); later searches use the cache.
- **Rotation / perspective.** Embeddings tolerate moderate tilt, but keep the cover roughly upright
  and flat in the guide.
- **Dark, over-bright or blurred shots** reduce quality; Auto Scan refuses them.
- **Model size.** The bundled int8 model is ~97 MB; fp32 is ~350 MB.
- **Percent and levels are workbook-relative heuristics**, not probabilities.
- Wishlist data is stored privately on the device. Clearing app data clears it. No cloud sync.

---

## Troubleshooting

| Symptom | Likely cause |
| :--- | :--- |
| Setup screen "Set up the image model" appears | No `openclip_image.onnx` in the APK assets and none imported. Run the export script, then rebuild, or tap **Import model file**. |
| "Model failed to load" | Wrong or corrupt file, or exported with a different input layout. Re-export with `tools/export_openclip_onnx.py`; input must be `[1,3,S,S]`. |
| Camera does not start | `CAMERA` permission denied (enable in system settings) or another app is using the camera. Tap the restart-camera button. |
| "No images found inside …" | The range has no embedded pictures. Check the cells hold **cell-note picture fills** (see the sample workbook), and that the range covers them. |
| Matches look random | Check that the workbook pictures are real covers, not placeholders; run `tools/benchmark.py` to see how the model separates your catalogue. |
| Everything is "Low" | The thresholds are workbook-relative heuristics; tune `ScoreScale` using the benchmark's distributions. |
| Two sheets have a picture in `B4` and one is missing | Duplicate-address collapsing (v1 behaviour). |
| Gradle sync fails resolving dependencies | Needs access to `google()` and `mavenCentral()`. Versions are in `gradle/libs.versions.toml` (AGP 9.4.1, Gradle 9.8.0 via `gradle/wrapper/gradle-wrapper.properties`). |
| Build error about the JDK | AGP 9 / Gradle 9 need JDK 17 or newer. In Android Studio: Settings → Build Tools → Gradle → Gradle JDK. |
| Compile error in `ui/`, `camera/` or `MainViewModel.kt` | See [Honest status](#honest-status-of-this-release): this layer was first written without a compiler. |

---

## Security and privacy

- **No `INTERNET` permission** is declared; the app cannot make network requests.
- The camera is the only permission. Photos picked from the gallery and the workbook are read
  through the system file picker; nothing leaves the device.
- Models, caches, thumbnails and the wishlist live in the app's private storage
  (`allowBackup=false`).
- The only network use is on your computer, when you run the export script (it downloads the
  OpenCLIP weights from the OpenCLIP hub) and `pip install`.

---

## Credits

- [OpenCLIP](https://github.com/mlfoundations/open_clip) (ViT-B-32, LAION-2B weights)
- [ONNX Runtime](https://onnxruntime.ai/) for on-device inference
- [FAISS](https://github.com/facebookresearch/faiss) (reference behaviour for the exact inner-product index)
- CameraX, Jetpack Compose, Kotlin coroutines
