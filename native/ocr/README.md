# `bc_ocr` — native OCR core (v2.5.0-dev.5, Stage B in progress)

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
| `rec` | recogniser preprocessing (BGR, height 48, width ≤ 320, right padding, `(v/255-0.5)/0.5`) and CTC decoding with an explicit class-count guard | done, 10 tests |
| `order` | reading order: horizontal bands left-to-right, vertical Japanese columns right-to-left then top-to-bottom | done, 6 tests |
| `engine` | runtime init (`init`/`init_from`), session creation, model metadata, the full pipeline | implemented; **text decoding blocked** (see below) |

```
cargo test          # 40 tests, host — all pass
```

Cross-compiled for Android (NDK r27d, API 26): `libbc_ocr.so` for arm64-v8a and x86_64.

## Real-model verification (host, ONNX Runtime 1.20.0)

`cargo test --test real_models -- --ignored --nocapture` with `BC_OCR_MODELS`,
`BC_OCR_IMAGE` and `ORT_DYLIB_PATH` set:

| Step | Result |
|---|---|
| Runtime resolution | **ok** — `ort::init()` loaded `onnxruntime.dll` 1.20.0 via `load-dynamic`; nothing linked |
| Detector load | **ok** — input `x[-1,3,-1,-1]`, output `[-1,1,-1,-1]` (fully dynamic) |
| Recogniser load | **ok** — input `x[-1,3,48,-1]`, output `fetch_name_0[-1,-1,18710]` (single CTC head) |
| Detector inference | **ok** — 512×320 probability map in **195 ms**, 12,543 / 163,840 values above 0.2 |
| DB post-processing | **ok** — **11 regions**, scores 0.58–0.96, 2 correctly classified as vertical (Japanese columns) |
| Reading order | **ok** — `[0,1,3,5,6,7,8,9,10,2,4]`: horizontal lines first, then the columns right-to-left |
| Recogniser inference | **ok** — the graph executes and returns finite logits |
| Text decoding | **BLOCKED** — see below |

### Blocking mismatch: model and dictionary disagree

* The model's CTC head emits **18,710** classes.
* The dictionary published in the model's own `inference.yml` has **18,705** entries (the extracted
  `ppocrv6_dict.txt` used here has 18,704 lines), i.e. it can address 18,706 or 18,707 classes.
* `rec::decode_output` refuses to decode rather than truncating the 5 extra classes or shifting the
  index space; the error names both counts:

```
recogniser outputs 18710 classes but the dictionary maps 18706 (18704 characters + blank + space);
refusing to decode
```

Nothing was invented to get past this: no truncation, no padding of the dictionary, no third-party
dictionary substitution (the 19,053-entry dictionary of a manga *fine-tune* was fetched, compared and
**rejected** because it belongs to a different model). Obtaining the export's exact 18,710-class
dictionary is a prerequisite for text output.

## What is not implemented yet

| Stage | State |
|---|---|
| Exact dictionary for the exported recogniser | **missing upstream** — blocker for text |
| JNI exports (`Java_com_bookcovermatcher_online_NativeOcr_*`) | not written |
| Kotlin `NativeOcr` / `RustOcrTextSource`, model distribution | not written |
| Device inference, 4-language accuracy, NNAPI | not measured |

Because of this, **the application still performs OCR through the embedded Python engine** and `app/`
is untouched. The migration is **incomplete**.

## Building for Android

Toolchain used and verified on this machine:

* Rust 1.99.0 with `aarch64-linux-android` and `x86_64-linux-android` targets
  (`rustup target add …`);
* Android NDK **r27d** at `%LOCALAPPDATA%\Android\Sdk\ndk\android-ndk-r27d`;
* no `cargo-ndk` needed for this dependency-free crate — the NDK's own `clang.exe` is enough:

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

Once the ONNX stages land, the crate gains:

```toml
ort = { version = "=2.0.0-rc.15", default-features = false,
        features = ["api-20", "load-dynamic", "std"] }
jni = { version = "0.21", default-features = false }
```

`api-20` + `load-dynamic` is the only combination that matches the ONNX Runtime the app already
ships (`com.microsoft.onnxruntime:onnxruntime-android:1.20.0`): it links no runtime of its own and
resolves `libonnxruntime.so` from the process at run time, so two runtimes can never coexist.

## Models (downloaded, not committed)

`tools/fetch-ppocrv6-models.ps1` fetches the official artifacts and extracts the dictionary. Sizes
measured on 2026-11-05:

| Artifact | File | Size |
|---|---|---|
| `PaddlePaddle/PP-OCRv6_medium_det_onnx` | `inference.onnx` | 62,032,837 B (59.2 MiB) |
| `PaddlePaddle/PP-OCRv6_medium_rec_onnx` | `inference.onnx` | 76,554,979 B (73.0 MiB) |
| dictionary | `character_dict` inside the recognition `inference.yml` | 18,704 characters |

The dictionary is **not** `en_dict.txt`: the recognition model's own `inference.yml` declares
`CTCLabelDecode` with an 18,704-entry `character_dict` that already covers kana, CJK and Latin.
