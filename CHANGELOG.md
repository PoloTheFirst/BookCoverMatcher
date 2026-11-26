## v3.0.0-dev.6 (2026-11-25)

# Choose your colors in More → Display

BCM and BCM Plus open with the Sophie palette from Polo’s Atelier. You can now use your phone’s colors or build a custom palette while choosing light or dark appearance separately.

### Added

- **Use phone colors** applies the Android system palette on Android 12 and newer. Turning it off restores your previous manual palette.
- **Custom palette** lets you select an accent hue with a slider or take the hue from a six-digit hex color. The app generates matching surfaces, controls and text for light and dark appearances.

### Changed

- **More → Display** shows both the appearance and the active palette. Palette changes apply immediately, including to the scanner controls, and persist when the app reopens.

### Fixed

- None.

### Removed

- None.

### Tests

- JVM tests passed in both editions. BCM passed 1,271 with 12 skipped. BCM Plus passed 1,444 with 16 skipped. Android lint completed for both editions.
- Three instrumented tests passed for each edition on both Android 8 and Android 16. They exercised the Display controls, slider and hex input, appearance overrides, restoration of manual colors and palette persistence after activity recreation. Contrast checks covered 25 hue positions in both appearances.

### Verified

- On Android 16, BCM’s phone-palette primary color matched Android’s light and dark schemes. On Android 8, manual palettes worked and the phone-colors switch was disabled.
- Verified both signed APKs use the existing release certificate, application ID and version code 232. Checked ZIP alignment, offline models, native libraries and the edition-specific plugin counts.

### Known limitations

- Phone colors require Android 12 or newer. Device checks used emulators.

### APK version codes

| Edition | Original APK code | Rebuilt APK code |
| --- | ---: | ---: |
| BCM | [232](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v3.0.0-dev.6/BCM-v3.0.0-dev.6.apk) | [2999906](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v3.0.0-dev.6/BCM-v3.0.0-dev.6-code2999906.apk) |
| BCM Plus | [232](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v3.0.0-dev.6/BCM-Plus-v3.0.0-dev.6.apk) | [2999906](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v3.0.0-dev.6/BCM-Plus-v3.0.0-dev.6-code2999906.apk) |

**Version code/name: 2999906 / 3.0.0-dev.6.**
