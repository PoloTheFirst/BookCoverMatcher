# Environment inventory — v2.4.0-rc.7

**Recorded:** 2026-11-05. Native Windows, PowerShell, Android Studio. No WSL was used.

## Repository

| Item | Value |
|---|---|
| Project root | `C:\Users\ASUS\AndroidStudioProjects\BookCoverMatcher` |
| QA workspace | `...\BookCoverMatcher-QA` (relocated inside the project; used as the only QA root) |
| Authentic workbooks | `...\test_excel` (read-only) |
| Branch | `dev` |
| Starting commit | `3472f7bd4c03a1b8c620442a72cfa3d993bf9ec1` — "fix: finish v2.4.0 - filters, cell formats and per-worksheet mapping (v2.4.0)", pushed to `origin/dev` |
| Remote | `origin` → `https://github.com/PoloTheFirst/BookCoverMatcher.git` |
| Working tree at start | `.idea/deploymentTargetSelector.xml` modified, `historical_prompt/v2.4.0-rc.7.md` staged by the user, `BookCoverMatcher-QA/` and `hs_err_pid4316.log` untracked |

## Build toolchain

| Item | Value |
|---|---|
| JDK | 25.0.4.1 LTS (`java -version`) |
| Gradle | wrapper 9.8.0 (`gradle-wrapper.properties`) |
| AGP / Kotlin | from `gradle/libs.versions.toml` (Compose BOM 2024.10.01, Chaquopy 17.1.0, onnxruntime-android 1.20.0) |
| SDK levels | `compileSdk 35`, `targetSdk 35`, `minSdk 26`; ABIs `arm64-v8a`, `x86_64` |
| Version before this round | `versionCode 213`, `versionName "2.4.0"` |
| Version after this round | `versionCode 214`, `versionName "2.4.0-rc.7"` |

## Device and SDK

| Item | Value |
|---|---|
| Android SDK | `C:\Users\ASUS\AppData\Local\Android\Sdk` |
| platform-tools / adb | present; `adb devices -l` → `emulator-5554  device` |
| Emulator binary | `...\Sdk\emulator\emulator.exe` |
| AVDs installed | `Pixel_8`, `Medium_Phone_API_37.0` |
| Device used | **Pixel_8** (`emulator-5554`) |
| Product / API | `sdk_gphone64_x86_64`, Android 17 / **API 37**, build `CE2A.260420.051.A2` |
| Display | 1080 × 2400 @ 420 dpi |
| Boot | headless (`-no-window -no-audio -no-boot-anim -gpu swiftshader_indirect`); the previous snapshot failed to load, so it cold-booted |

## Python (QA tooling)

| Item | Value |
|---|---|
| Interpreter | `BookCoverMatcher-QA\.venv\Scripts\python.exe`, **Python 3.14.9** |
| Libraries used | standard library only for rc.7 (`zipfile`, `hashlib`, `json`, `subprocess`); the prior round's generators use Pillow 13 |
| rc.7 scripts added | `scripts/rc7_verify.py`, `scripts/rc7_verify_range.py`, `scripts/rc7_fixture_check.py` |

## Blockers and environment defects found

1. **The prior QA tooling still targets the obsolete external QA directory.** `deepseek_driver.py:21`
   (`QA = C:\Users\ASUS\AndroidStudioProjects\BookCoverMatcher-QA`) and `deepseek_structure_check.py:28`
   do the same. The first run of `rc7_verify.py` therefore wrote its evidence outside the project; it was
   copied into `reports/v2.4.0-rc.7/` and the external copy was removed. The rc.7 scripts compute the QA
   root from their own location instead.
2. **`uiautomator dump` waits for an idle window**, and the home screen runs an infinite pulse animation,
   so a dump can block for its full timeout. The driver bounds each dump and retries; this is why the
   first dump of the home screen does not contain the range field (it is below the fold as well).
3. **Camera content cannot be injected** on this emulator build (see `camera-test-report.md`).
4. Nothing blocked the build, the unit suite or the two rc.7 defect verifications.
