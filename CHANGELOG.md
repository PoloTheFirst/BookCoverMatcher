## v3.0.0-dev.5 (2026-11-25)

# Clearer Online actions and matching controls

The matching button keeps its gradient with the glow removed. Expanded Online results keep the website arrow beside its label and show wishlist membership on the action itself.

### Added

- A **Wishlisted** action state for catalogue results saved in a wishlist profile. The action still opens the profile picker.

### Changed

- Result actions flow onto additional rows when space is tight. The website label and arrow share one button.
- Signed release builds produce `BCM-v3.0.0-dev.5.apk` and `BCM-Plus-v3.0.0-dev.5.apk`. The installation guide and release conventions use the renamed standard downloads.

### Fixed

- Saving a result to a wishlist updates the wishlist button independently of its bookmark state.

### Removed

- Removed the glow from **Link & Find Top 5**.

### Tests

- The Play JVM suite passed 1,271 tests with 12 skipped. The GitHub suite passed 1,444 with 16 skipped.
- An Android 16 emulator rendered and exercised the actual result card at 300 dp wide with 150% system font size. The test checked the website target, arrow position and independent wishlist and bookmark states.

### Verified

- Both release APKs were signed locally. Checked their filenames, package metadata, release certificate, ZIP alignment, native libraries, offline models and bundled plugin counts.

### Known limitations

- The layout check used an Android emulator. Provider availability depends on the installed edition.

### APK version codes

| Edition | Original APK code | Rebuilt APK code |
| --- | ---: | ---: |
| BCM | [231](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v3.0.0-dev.5/BCM-v3.0.0-dev.5.apk) | [2999905](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v3.0.0-dev.5/BCM-v3.0.0-dev.5-code2999905.apk) |
| BCM Plus | [231](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v3.0.0-dev.5/BCM-Plus-v3.0.0-dev.5.apk) | [2999905](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v3.0.0-dev.5/BCM-Plus-v3.0.0-dev.5-code2999905.apk) |

**Version code/name: 2999905 / 3.0.0-dev.5.**
