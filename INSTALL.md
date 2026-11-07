# Installing BookCoverMatcher on a device

This file exists because installing a build produced **"There was a problem parsing the package"**. Everything
below is measured from the artifacts in this repository, not assumed. Use it to tell in one minute whether the
problem is the APK or the device/transfer.

## 1. Which artifact to install

| | Debug build | Release build |
|---|---|---|
| File | `app/build/outputs/apk/debug/app-debug.apk` | `app/build/outputs/apk/release/app-release.apk` (a copy also sits in `app/release/app-release.apk`) |
| Size (v2.5.0-dev.5) | 195,381,208 B (186.33 MiB) | 191,212,207 B (182.35 MiB) |
| SHA-256 (v2.5.0-dev.5) | `3EF3BBEC3C438B1C55EBA2644A001F9768DFF55D12DC37859B63B07541A0F742` | `33B89335D5B8B0C9B76C32AFAE65FEDA65451060F98A298F86E33C4C26A969CA` |
| Package / version | `com.bookcovermatcher` · `220` / `2.5.0-dev.5` | identical |
| Signature | v2 only · `C=US, O=Android, CN=Android Debug` (`7a2510fd…`) | v2 only · `CN=Tin Yan To` (`db662c95…`) |
| ABIs inside | `arm64-v8a`, `x86_64` | identical |

**The two builds are signed with different certificates.** That is deliberate for distribution, but it has one
consequence that looks like a broken APK:

> A phone that already has the **debug** build installed will refuse the **release** APK, because Android will not
> replace an app whose signing certificate changed. Uninstall the old build first:
>
> ```powershell
> adb uninstall com.bookcovermatcher
> adb install -r app\build\outputs\apk\release\app-release.apk
> ```
>
> On a phone without adb: long-press the app icon → *Uninstall* → then install the APK again.

## 2. Check the APK before blaming the phone

```powershell
Get-FileHash app\build\outputs\apk\release\app-release.apk -Algorithm SHA256
# must equal 33B89335D5B8B0C9B76C32AFAE65FEDA65451060F98A298F86E33C4C26A969CA for v2.5.0-dev.5

& "$env:LOCALAPPDATA\Android\Sdk\build-tools\35.0.0\apksigner.bat" verify --verbose --print-certs `
    app\build\outputs\apk\release\app-release.apk
# "Verifies" + "Verified using v2 scheme" + the certificate above

& "$env:LOCALAPPDATA\Android\Sdk\build-tools\35.0.0\aapt2.exe" dump badging `
    app\build\outputs\apk\release\app-release.apk | Select-Object -First 3
# package: name='com.bookcovermatcher' versionCode='220' versionName='2.5.0-dev.5' … minSdkVersion:'26'
```

If any of those three disagree with the table above, the file you have is not the file that was built: re-copy it.

## 3. Check the phone

* **Android 8.0 (API 26) or newer is required.** `minSdkVersion` is **26**; an older device cannot parse the
  package and Android reports exactly *"There was a problem parsing the package"*. Check with
  *Settings → About phone → Android version*, or `adb shell getprop ro.build.version.sdk` (must print `26` or more).
* **The phone must be 64-bit** (`arm64-v8a`). Every current phone is; a 32-bit-only device is not supported and
  would fail with a different message (*no matching ABIs*).
* **Free storage**: the APK is ~182 MiB and expands to roughly 400 MiB installed (embedded Python runtime, ONNX
  Runtime for two ABIs, resources). Android refuses an install without enough free space.

## 4. Transferring the file

182 MiB is large enough that a copy can end silently truncated — which Android also reports as *"problem parsing
the package"*. Copy it again and re-check the SHA-256 on the phone (or compare byte counts), or install it over
USB with `adb install`, which verifies the transfer.

## 5. What is known to work

Verified on this machine, from the committed source:

* `app-release.apk` (the exact file above) — `adb install` **succeeded**, app launched, Home screen rendered, the
  OCR panel reported `chrome-lens-py 3.8.2 · OCR only, no product search`.
* Emulator: `Medium_Phone_API_37.0` / AVD `Pixel_8`, Android **API 37**, ABI **x86_64**
  (`primaryCpuAbi=x86_64`, `versionCode=220`, `versionName=2.5.0-dev.5`).

If the APK verifies here and the phone is Android 8.0+ with a complete copy, the remaining causes are: an existing
install signed with another certificate (§1), or Play Protect blocking a sideloaded build
(*Play Store → Play Protect → Settings → turn off "Scan apps with Play Protect" while installing*).
