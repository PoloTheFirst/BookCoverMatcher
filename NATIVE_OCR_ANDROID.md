# Native OCR on Android — integration notes (`v2.5.0-dev.5-crash-fix-8`)

This document describes how the verified Rust PP-OCRv6 pipeline (`native/ocr`) is wired into the
Android app, what has actually been proven on a device, and what is deliberately still missing.

**Shipped OCR is unchanged.** `chrome-lens-py` through Chaquopy remains the default engine. The
native engine is a second, user-selectable engine in Online → READ THE COVER; neither engine replaces
or silently stands in for the other.

## 1 · Layers

```
OnlineSearchController
   └── OcrTextSource                      (existing contract: availability() + recognise())
         ├── SwitchingOcrTextSource       reads the engine preference on every call
         │     ├── ChromeLensPyBridge     the shipped engine (Chaquopy → lens_bridge.py)
         │     └── RustOcrTextSource      the native engine
         │           ├── PpocrModels      model files + pinned SHA-256 (extracted from the APK's assets)
         │           ├── AndroidImageCodec.decodePhoto → PixelImage (ARGB ints)
         │           └── NativeOcrLib     JNI: version/create/describe/recognise/destroy
         └── libbc_ocr.so → OcrEngine (detector + recogniser sessions, ONNX Runtime 1.20.0)
```

`OcrText` is reused unchanged: `text`, `blockHeights` (normalised quad heights), `detectedLanguage`,
`translatedText`, `wordCount`, `blockCount`, `libraryVersion`. The native engine reports
`detectedLanguage = null` and `translatedText = null` rather than guessing: PP-OCRv6 is not
language-conditioned and there is no translation step.

## 2 · JNI design

| Kotlin (`NativeOcrLib`) | Rust (`src/jni.rs`) | Notes |
|---|---|---|
| `version(): String` | `…_version` | crate version, e.g. `0.1.0` |
| `create(det, rec, dict, runtime): Long` | `…_create` | opens both sessions; returns `> 0` or throws |
| `describe(handle): String` | `…_describe` | `BC-OCR-INFO 1` payload |
| `recognise(handle, argb, w, h): String` | `…_recognise` | `BC-OCR-RESULT 1` payload |
| `destroy(handle)` | `…_destroy` | idempotent |

* **Handles, not pointers.** Kotlin holds an `i64` id; `src/registry.rs` maps it to an `Arc`. A stale
  or forged id is an error, and an in-flight call keeps the engine alive while `destroy` runs.
* **No panic escapes.** Every entry point runs inside `catch_unwind`; a panic becomes
  `IllegalStateException`, because unwinding into ART is undefined behaviour.
* **Bounded allocation.** `1…8192` per side and ≤ 16.7 Mpx, checked before any buffer exists.
* **Pixels, not files.** Kotlin decodes with `BitmapFactory` (EXIF-aware, the same input the Python
  path uses) and passes an `IntArray` of ARGB; Rust converts to RGBA. One copy, no shared memory, no
  image codec in the crate.
* **One ONNX Runtime.** `libbc_ocr.so` is built with `load-dynamic` and `api-20`, so it links no
  runtime. The Kotlin factory loads Maven's `libonnxruntime.so` and passes an **empty** runtime path:
  Android refuses a native `dlopen` by absolute path inside the app's linker namespace, but a soname
  lookup reuses the mapping that is already loaded.

### Wire format (versioned)

```
BC-OCR-RESULT 1
REGIONS<TAB>3
LINE<TAB>1<TAB>0.991234<TAB>0<TAB>x0,y0,x1,y1,x2,y2,x3,y3<TAB>THE HOBBIT
ERROR<TAB>…            (optional, one per region that failed)
END
```

Tab-separated, `\`-escaped text, `END`-terminated, header-versioned. Parsed by pure Kotlin
(`NativeOcrProtocol`), so the parser is unit-tested on a desktop JVM — including a test that a cover
printing a tab cannot forge an extra field or a fake `END`.

## 3 · Threading, lifecycle and cancellation

* `recognise` runs on `Dispatchers.IO` in the controller, never on the UI thread.
* The adapter serializes all native calls on one lock; Rust holds the engine behind a `Mutex`. A
  second request waits instead of corrupting a session (ONNX Runtime forbids concurrent `run`).
* The engine opens lazily on the first recognition and is reused; `close()` destroys the handle.
  Re-opening after `close()` is supported (verified by the repeated-initialisation device test).
* **Cancellation is cooperative.** ONNX Runtime's C API cannot abort a `run` in flight, so cancelling
  the coroutine does not stop inference: the result is discarded by the controller's session token.
  The timeout argument is accepted for contract compatibility and ignored — a timeout that cannot
  interrupt the native call would only misreport what happened.

## 4 · Error mapping

| Situation | `OcrFailure` | Message |
|---|---|---|
| `libbc_ocr.so` absent / unsupported ABI | `OCR_ENGINE_MISSING` | "Native OCR not in this build" |
| models absent or hash mismatch | `OCR_MODELS_MISSING` | "OCR model not installed" |
| engine could not open its sessions | `RUNTIME_MISSING` | runtime message |
| image could not be decoded | `BAD_IMAGE` | "Could not read that picture" |
| inference or payload failure | `INTERNAL` | "Could not read the cover" |

`OcrErrorMessages` keeps exactly one sentence per category and its `when` is exhaustive, so a new
category cannot exist without user-facing wording. There is **no automatic fallback**: selecting the
native engine without it present produces a native error, not a silent switch.

## 5 · Model distribution — bundled, verified, extracted on first use

The detector and recogniser total **132.2 MB** (138,662,763 bytes). They are **packaged in every
APK**; a normal user never runs a script, never pushes files and never downloads anything.

| Asset | Size | SHA-256 |
|---|---|---|
| `det.onnx` (PP-OCRv6 medium det) | 62,032,837 | `eb13b44b25bb36f89528b68720af8a61d9cf381176107f465db1757b65d086e1` |
| `rec.onnx` (PP-OCRv6 medium rec) | 76,554,979 | `9c09abf0957f7968c7586464b7397b84ad2387a0497a351af40e9acc71b673ba` |
| `ppocrv6_dict.txt` (18,708 entries) | 74,947 | `b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d` |

Upstream revisions: `PaddlePaddle/PP-OCRv6_medium_det_onnx@61323801669c338b7891481ec7bac61ce31b576a`
and `PaddlePaddle/PP-OCRv6_medium_rec_onnx@50c7eacafc52fa7bcf4194e8cd08e46f8558504b` (Apache-2.0); the
dictionary is the copy already tracked at `native/ocr/assets/ppocrv6_dict.txt`.

**Build time.** The Gradle task `preparePaddleOcrAssets` runs before `preBuild`:

* fetches `det.onnx` / `rec.onnx` from the pinned revisions above into
  `app/src/main/assets/ocr/ppocrv6/` (git-ignored) and copies the dictionary from `native/ocr/assets`;
* verifies **size and SHA-256** of every file and **fails the build** if one is missing, truncated or
  different — an APK that cannot run PaddleOCR is never produced;
* reuses a verified local copy, so only the first build (or a clean CI checkout) needs network access;
* `androidResources { noCompress += "onnx" }` keeps the weights *stored* (not deflated), so the models
  are readable straight from the APK and the packaging hash in the verifier matches the file.

**First use.** `PpocrModels.ensureInstalled { context.assets.open("ocr/ppocrv6/$it") }` copies the
three assets into `files/ocr/ppocrv6/`, hashing as it copies, writing each file to `name.part` and
renaming only after the hash matches; a marker records the verification so later launches cost one
read. It is single-flight per process (`INSTALL_LOCK`) and retryable: an interrupted extraction leaves
only `.part` files, which the next attempt deletes. Any failure surfaces as `OCR_MODELS_MISSING` with
the reason — the stored selection is kept and nothing falls back to Chrome Lens.

**APK verification.** `tools/verify-ocr-apk.ps1` reads the *built archive*, extracts each model asset,
re-computes its hash, and checks the native libraries per ABI. See §7 for the reported numbers.

**Size and channels.** The 139 MB of weights are intentional and are not traded away for a smaller
model, a user-managed directory or a post-install download. A universal debug APK is now ~320 MB.
Google Play's base-module limit is 200 MB; this project ships sideloaded APKs (`INSTALL.md`), where no
such limit applies. If it ever needed Play distribution, the compatible mechanism is an install-time
Play Asset Delivery pack for the models (still no user action) — not removing them from the app.


## 6 · Engine selection

* The Online panel shows an **OCR engine** selector under the feature switch: `Chrome Lens` (default)
  and `PaddleOCR (PP-OCRv6 Medium)`. It is part of the READ THE COVER section, not a hidden switch.
* `OnlineSearchPrefs.ocrEngine` persists the choice: `"python"` (kept for upgrades, means Chrome
  Lens) or `"rust"` (PaddleOCR). `OcrEngineChoice` resolves aliases (`paddle`, `paddleocr`, …) and
  treats an unknown value as Chrome Lens without rewriting it; only an explicit selection writes.
* `SwitchingOcrTextSource` reads the preference on each call and closes the previous engine when the
  selection changes, so a switch does not leave two engines resident.
* Selection and availability are separate: the chips show what is selected, the line below shows
  whether that engine can run (`PaddleOCR unavailable — <reason>` when the library or models are
  missing or invalid). The engine that actually ran is recorded per request in the UI result and in
  the log (`adb logcat -s OnlineSearch` → `ocr engine request/result id=…`).

## 7 · What was verified, and where

| Level | Claim | Evidence |
|---|---|---|
| 1 | Native library built for every shipped ABI | arm64-v8a 592,904 B (ELF AArch64), x86_64 653,352 B (ELF x86-64) |
| 2 | JNI works on Android | `bc_ocr load: ok, version=0.1.0, abi=x86_64`; unknown-handle and missing-file errors observed |
| 3 | Kotlin adapter implements `OcrTextSource` | on device: `engine=python → Ready(chrome-lens-py 3.8.2)`, `engine=rust → Ready(bc_ocr (PP-OCRv6 medium) 0.1.0)` |
| 4 | Models are **inside the built APK**, hash-correct | `tools/verify-ocr-apk.ps1`: `det.onnx` 62,032,837 B and `rec.onnx` 76,554,979 B **stored** + `ppocrv6_dict.txt` 74,947 B, all three PASS on extracted SHA-256 (138,662,763 B total); `lib/{arm64-v8a,x86_64}/libbc_ocr.so` present, exactly one `libonnxruntime.so` per ABI; debug APK 336,203,213 B, release 331,118,159 B |
| 5 | A clean install extracts the models from the APK and reads offline | emulator, app uninstalled first: `packaged assets: [det.onnx, ppocrv6_dict.txt, rec.onnx]`, then `clean install: extracted + recognised in 2470 ms, 10 blocks`, `models: ready (138662763 bytes)` |
| 6 | **Real OCR on a physical Android device** | **NOT DONE** — no physical device was attached |

Real inference was exercised on the emulator `Medium_Phone_API_37.0` (Android 17, x86_64, 4 GB RAM):
10 lines read from the bundled Hobbit cover in **1,991 ms cold / 967 ms warm**, against Python's
1,226 ms for 4 coarser blocks on the same image.

## 8 · Known limitations

1. **No physical-device run.** ARM latency, memory and thermal behaviour are unmeasured; the numbers
   above are emulator numbers and must not be quoted as phone performance.
2. **First-use extraction costs time.** A clean install copies and hashes 139 MB before the first
   PaddleOCR result; it is bounded, retryable and does not block other features, but it is not free.
3. **No language detection or translation** on the native path.
4. **Inference cannot be interrupted** (§3); cancellation discards results.
5. **No per-language accuracy claim.** Host-side synthetic crops cover en/ja/zh-Hant/zh-Hans, but no
   reference transcriptions exist for real scans, so no CER is reported.
6. **Python retirement is out of scope.** Chaquopy, `chrome-lens-py` and `lens_bridge.py` stay until a
   separately authorised task removes them.
7. **A universal APK only.** Both ABIs and the models ship in one ~320 MB file; ABI splits and
   AAB/asset-pack delivery are not built, and Play's 200 MB base limit would need the latter.

## 9 · Reproducing the device run

```powershell
powershell -ExecutionPolicy Bypass -File tools/build-native-ocr.ps1      # both ABIs → jniLibs
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
powershell -ExecutionPolicy Bypass -File tools/verify-ocr-apk.ps1
adb -s <serial> uninstall com.bookcovermatcher
adb -s <serial> install -r app\build\outputs\apk\debug\app-debug.apk
adb -s <serial> install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb -s <serial> shell am instrument -w -e class com.bookcovermatcher.ocr.NativeOcrDeviceTest `
    com.bookcovermatcher.test/androidx.test.runner.AndroidJUnitRunner
adb -s <serial> logcat -d -s NativeOcrTest:I
```

`connectedDebugAndroidTest` runs these suites too. There is no model-push step any more: the APK
carries the models, and `NativeOcrDeviceTest.aCleanInstallPreparesPaddleOcrFromTheApkAndReadsOffline`
deletes the private copy first, so the extraction path is what gets exercised.

## 10 · Accuracy: what was measured (crash-fix-9)

The reported symptom was poor recognition on illustrated Japanese covers: substitutions, stray
fragments, and no title. Investigating it needed a reproducible measurement, so
`tools/ocr-reference/` now holds one (see its `README.md` for commands, environment and the full
tables). Nothing in it is packaged in the APK and no third-party cover is committed.

### The harness

* 7 synthetic covers with hand-written ground truth (Japanese horizontal / vertical / rotated /
  scattered-decorative, Traditional Chinese, Simplified Chinese, English) generated deterministically
  by `make_fixtures.py`;
* an independent Python implementation of the **official PaddleOCR algorithm** over the same pinned
  ONNX files, with switches for one preprocessing variable at a time;
* `metrics.py` scoring CER / exact match / detection recall, and `native/ocr/examples/eval.rs` for the
  Rust side;
* stage bisectors (`rec_on_crops.py`, `probe_crop.py`, `probe_vertical.py`, `debug_rec.py`).

Parity was established where it matters most: feeding the Rust engine's own dumped crops to the Python
recogniser reproduced its text character for character on all 10 lines of the real cover, which pins
preprocessing, BGR order, resize/padding, the 18,710-class CTC mapping and the dictionary down as
correct. Detector geometry was compared the same way (same image, both pipelines).

### Root causes found

1. **Vertical columns were squeezed into a 12-pixel-wide canvas** (`ja_vertical` read one stray
   character). PP-OCR recognises horizontal lines; a region at least 1.5× taller than wide must be
   turned a quarter turn first. The direction is not arbitrary — measured on the real column crop:
   as-is 0.074 `试验`, clockwise 0.313 `修青小站`, **counter-clockwise 0.877 `縱書き小説`**, per-character
   cells exact `縦書き小説`. `engine::recognise_region` now rotates counter-clockwise
   (`RgbaImage::rotated_counter_clockwise`), and `ja_vertical` went from CER 1.000 to 0.000.
2. **The detector tensor was RGB** while PaddleOCR's `DecodeImage(img_mode='BGR')` + ImageNet stats
   expect BGR. Fixed for parity (`det::preprocess`); the measured effect on the fixture suite is
   ±1 character (0.161 BGR vs 0.149 RGB overall CER), so this is reported as a parity fix, not a win.
3. **Full-width digits reach the search query verbatim** (`じれったい２`). Searching is now NFKC-folded
   (`core/online/SearchText.kt`) while the raw recognised text stays untouched in the result, the log
   and `OcrText.text`.

Not defects: PaddleOCR's `min` resize policy scored the same as the engine's `max` (0.161 both), so the
engine keeps `max`; detector threshold/unclip parameters already match the model's `inference.yml`; the
dictionary and CTC mapping were already correct.

### Numbers (fake-clean fixtures, this machine)

| Fixture | lang | before | after | after text |
|---|---|---|---|---|
| `ja_horizontal` | ja | 0.000 | 0.000 | `じれったい、2ギルR` |
| `ja_vertical` | ja | 1.000 | **0.000** | `縦書き小説` |
| `ja_rotated` | ja | 0.091 | 0.091 | `じれったい２` |
| `ja_decorative` | ja | 0.000 | 0.200 | `じれつたい` |
| `zh_hant` | zh-Hant | 0.000 | 0.000 | `繁體中文測試封面` |
| `zh_hans` | zh-Hans | 0.000 | 0.000 | `简体中文测试封面` |
| `en` | en | 0.000 | 0.000 | `FINAL FANTASY SEVEN` / `Tifa Lockhart FAN BOOK` / `R-18` |
| **overall** | | 0.069 | **0.063** | |

Real-model host run (`cargo test --test real_models -- --ignored`, ONNX Runtime 1.20.0): 3/3 pass,
including `日本語のテキスト認識` and `吾輩は猫である 第1巻` read exactly from the multilingual crops, and the
Hobbit cover reading `THE AUTHORIZED EDITION` / `THE` / `HOBBIT` / `THE ENCHANTING PRELUDE TO` /
`"THE LORD OF THE RINGS"`.

### Blocked and unmeasured

* **The user's own cover was not supplied as a file.** The Tifa / FINAL FANTASY VII regression case is
  therefore **blocked**, not passed; its reported outputs stay user-reported baselines.
* **Chrome Lens was not run** on these fixtures (needs a device for the Python path), so there is no
  Lens-vs-PaddleOCR comparison, only the user's account.
* **`ja_decorative` regressed by one character** (`っ` → `つ`) after the BGR change; it is a model
  weakness on a small stroked kana over artwork, recorded rather than explained away.
* **No multi-scale passes, orientation search or batching were added.** The measurements did not
  justify them: detection recall was 1.00 on every fixture before and after, so extra passes would have
  bought latency, not text.
* **`ja_rotated` still returns a full-width digit** and one scattered title character is still wrong:
  model limitations on this domain, mitigated downstream by the search-side folding.

