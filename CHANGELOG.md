## v2.1.0 (2026-10-21)

# Read cover text and search Open Library

Online search is optional. The cover-reading step sends a selected image to Chrome Lens. The catalogue step uses extracted title or author text to search Open Library.

### Added

- Embed CPython and chrome-lens-py with Chaquopy. The Python bridge returns recognized text and block heights to Kotlin.
- Add editable search queries, structured Open Library results and separate OCR and catalogue controls.

### Changed

- Declare Internet access and validate JPEG input before handing it to the bridge. Use provider record keys to construct result links.

### Fixed

- Surface named bridge, OCR and encoding failures so an empty capture or malformed result can be diagnosed.

### Removed

- None recorded.

### Tests

- The historical bridge follow-up recorded 110 unit tests. The archival source rebuild passed 110 tests.

### Verified

- The archival APK was newly built from the historical v2.1.0 source. The Android build identifies as 2.1.0, code 201.

### Known limitations

- An original distributed APK could not be recovered. The rebuilt APK uses the current owner signing key. Android requires a compatible signing identity to update an existing installation.
- Seven historical ELF libraries fail 16 KB native alignment even though the APK passes ZIP alignment. The original testing did not verify a complete live workflow on a device.

### APK version codes

| Edition | Original APK code | Rebuilt APK code |
| --- | ---: | ---: |
| Standard | [201](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v2.1.0/bookcovermatcher-v2.1.0.apk) | [2010000](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v2.1.0/bookcovermatcher-v2.1.0-code2010000.apk) |

The original-code download is the earlier source rebuild described above.

**Version code/name: 2010000 / 2.1.0.**
