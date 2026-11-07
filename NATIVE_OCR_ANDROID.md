# Native OCR on Android — integration notes (`v2.5.0-dev.5-crash-fix-6`)

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
         │           ├── PpocrModels      model files + pinned SHA-256
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

## 5 · Model distribution

The detector and recogniser are ~139 MB, so they are **not bundled** and **not downloaded**:

* `PpocrModels` pins the three SHA-256 values (identical to the host-verified artifacts) and installs
  only when all three match, promoting `.part` files atomically and recording the verification so the
  139 MB is not re-hashed on every launch.
* Models live in app-private storage: `files/ocr/ppocrv6/{det.onnx,rec.onnx,ppocrv6_dict.txt}`.
* A developer installs them with `tools/push-ocr-models.ps1`, which pushes into a staging directory,
  makes it readable, and copies with `run-as` so the files carry the app's UID. Android 11+ hides
  shell-owned directories from the app, which otherwise looks exactly like "models missing".
* A user-facing download flow is deliberately **out of scope** here; it needs status UI, cancellation,
  retry and resume, and is a separate product decision.

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
| 4 | Models installed, verified, offline-usable | `models: ready (138,662,763 bytes)` after on-device SHA-256 verification |
| 5 | **Real OCR on a physical Android device** | **NOT DONE** — no physical device was attached |

Real inference was exercised on the emulator `Medium_Phone_API_37.0` (Android 17, x86_64, 4 GB RAM):
10 lines read from the bundled Hobbit cover in **1,991 ms cold / 967 ms warm**, against Python's
1,226 ms for 4 coarser blocks on the same image.

## 8 · Known limitations

1. **No physical-device run.** ARM latency, memory and thermal behaviour are unmeasured; the numbers
   above are emulator numbers and must not be quoted as phone performance.
2. **No user-facing model distribution** (see §5); installation is a developer step.
3. **No language detection or translation** on the native path.
4. **Inference cannot be interrupted** (§3); cancellation discards results.
5. **No per-language accuracy claim.** Host-side synthetic crops cover en/ja/zh-Hant/zh-Hans, but no
   reference transcriptions exist for real scans, so no CER is reported.
6. **Python retirement is out of scope.** Chaquopy, `chrome-lens-py` and `lens_bridge.py` stay until a
   separately authorised task removes them.

## 9 · Reproducing the device run

```powershell
powershell -ExecutionPolicy Bypass -File tools/build-native-ocr.ps1      # both ABIs → jniLibs
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
adb -s <serial> install -r app\build\outputs\apk\debug\app-debug.apk
adb -s <serial> install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
powershell -ExecutionPolicy Bypass -File tools/push-ocr-models.ps1 -Serial <serial>
adb -s <serial> shell am instrument -w -e class com.bookcovermatcher.ocr.NativeOcrDeviceTest `
    com.bookcovermatcher.test/androidx.test.runner.AndroidJUnitRunner
adb -s <serial> logcat -d -s NativeOcrTest:I
```

`connectedDebugAndroidTest` also runs the suite, but AGP reinstalls the app and wipes the installed
models first — run it **before** `push-ocr-models.ps1`, or use `am instrument` as above.
