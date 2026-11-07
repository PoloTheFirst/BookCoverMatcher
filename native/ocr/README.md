# `bc_ocr` — native OCR core (v2.5.0-dev.5, recognition verified on the host)

This crate is the native half of the migration described in
`historical_prompt/v2.5.0-dev.5.md`: PP-OCRv6 text detection + recognition on ONNX Runtime,
called from Kotlin through JNI, replacing the embedded-Python `chrome-lens-py` OCR.

**It is not the application's OCR engine yet.** Nothing under `app/` loads `libbc_ocr.so`, so the
shipped OCR path is still the existing engine. That is deliberate: the release prompt requires the
old engine to stay functional until the new one is proven on a device, and a half-verified native
path must never become the default.

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

Toolchain used and verified on this machine:

* Rust 1.99.0 with `aarch64-linux-android` and `x86_64-linux-android` targets
  (`rustup target add …`);
* Android NDK **r27d** at `%LOCALAPPDATA%\Android\Sdk\ndk\android-ndk-r27d`;
* no `cargo-ndk` needed — the NDK's own `clang.exe` is enough. The sizes below were measured before
  `ort` was added, so re-measure after any dependency change:

```powershell
$ndk = "$env:LOCALAPPDATA\Android\Sdk\ndk\android-ndk-r27d\toolchains\llvm\prebuilt\windows-x86_64\bin"
foreach ($t in 'x86_64-linux-android','aarch64-linux-android') {
  $env:CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER = "$ndk\clang.exe"
  $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = "$ndk\clang.exe"
  $api = $t -replace '^(.*)-linux-android$', '$1'
  $env:RUSTFLAGS = "-C link-arg=--target=$t" + "26"
  cargo build --release --target $t
}
```

Verified output: `target/x86_64-linux-android/release/libbc_ocr.so` (326 KB) and
`target/aarch64-linux-android/release/libbc_ocr.so` (300 KB), Android API 26 (`minSdk` of the app).

The ONNX stages are already wired to the runtime the app ships:

```toml
ort = { version = "=2.0.0-rc.15", default-features = false,
        features = ["api-20", "load-dynamic", "std"] }
# not yet needed — JNI is the next stage:
# jni = { version = "0.21", default-features = false }
```

`api-20` + `load-dynamic` is the only combination that matches the ONNX Runtime the app already
ships (`com.microsoft.onnxruntime:onnxruntime-android:1.20.0`): it links no runtime of its own and
resolves `libonnxruntime.so` from the process at run time, so two runtimes can never coexist.

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
