# Environment Baseline — deepseek-qa-recovery

**Captured:** 2026-11-02 ~16:00 CST (UTC+08:00) on the QA host.

## Host

| Item | Value |
|---|---|
| OS | Microsoft Windows 11 Pro 10.0.26300, build 26300 |
| CPU | AMD Ryzen AI MAX+ 395 w/ Radeon 8060S — 16 cores / 32 threads |
| RAM | 31.6 GB |
| Free space on C: | 288.6 GB |
| Shell used for QA tooling | git-bash (MSYS) invoking native `adb.exe` / `powershell.exe` |
| Note | All commands are native Windows binaries; no WSL is involved. |

## Android toolchain

| Item | Value | How verified |
|---|---|---|
| adb | `C:\Users\ASUS\AppData\Local\Android\Sdk\platform-tools\adb.exe`, 1.0.41 / 37.0.1-15733141 | `where.exe adb`, `adb version` |
| Emulator binary | `…\Sdk\emulator\emulator.exe`, **37.2.13.0** (build 16740729) | `emulator -version` |
| SDK root | `C:\Users\ASUS\AppData\Local\Android\Sdk` | `ls` |
| Platforms installed | android-35, **android-37.0** | `ls platforms` |
| Build-tools | 34.0.0, 35.0.0, 36.0.0 | `ls build-tools` |
| System images | android-34 google_apis_playstore; android-37.0 google_apis_playstore (`_ps16k`) | `ls system-images` |
| AVDs | `Pixel_8`, `Medium_Phone_API_37.0` | `emulator -list-avds` |
| Android Studio | running (studio64.exe, PID 17012) | `tasklist` |
| JBR / JDK for harness | OpenJDK 25.0.4 (Android Studio JBR) | `jbr/bin/java -version` |
| Gradle | 9.8.0 (wrapper) | `gradle-wrapper.properties` |
| Chaquopy build Python | CPython 3.12.15 via uv (`…\uv\python\cpython-3.12.15-…\python.exe`) | `local.properties` |

## Application build configuration (`app/build.gradle.kts`)

| Item | Value |
|---|---|
| applicationId / namespace | `com.bookcovermatcher` |
| versionName / versionCode | 2.4.0 / 213 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 |
| ABIs | `arm64-v8a`, `x86_64` (x86_64 needed for this emulator) |
| UI | Jetpack Compose (BOM), CameraX, ONNX Runtime, Chaquopy (chrome-lens-py 3.8.2) |
| Launcher activity | `com.bookcovermatcher.MainActivity` (exported, `screenOrientation="portrait"`) |
| Permissions | `CAMERA`, `INTERNET` (INTERNET only for opt-in online search, off by default) |

**Rotation note:** `MainActivity` is declared `android:screenOrientation="portrait"`, so
"rotate the device" scenarios do not produce a landscape layout by design. Recorded here so the
rotation tests are interpreted correctly rather than reported as a missing feature.

## Git baseline (source repo)

Captured to `evidence/logcat/git-baseline.txt` before any change by this engagement.

| Item | Value |
|---|---|
| Branch | `dev` → `origin/dev` |
| Commit | `e751450f345f77e5caaf0ca47d8f1a60e66c0cd4` (v2.4.0-dev.4) |
| Working tree | **dirty**: 20 staged, 46 modified, 23 untracked (87 entries) |
| Newest source file | `app/src/main/java/…/SheetMapping.kt`, 2026-11-02 05:18 |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk`, 2026-11-02 07:29:48, 195,194,889 B |

**APK currency check:** the APK timestamp (07:29) is later than the newest source change (05:18),
and `assembleDebug` reported `UP-TO-DATE` for `compileDebugKotlin`/`packageDebug`. The installed
APK therefore corresponds to the working tree, not to `HEAD`.

This engagement did not commit, stash, reset, or clean anything. The pre-existing dirty tree is
preserved exactly as found.

## Authentic workbook integrity

| Item | Value |
|---|---|
| Path | `BookCoverMatcher\test_excel\doujin.xlsx` |
| SHA-256 | `a5ad2ba78808c9aa8e57d7ffec64f0577d34d28d63eadb56ba952d79ac2a6aad` |
| Size | 40,483,600 B |
| mtime | 2026-10-31 06:40 |
| Status | matches the hash recorded by the previous engagement; **not modified** by this one |

## QA workspace tooling added by this engagement

| File | Purpose |
|---|---|
| `scripts/deepseek_driver.py` | adb/UIAutomator primitives + reliable SAF picker navigation |
| `scripts/deepseek_sweep.py` | runs every fixture through the real UI, appends JSONL |
| `scripts/deepseek_structure_check.py` | independent OOXML validator (does not reuse the previous validator) |
| `scripts/generate_camera_images.py` | synthetic camera/scenario images (Pillow) |
