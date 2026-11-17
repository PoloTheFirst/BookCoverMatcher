# Emulator Baseline — Pixel 8 AVD

**Captured:** 2026-11-02 15:56–16:21 CST
**Requirement:** the brief's "Pixel 8 virtual device managed through Android Studio's Device Manager".

## Identity

| Item | Value | Source |
|---|---|---|
| AVD name | **Pixel_8** | `ro.boot.qemu.avd_name`, `emulator -list-avds` |
| ADB serial | **emulator-5554** | `adb devices -l` |
| ADB state | `device` (not unauthorized/offline) | `adb devices -l` |
| Product / device | `sdk_gphone64_x86_64` / `emu64xa` | `ro.product.name`, `ro.product.model` |
| ABI | `x86_64` | `ro.product.cpu.abi` |
| Android release | **17** | `ro.build.version.release` |
| API level | **37** | `ro.build.version.sdk` |
| Build id | `CE2A.260420.051.A2` | `ro.build.fingerprint` |
| Build fingerprint | `google/sdk_gphone64_x86_64/emu64xa:17/CE2A.260420.051.A2/16418371:user/release-keys` | `getprop` |
| Boot completed | `1` | `getprop sys.boot_completed` |
| Resolution | 1080 × 2400 | `wm size` |
| Density | 420 dpi | `wm density` |
| Data partition | 10 G total, 2.0 G used, **7.4 G available** | `df -h /data` |
| Cameras | 2 logical devices | `dumpsys media.camera` |

## Camera configuration (from `~/.android/avd/Pixel_8.avd/config.ini`)

```
hw.camera.back = virtualscene
hw.camera.front = emulated
hw.gpu.enabled = yes
hw.gpu.mode = auto
hw.lcd.width = 1080
hw.lcd.height = 2400
hw.lcd.density = 420
```

The back camera is the emulator **virtual scene** (a 3-D room), which is the camera the app uses
for scanning. This is a real CameraX/Camera2 path through the emulator HAL — not a file import.

Content injection for scenarios: the emulator supports
`-virtualscene-poster wall=<png|jpeg>`, which replaces the poster on the virtual scene's wall.
That makes controlled imagery reach the app through the camera pipeline. Poster names supported by
this emulator build: `wall` (vertical plane), `table` (horizontal plane).

**Known limitation:** the virtual scene's viewpoint is controlled interactively in the emulator
window; precise framing (e.g. "cover occupies 30 % of frame, rotated 12°") cannot be commanded
purely over adb. Scenarios therefore bake the geometry into the injected poster image (angle,
rotation, scale, lighting, obstruction), which is a faithful test of what the app receives.

## Emulator launch used

```powershell
Start-Process -FilePath 'C:\Users\ASUS\AppData\Local\Android\Sdk\emulator\emulator.exe' `
              -ArgumentList '-avd','Pixel_8' -WorkingDirectory '...\Sdk\emulator'
```

Started from the existing AVD; **no new AVD was created**, per the brief.

## Application installation state

| Item | Value |
|---|---|
| Package | `com.bookcovermatcher` |
| versionName / versionCode | 2.4.0 / 213 |
| minSdk / targetSdk | 26 / 35 |
| first install | 2026-11-02 06:49:21 |
| last update (before this engagement) | 2026-11-02 08:43:31 |
| Reinstalled by this engagement | `adb install -r app-debug.apk` → `Success` (APK built 07:29, source ≤ 05:18) |
| Launch | `am start -n com.bookcovermatcher/.MainActivity` → resumed, **0 fatal exceptions** in logcat |

## Baseline screenshots

| File | Content |
|---|---|
| `evidence/screenshots/emulator-baseline/001-app-launch.png` | Home screen: live camera preview (virtual scene), "Scan Book Cover", auto-scan toggle, restart camera, bottom nav (Home/Search/Wishlist/More) |

## Fixture staging on device

52 workbooks copied to `/sdcard/Documents/QA/<category>/` (6 category folders). Verified by
counting `*.xlsx` under those folders: **52**. The authentic `doujin.xlsx` was already present on
the device at `/sdcard/Documents/doujin.xlsx`; it was **not** imported during automated runs
(40 MB, and it is user data — read-only).
