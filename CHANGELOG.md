## v3.0.0-dev.7 (2026-11-26)

# The launcher book follows the phone’s light and dark modes

BCM and BCM Plus now refresh their full-color launcher icon when Android delivers a light/dark change and when the app starts. The book uses the cream tile in light mode and the rose tile in dark mode.

### Added

- None.

### Changed

- The adaptive launcher icon has separate light and dark resources. Both include the book silhouette for launchers that support themed icons.

### Fixed

- A launcher could keep displaying the previous book colors after a system appearance change. BCM now requests a refresh of the existing launcher entry, preserving its component name and home-screen shortcut.

### Removed

- None.

### Tests

- BCM passed 1,271 JVM tests with 12 skipped. BCM Plus passed 1,444 with 16 skipped. Lint reported zero errors in both editions.
- Four Android 16 instrumented tests passed in each edition. They covered both launcher colorways, the single original launcher entry, and the independent in-app Display settings.

### Verified

- Reproduced the stale cream icon on dev.6. On the Android 16 Pixel Launcher, inspected the rendered app-drawer icons after switching to dark and back to light in both dev.7 editions. A pinned BCM Plus shortcut changed colors in its original home-screen position.
- Force-stopped BCM Plus, changed the system appearance, then opened the app through its existing icon. Returning to the launcher showed the new colors.
- Both signed APKs use version code 233, the existing application ID and the dev.6 release certificate. On Android 8, the signed upgrade from dev.6 BCM to dev.7 BCM and then BCM Plus preserved the first-install timestamp. Both dev.7 editions launched through the original component in both system modes.

### Known limitations

- A launcher can retain its cached icon while the app is stopped. Opening BCM once after the mode change requests a refresh. Launcher themes and icon packs may supply their own colors.
- The reported Vivo X100 Pro was unavailable for direct verification.

### APK version codes

| Edition | Original APK code | Rebuilt APK code |
| --- | ---: | ---: |
| BCM | [233](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v3.0.0-dev.7/BCM-v3.0.0-dev.7.apk) | [2999907](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v3.0.0-dev.7/BCM-v3.0.0-dev.7-code2999907.apk) |
| BCM Plus | [233](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v3.0.0-dev.7/BCM-Plus-v3.0.0-dev.7.apk) | [2999907](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v3.0.0-dev.7/BCM-Plus-v3.0.0-dev.7-code2999907.apk) |

**Version code/name: 2999907 / 3.0.0-dev.7.**
