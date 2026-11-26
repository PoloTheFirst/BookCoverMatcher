## v2.2.0 (2026-10-22)

# Catalogue search, folders and saved scan sessions

Open Library and Google Books return structured catalogue results. Saving a scan places its comparison results in a session inside a folder the reader chooses.

### Added

- Run the two catalogue providers and report their results separately. Surface workbook, online and bookmarked results in their own areas.

### Changed

- Group offline saves by session and show bookmarked results first inside the session. Keep bookmark and folder membership independent.

### Fixed

- Connect the online controller state to the screen so the persisted switch actually enables the feature. Correct OCR block-height alignment for multiline text and handle instrumentation contexts without an application context.

### Removed

- None recorded.

### Tests

- The historical report recorded 165 JVM tests, zero failures, and three passing emulator tests. The archival rebuild passed 163 JVM tests and skipped two.

### Verified

- The newly rebuilt APK uses the historical Android identity 2.2.0, code 202.

### Known limitations

- The original release has no recoverable original APK. The archival build uses the current owner signing key, which can prevent an in-place update of an installation signed with an older key.
- Seven historical ELF libraries fail 16 KB native alignment even though ZIP alignment passes. Catalogue results depend on provider coverage and availability.

### APK version codes

| Edition | Original APK code | Rebuilt APK code |
| --- | ---: | ---: |
| Standard | [202](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v2.2.0/bookcovermatcher-v2.2.0.apk) | [2020000](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v2.2.0/bookcovermatcher-v2.2.0-code2020000.apk) |

The original-code download is the earlier source rebuild described above.

**Version code/name: 2020000 / 2.2.0.**
