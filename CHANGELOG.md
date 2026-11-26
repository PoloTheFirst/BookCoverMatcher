## v3.0.0-dev.3 (2026-11-24)

# BCM’s open-book logo

BCM and BCM Plus have a new Material 3 Expressive mark on the Android launcher and Home screen. A glint on the open book’s right page represents a matched cover.

### Added

- A dedicated monochrome book with a transparent page crease and glint for Android’s themed icons.

### Changed

- Light uses a cream tile with blush and ivory pages outlined in rose. Dark uses a deep rose tile with blush and ivory pages. Both draw from Polo’s Atelier.
- Home follows **More → Display**. The full-color launcher follows Android’s appearance where the launcher supports it.
- Refreshed the OAuth logo, Play listing icon, full-size master and feature graphic in the repository.

### Fixed

- The Home mark uses the app’s selected appearance even when the phone is set to the other mode. Its framing matches the visible center of the adaptive icon.

### Removed

- Replaced the cyan book and navy tile in the current app and branding artwork.

### Tests

- The Play debug and release APKs assembled successfully. 1,271 JVM tests passed and 12 were skipped. Lint reported zero errors and 61 warnings.
- The BCM Plus debug and release APKs assembled successfully. 1,444 JVM tests passed and 16 were skipped. Lint reported zero errors and 63 warnings.

### Verified

- Checked the launcher and Home artwork on Android 8 and Android 16 emulators and validated the monochrome cutouts. All four exported PNGs match their recorded sizes and digests. The artwork stays within Android’s adaptive icon safe zone.

### Known limitations

- Launcher shape and themed tint depend on the device. On the Android 16 emulator, Pixel Launcher kept its previous full-color shortcut after a system appearance change until the app package refreshed. BCM Home switched immediately.

### APK version codes

| Edition | Original APK code | Rebuilt APK code |
| --- | ---: | ---: |
| BCM | [229](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v3.0.0-dev.3/BCM-v3.0.0-dev.3.apk) | [2999903](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v3.0.0-dev.3/BCM-v3.0.0-dev.3-code2999903.apk) |
| BCM Plus | [229](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v3.0.0-dev.3/BCM-Plus-v3.0.0-dev.3.apk) | [2999903](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v3.0.0-dev.3/BCM-Plus-v3.0.0-dev.3-code2999903.apk) |

**Version code/name: 2999903 / 3.0.0-dev.3.**
