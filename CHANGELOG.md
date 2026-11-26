## v3.0.0-dev.2 (2026-11-24)

# BCM name and scanner refresh

The standard launcher and Home screen now show **BCM**. The published Plus launcher uses **BCM Plus**. The scanner uses the same Material 3 Expressive styling and Sophie colors as the rest of the app.

### Added

- A labeled **Auto-scan** toggle and stability progress below the viewfinder. **Sharp**, **Light** and **Steady** indicate current image quality.

### Changed

- The capture frame, focus ring and controls use the website’s rose and blush accents. **Follow system** selects the light or dark scheme, and **More → Display** lets you choose either one.
- The capture and restart actions adapt to narrow widths and larger text. The current app name comes from the same Android string on the launcher, Home and setup screens.

### Fixed

- Capture and Auto-scan wait for the preview to stream after camera startup or restart.

### Removed

- The fixed cyan scanner frame and the quality badges over the camera image have been replaced by the themed frame and feedback beneath the viewfinder.

### Tests

- Play debug and release APKs assembled. For the JVM tests, 1,271 passed, 12 skipped. Android lint reported zero errors, 61 warnings and one hint.
- BCM Plus debug and release APKs assembled. For the JVM tests, 1,444 passed, 16 skipped. Android lint reported zero errors, 63 warnings and one hint.

### Verified

- The initial builds used the application label **BCM**. The final Plus APK carries the **BCM Plus** label. The Android application ID remained `com.bookcovermatcher`. At 320 dp and 200% text on Android 8, the three scanner actions were reachable and each touch target was at least 48 dp high.
- Android 16 emulator checks covered the dev.1 → dev.2 Play update, both default scanner palettes, manual capture, Auto-scan and restart. The synthetic workbook, source records and wishlist matched their pre-update hashes.

### Known limitations

- Camera behavior was checked with a simulated feed. Physical-device focus and exposure remain unverified. The initial local release builds were unsigned. Both public APKs are signed.

### APK version codes

| Edition | Original APK code | Rebuilt APK code |
| --- | ---: | ---: |
| BCM | [228](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v3.0.0-dev.2/BCM-v3.0.0-dev.2.apk) | [2999902](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v3.0.0-dev.2/BCM-v3.0.0-dev.2-code2999902.apk) |
| BCM Plus | [228](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v3.0.0-dev.2/BCM-Plus-v3.0.0-dev.2.apk) | [2999902](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v3.0.0-dev.2/BCM-Plus-v3.0.0-dev.2-code2999902.apk) |

**Version code/name: 2999902 / 3.0.0-dev.2.**
