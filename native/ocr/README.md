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
| `dict` | PP-OCR `CTCLabelDecode` index space (`blank = 0`, dictionary from 1, optional trailing space), greedy CTC decode with repeat collapsing, blank skipping and per-character confidence | done, 7 tests |
| `geom` | corner ordering with PaddleOCR's `get_mini_boxes` rule, quad size + vertical/horizontal classification, affine solve, bilinear perspective crop with edge clamping | done, 6 tests |

```
cargo test          # 13 tests, host (x86_64-pc-windows-msvc)
```

## What is not implemented yet

`det`, `rec`, `order`, the ONNX Runtime session layer and the JNI exports
(`Java_com_bookcovermatcher_online_NativeOcr_*`). Their exact specification is recorded in
`historical_prompt/v2.5.0-dev.5-ag.md` §6–§11 so the next session does not have to rediscover it.

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
