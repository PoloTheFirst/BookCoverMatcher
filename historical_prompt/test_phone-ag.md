Based on what's actually in this project — `minSdk 26` / `targetSdk 35` / `compileSdk 35`, ABIs `arm64-v8a` + `x86_64`, a 195 MB APK carrying embedded CPython (Chaquopy) + ONNX Runtime, Compose Material3, and no Play Services / ML Kit dependency anywhere — plus the fact that the previous on-device run was on a `Medium_Phone_API_37.0` (x86_64) AVD:

## Create this one

**Pixel 7 · API 36 (Android 16) · x86_64 · 1080×2400 @ 420 dpi**

| Setting | Value | Why for *this* app |
|---|---|---|
| Device profile | **Pixel 7** (or Pixel 8 — identical 1080×2400/420dpi geometry) | 1080×2400 is the coordinate/screenshot baseline most agent stacks expect; stable `adb shell input` taps without dpi surprises |
| System image | **x86_64**, API 36 | Matches your `abiFilters`; no ARM translation, so the Python runtime and ONNX sessions don't crawl |
| Google APIs? | **No** — plain AOSP image | Verified: the app has zero `play-services`/ML Kit deps (OCR is in-process Python, matching is ONNX). AOSP keeps `adb root` available for the agent |
| RAM | **4096 MB** | Chaquopy + OpenCLIP load large native libs; the Fields preview and Health/Gallery builds are memory-heavy |
| Internal storage | **10–16 GB** | 195 MB app + 40 MB `doujin.xlsx` workbooks + staged copies + extracted Python |
| Graphics | **Hardware (host GPU)** | The source window draws up to 96 columns of Compose text; SwiftShader makes the audition painful |
| Camera | VirtualScene / webcam0 | Only if the agent path touches capture — the emulator is exactly what blocked the Camera/Auto Scan test before |

API 36 over 37: 37 is what your `connectedDebugAndroidTest` already passed on, but 36 is the mature release that enforces edge-to-edge against your `targetSdk 35`, so layout regressions surface there first. Keep the AVD name close to the repo's (`Pixel_7_API_36`) so `connectedDebugAndroidTest` stays reproducible.

## Two alternates, only if the workflow demands it

- **Agent needs root / system-level automation** (writing settings, `screencap` outside app scope, accessibility-service injection without a device-owner hack): same Pixel 7 / API 36, but pick the **AOSP "VanillaIceCream" image with no Google APIs** — nothing in this app needs GMS, so you lose nothing.
- **Camera, Auto Scan, real CameraX** — the one thing an emulator can't honestly validate (documented as unverified in your README): a **physical Pixel 7a / 8**. Everything else (Fields chips, cell dialog, timeline, gallery, source window) is fine on the AVD.

## Worth adding a second AVD for

**Pixel Tablet (2560×1600 @ 320 dpi)**, because your own notes say "no screen width was measured" — and `v2.4.0` added the wide Insights tables, the Fields chips row, and the 24-column worksheet table. That's the one profile that would catch a width-dependent layout bug the agent can't see on a phone.