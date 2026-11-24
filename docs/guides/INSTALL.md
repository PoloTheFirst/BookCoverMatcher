# Installing BCM on a device

## Development and published builds

The local development build is **3.0.0-dev.2 (228)**. Look for **BCM** in your launcher. The Android app ID is still `com.bookcovermatcher`. The published standard APK is **v2.7.0 (226)**. Build Android with compile SDK 37, target SDK 36 and minimum SDK 26.

Use the [distribution build and signing guide](DISTRIBUTION_VARIANTS.md) for the new `play` and `github` APK paths. Both editions use the same package ID and require a matching release certificate to update an existing installation. Release builds no longer fall back to a debug signing key. Preserve app data: stop on signing incompatibility; do not uninstall or clear data.

The measurements below are historical v2.5.0 troubleshooting evidence, not current filenames, hashes, sizes or signing instructions.

This file exists because installing a build produced **"There was a problem parsing the package"**. Everything
below is measured from the artifacts in this repository, not assumed. Use it to tell in one minute whether the
problem is the APK or the device/transfer.

## 1. Which artifact to install

| | Debug build | Release build |
|---|---|---|
| File | `app/build/outputs/apk/debug/app-debug.apk` | `app/build/outputs/apk/release/app-release.apk` (a copy also sits in `app/release/app-release.apk`) |
| Size (v2.5.0) | 336,866,830 B (321.27 MiB) | 331,134,539 B (315.79 MiB) |
| SHA-256 (v2.5.0) | `9037F6DB6A4E713EE4F18E2887163C8D2D5D6B3BF3DB71B27E098A0E790A0C36` | `FFA171DDBBD08F9647EA5B785C9B86D88F9310AC2E64636D46D78DF9CD0933A5` |
| Package / version | `com.bookcovermatcher` · `221` / `2.5.0` | identical |
| Signature | v2 only · `C=US, O=Android, CN=Android Debug` (`7a2510fd…`) | v2 only · `C=US, O=Android, CN=Android Debug` |
| ABIs inside | `arm64-v8a`, `x86_64` | identical |
| PaddleOCR models | bundled (132.2 MiB, hash-verified in the APK) | identical |

Both artifacts grew by ~132 MiB in this build because the PP-OCRv6 detector, recogniser and dictionary
are now **inside the APK** — see `docs/reference/NATIVE_OCR_ANDROID.md` §5. `tools/verify-ocr-apk.ps1` prints the
per-file hashes straight out of the archive.

**Signing.** Both artifacts are signed with the *Android Debug* certificate configured in
`app/build.gradle.kts`, so on this machine either installs over the other. A debug keystore is
machine-local: an APK built elsewhere may not replace this historical installation. For current editions, compare the actual certificates and stop if they differ. Rebuild using the correct existing key; uninstalling would remove local app data and is not an update strategy.

## 2. Check the APK before blaming the phone

```powershell
Get-FileHash app\build\outputs\apk\release\app-release.apk -Algorithm SHA256
# must equal FFA171DDBBD08F9647EA5B785C9B86D88F9310AC2E64636D46D78DF9CD0933A5 for v2.5.0

& "$env:LOCALAPPDATA\Android\Sdk\build-tools\35.0.0\apksigner.bat" verify --verbose --print-certs `
    app\build\outputs\apk\release\app-release.apk
# "Verifies" + "Verified using v2 scheme" + the certificate above

& "$env:LOCALAPPDATA\Android\Sdk\build-tools\35.0.0\aapt2.exe" dump badging `
    app\build\outputs\apk\release\app-release.apk | Select-Object -First 3
# package: name='com.bookcovermatcher' versionCode='221' versionName='2.5.0' … minSdkVersion:'26'
```

If any of those three disagree with the table above, the file you have is not the file that was built: re-copy it.

## 3. Check the phone

* **Android 8.0 (API 26) or newer is required.** `minSdkVersion` is **26**; an older device cannot parse the
  package and Android reports exactly *"There was a problem parsing the package"*. Check with
  *Settings → About phone → Android version*, or `adb shell getprop ro.build.version.sdk` (must print `26` or more).
* **The phone must be 64-bit** (`arm64-v8a`). Every current phone is; a 32-bit-only device is not supported and
  would fail with a different message (*no matching ABIs*).
* **Free storage**: the APK is ~316 MiB and installing it used 346,252 KiB (~338 MiB) on the test device
  (APK, dex, embedded Python runtime, ONNX Runtime for two ABIs). The first PaddleOCR use adds a
  138,662,763 B (~132 MiB) private copy of the models, so budget roughly 500 MiB. Android refuses an
  install without enough free space.

## 4. Transferring the file

316 MiB is large enough that a copy can end silently truncated — which Android also reports as *"problem parsing
the package"*. Copy it again and re-check the SHA-256 on the phone (or compare byte counts), or install it over
USB with `adb install`, which verifies the transfer.

## 5. What is known to work

Verified on this machine, from the committed source:

* `app-release.apk` (the exact file above) — `adb install` **succeeded**, app launched, Home screen rendered, the
  OCR panel reported `chrome-lens-py 3.8.2 · OCR only, no product search`.
* Emulator: `Medium_Phone_API_37.0` / AVD `Pixel_8`, Android **API 37**, ABI **x86_64**
  (`primaryCpuAbi=x86_64`, `versionCode=221`, `versionName=2.5.0`).

If the APK verifies here and the phone is Android 8.0+ with a complete copy, the remaining causes are: an existing
install signed with another certificate (§1), or Play Protect blocking a sideloaded build
(*Play Store → Play Protect → Settings → turn off "Scan apps with Play Protect" while installing*).
