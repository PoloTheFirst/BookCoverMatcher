# `bc_ocr` — native OCR core (v2.5.0-dev.5, Android integration verified on an emulator)

This crate is the native half of the migration described in
`historical_prompt/v2.5.0-dev.5.md`: PP-OCRv6 text detection + recognition on ONNX Runtime,
called from Kotlin through JNI, replacing the embedded-Python `chrome-lens-py` OCR.

**It is not the application's default OCR engine yet.** `chrome-lens-py` still ships and still runs;
the native engine is selected by a **debug-only** switch while the migration is unfinished. Nothing
in a release build offers it, and there is no automatic fallback in either direction.

## Android integration (added in `v2.5.0-dev.5-crash-fix-6`)

| Piece | Where |
|---|---|
| JNI entry points | `src/jni.rs` — `Java_com_bookcovermatcher_ocr_NativeOcrLib_{version,create,describe,recognise,destroy}` |
| Handle table | `src/registry.rs` — Kotlin holds an `i64` id, never a pointer; stale ids are errors |
| Wire format | `src/wire.rs` — `BC-OCR-RESULT 1` / `BC-OCR-INFO 1`, tab-separated, escaped |
| Kotlin wrapper | `app/src/main/java/com/bookcovermatcher/ocr/NativeOcr.kt` |
| Parser (pure Kotlin) | `…/ocr/NativeOcrProtocol.kt` |
| Adapter | `…/ocr/RustOcrTextSource.kt` (`OcrTextSource`) |
| Models | `…/ocr/PpocrModels.kt` — pinned SHA-256, atomic install |
| Engine selection | `…/ocr/OcrEngines.kt` — `SwitchingOcrTextSource`, reads the preference per call |
| Build + packaging | `tools/build-native-ocr.ps1` → `app/src/main/jniLibs/<abi>/libbc_ocr.so` (git-ignored) |
| Model install | `tools/push-ocr-models.ps1` → app-internal `files/ocr-import/ppocrv6` |
| APK check | `tools/verify-ocr-apk.ps1` |
| Device tests | `app/src/androidTest/.../NativeOcrDeviceTest.kt` |

### Safety and lifecycle properties the integration guarantees

* **No pointer crosses JNI.** Kotlin holds an integer handle; `destroy` is idempotent; an unknown
  handle throws `IllegalStateException` naming it; an in-flight call keeps the engine alive because
  it holds an `Arc`.
* **No panic escapes.** Every entry point runs inside `catch_unwind` and converts a panic into a
  Java exception instead of unwinding into ART.
* **Bounded allocation.** Dimensions are validated (`1…8192` per side, ≤ 16.7 Mpx) before any buffer
  is allocated.
* **One environment, one runtime.** The crate has always allowed one ONNX Runtime environment per
  process; the Android factory resolves `libonnxruntime.so` **by soname** (loading Maven's copy first)
  because Android refuses a native `dlopen` by absolute path inside the app's linker namespace.
* **Serialized inference.** `Mutex<OcrEngine>` in the handle table matches the crate's `&mut self`
  contract.
* **Cancellation is cooperative and documented.** ONNX Runtime cannot abort a `run` in flight, so a
  cancelled coroutine discards the result; the engine stays consistent.

### Verified on an emulator (API 37, x86_64, 4 GB RAM) — not a physical device

| Check | Result |
|---|---|
| `libbc_ocr.so` packaged for arm64-v8a / x86_64 | 592,904 B / 653,352 B, one `libonnxruntime.so` per ABI |
| Library load + JNI resolution | `bc_ocr 0.1.0`, abi x86_64 |
| Model install (SHA-256 pinned) | **ready, 138,662,763 bytes** |
| `describe()` | `CLASSES 18710`, `DICT 18708`, `SPACE 1`, `DET x[?,3,?,?]`, `REC fetch_name_0[?,3,48,?]` |
| Cover OCR | 10 lines: `THE AUTHORIZED EDITION`, `THE`, `HOBBIT`, `THE ENCHANTING PRELUDE TO`, `"THE LORD OF THE RINGS"`, `RRTOLKIEN`, `BOUGFTON` + 3 tiny marks |
| Latency (emulator) | cold **1,991 ms** (session load included), warm **967 ms** |
| Engine selection | python → `Ready(chrome-lens-py 3.8.2)`, rust → `Ready(bc_ocr (PP-OCRv6 medium) 0.1.0)` |
| Python comparison (same cover) | Python 1,226 ms / 4 blocks (includes Lens network); native 2,534 ms cold / 10 blocks |
| Instrumentation | **9/9 tests pass** on `Medium_Phone_API_37.0` |

**Not verified:** a physical phone, ARM latency/memory, NNAPI/GPU, and per-language accuracy on real
scans (the host-side synthetic crop results in the section below are the only multilingual evidence).

## What is implemented and tested

| Module | Contents | State |
|---|---|---|
| `dict` | PP-OCR `CTCLabelDecode` index space (`blank = 0`, dictionary from 1, optional trailing space), greedy CTC decode with repeat collapsing and per-character confidence | done, 7 tests |
| `geom` | corner ordering with PaddleOCR's `get_mini_boxes` rule, quad size + vertical/horizontal classification, affine solve, bilinear perspective crop with edge clamping | done, 6 tests |
| `image` | `RgbaImage` validation, bilinear resize, clamped crop, quad crop | done, 4 tests |
| `det` | detector preprocessing (resize to 736 → pad to 32 → ImageNet mean/std → CHW) and DB post-processing (threshold, 8-connected components, mean-score filter, `unclip_ratio`, source mapping) | done, 7 tests |
| `rec` | recogniser preprocessing (BGR, height 48, width ≤ 320, right padding, `(v/255-0.5)/0.5`) and CTC decoding with an explicit class-count guard; detects the graph's own `Softmax` so a probability row is not softmaxed twice | done, 13 tests |
| `order` | reading order: horizontal bands left-to-right, vertical Japanese columns right-to-left then top-to-bottom | done, 6 tests |
| `engine` | process-wide ONNX Runtime environment (the runtime allows one per process), one detector + one recogniser session, model metadata, `detect()` and the full `recognise()` pipeline | done — verified with the real models |
| `tests/dictionary.rs` | pins the real 18,708-entry vocabulary: counts, ordering, index→character mappings, blank/space classes, CTC repeats, refusals | done, 10 tests |

```
cargo test          # 43 unit + 10 dictionary tests, host — all pass
cargo test --test real_models -- --ignored      # 3 tests, needs the models + ONNX Runtime
```

Cross-compiled for Android (NDK r27d, API 26): `libbc_ocr.so` for arm64-v8a and x86_64.

## Real-model verification (host, ONNX Runtime 1.20.0, 2026-11-07)

```
$env:BC_OCR_MODELS  = "$env:TEMP\ppocrv6"                                  # det.onnx, rec.onnx
$env:BC_OCR_DICT    = "native\ocr\assets\ppocrv6_dict.txt"
$env:BC_OCR_IMAGE   = "app\src\androidTest\assets\cover.jpg"
$env:BC_OCR_CROPS   = "$env:TEMP\bc-ocr-crops"                             # tools/make-ocr-crops.py
$env:ORT_DYLIB_PATH = "$env:TEMP\ort120\onnxruntime-win-x64-1.20.0\lib\onnxruntime.dll"
cargo test --test real_models -- --ignored --nocapture
```

| Step | Result |
|---|---|
| Runtime resolution | **ok** — `ort::init()` loaded `onnxruntime.dll` 1.20.0 via `load-dynamic`; nothing linked |
| Detector load | **ok** — input `x[-1,3,-1,-1]`, output `fetch_name_0[-1,1,-1,-1]` (fully dynamic) |
| Recogniser load | **ok** — input `x[-1,3,48,-1]`, output `fetch_name_0[-1,-1,18710]`, `Softmax(axis=2)` |
| Detector inference | **ok** — 512×320 probability map in **100 ms**, 12,543 / 163,840 values above 0.2 |
| DB post-processing | **ok** — **11 regions**, scores 0.58–0.96, 2 correctly classified as vertical (Japanese columns) |
| Reading order | **ok** — `[0,1,3,5,6,7,8,9,10,2,4]`: horizontal lines first, then the columns right-to-left |
| Recogniser inference | **ok** — 18,710-class probabilities, finite |
| **Text decoding** | **ok** — real text, with realistic per-line confidences (see below) |

### Decoded text: the bundled Hobbit cover (301×500, 10 lines, 600 ms)

```
 1.  82.3%  8B                          6.  99.9%  THE ENCHANTING PRELUDE TO
 2.  98.9%  95c                         7.  99.1%  "THE LORD OF THE RINGS"
 3. 100.0%  THE AUTHORIZED EDITION      8. 100.0%  RRTOLKIEN
 4. 100.0%  THE                        9.  78.8%  BOUGFTON
 5.  99.6%  HOBBIT                     10.  27.8%  11   (vertical)
```

Lines 1–2 and 9–10 are the tiny publisher/price marks; `RRTOLKIEN` is `J.R.R. TOLKIEN` with the dots
and the `J` dropped, `BOUGFTON` is `HOUGHTON MIFFLIN` at ~7 px height. The title, the series line and
the author area are read correctly — the same words the Python/Chaquopy device test asserts.

### Multilingual smoke test (`tools/make-ocr-crops.py`, synthetic renders)

| Crop | Ground truth | Decoded | Score |
|---|---|---|---|
| `01_en.png` | THE HOBBIT | `THE HOBBIT` | 100.0 % |
| `02_ja.png` | 日本語のテキスト認識 | `日本語のテキスト認識` | 99.9 % |
| `03_ja_title.png` | 吾輩は猫である 第1巻 | `吾輩は猫である 第1巻` | 98.3 % |
| `04_zh_hant.png` | 繁體中文測試 | `繁體中文測試` | 100.0 % |
| `05_zh_hans.png` | 简体中文测试 | `简体中文测试` | 100.0 % |
| `06_mixed.png` | COMIC コミック 第12巻 | `COMIC コミック 第12巻` | 95.7 % |

These are **synthetic** single-line renders, so they prove script coverage, not scan accuracy. No CER
is claimed: that needs reference transcriptions of real scans, which the repository does not have.

## Dictionary reconciliation (resolved)

The mismatch that blocked `ae362fd` was **an extraction defect, not a missing upstream file**.

| Evidence | Value |
|---|---|
| Graph head | `MatMul(linear_8.w_0 [192, 18710]) + bias [18710]`, then `Softmax(axis = 2)` |
| Recogniser class layout | `0` = CTC blank, `1..=18,708` = dictionary in file order, `18,709` = `' '` |
| Model package `inference.yml` | 18,708 `character_dict` entries; entry 1748 = `U+3000`; last = `U+1F6C5` |
| Training config `PP-OCRv6_medium_rec.yml` (blob `d8149ed9…`, sha256 `5EBEE604…`) | `character_dict_path: ppocr/utils/dict/ppocrv6_dict.txt`, `use_space_char: true` |
| `ppocr/postprocess/rec_postprocess.py` | `["blank"] + dict_character` with `" "` appended when `use_space_char` |
| Committed dictionary (`assets/ppocrv6_dict.txt`, Apache-2.0) | 18,708 entries, blob `270082aa…`, sha256 `B5F2BFE2…`, 74,947 B |
| `inference.yml`-derived list (after fixing the extractor) | 18,708 entries — **identical** to the official file |

`18,708 + 1 blank + 1 space = 18,710`, exactly the graph's width, so nothing is truncated, padded or
explained away.

### Why the old extraction produced 18,704

Reproduced against the same `inference.yml` (see `tools/extract-ppocrv6-dict.py`):

1. `U+3000` (entry 1748) — the raw line is `- <U+3000>`; `String.Trim()`/`str.strip()` treat it as
   whitespace, leaving `-`, which no longer starts with `- ` and is skipped.
2. `U+303D`, `U+FF9E`, `U+FF9F` — PowerShell's culture-sensitive `StartsWith('- ')` returns **False**
   for values beginning with these collation-ignorable characters; `StringComparison::Ordinal` is
   required.

Four entries lost ⇒ 18,704, and every character after index 1748 shifted by four. That is precisely
the "plausible but wrong text" failure the guard in `rec::decode_output` exists to refuse.

## Building for Android

One script does the whole cross-compile and stages the output where Gradle picks it up:

```powershell
powershell -ExecutionPolicy Bypass -File tools/build-native-ocr.ps1
.\gradlew.bat assembleDebug
powershell -ExecutionPolicy Bypass -File tools/verify-ocr-apk.ps1
```

Requirements, all verified on this machine:

* Rust with the Android targets: `rustup target add aarch64-linux-android x86_64-linux-android`;
* Android NDK **r27d** (found through `BC_OCR_NDK`, `ANDROID_SDK_ROOT`/`ANDROID_HOME`,
  `local.properties`, or `%LOCALAPPDATA%\Android\Sdk` — in that order);
* no `cargo-ndk`: the NDK's own `clang.exe` is enough, and the script passes
  `--target=<triple>26` so the `.so` targets `minSdk 26` like the app.

Measured output (crate with `ort` + `jni`):

| ABI | File | Size | ELF machine |
|---|---|---|---|
| arm64-v8a | `app/src/main/jniLibs/arm64-v8a/libbc_ocr.so` | 592,904 B | AArch64 |
| x86_64 | `app/src/main/jniLibs/x86_64/libbc_ocr.so` | 653,352 B | x86-64 |

Those `.so` files are build outputs and are git-ignored; Gradle packages them through the standard
`jniLibs` mechanism. `tools/verify-ocr-apk.ps1` reads the built APK and asserts that both ABIs carry
`libbc_ocr.so`, that there is exactly one `libonnxruntime.so` per ABI, and that no PP-OCRv6 weights
were bundled.

The dependencies involved:

```toml
ort = { version = "=2.0.0-rc.15", default-features = false,
        features = ["api-20", "load-dynamic", "std"] }
jni = { version = "=0.21.1", default-features = false, optional = true }
```

`api-20` + `load-dynamic` is the only combination that matches the ONNX Runtime the app already
ships (`com.microsoft.onnxruntime:onnxruntime-android:1.20.0`): it links no runtime of its own and
resolves `libonnxruntime.so` from the process at run time, so two runtimes can never coexist. The
`jni` feature is on by default and can be switched off for a host build
(`cargo build --no-default-features`).

## Models and dictionary (downloaded, not committed) — except the dictionary

`tools/fetch-ppocrv6-models.ps1` fetches the official artifacts and verifies the dictionary. Sizes
measured on 2026-11-05:

| Artifact | File | Size |
|---|---|---|
| `PaddlePaddle/PP-OCRv6_medium_det_onnx` | `inference.onnx` | 62,032,837 B (59.2 MiB) |
| `PaddlePaddle/PP-OCRv6_medium_rec_onnx` | `inference.onnx` | 76,554,979 B (73.0 MiB) |

The 139 MB of weights stay out of git (`models/` is ignored). The **dictionary is committed**: it is
75 KB of text, Apache-2.0, and the recognition model cannot be interpreted without it.

| File | Source | Identity |
|---|---|---|
| `assets/ppocrv6_dict.txt` | `ppocr/utils/dict/ppocrv6_dict.txt` in `PaddlePaddle/PaddleOCR` | 18,708 entries, git blob `270082aa29aec1ff3ac74d0d575c90399216f74d`, sha256 `B5F2BFE2BDD9448429E3E82B51C789775D9B42F2403D082B00662EB77E401C5D` |

It is **not** `en_dict.txt` and not the 19,053-entry dictionary of a manga fine-tune (that one was
fetched, compared and rejected: it belongs to a different model). `tools/fetch-ppocrv6-models.ps1`
re-downloads it, checks the blob/sha256 and the entry count, and warns if upstream ever drifts.

## Multilingual crops for the smoke test

```powershell
python tools/make-ocr-crops.py            # -> %TEMP%\bc-ocr-crops (six PNGs + manifest.json)
$env:BC_OCR_CROPS = "$env:TEMP\bc-ocr-crops"
cargo test --test real_models -- --ignored --nocapture ppocrv6_medium_reads_multilingual_crops
```

The crops are rendered with system fonts (Meiryo / Microsoft JhengHei / Microsoft YaHei / Arial), and
`manifest.json` records each source string so the printed output can be compared honestly. They are
synthetic, so the test is a **qualitative smoke test**, not an accuracy measurement, and the script's
source is ASCII-only (CJK written as `\u` escapes) so no console or editor can transcode it.
