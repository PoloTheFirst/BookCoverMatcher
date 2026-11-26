## v2.4.0-dev.1 (2026-10-30)

# Open an image or catalogue record at its source cell

The workbook viewer can open an image or record at the worksheet cell that the workbook identifies. The surrounding cells help the reader verify what an image belongs to.

### Added

- Add **Go to source** to associated images, full-screen images, note detail and catalogue records. Show neighboring worksheet rows, Excel column letters, the selected cell and a header when available.

### Changed

- Treat missing or ambiguous relationships as unresolved so the viewer only offers source locations supported by workbook evidence.

### Fixed

- None recorded.

### Removed

- None recorded.

### Tests

- The historical suite recorded 487 JVM tests with zero failures. Four opt-in provider checks were skipped. Seventeen new tests covered source-location handling.

### Verified

- The public APK declares 2.4.0-dev.1, code 209. Source lookup reads the selected saved copy.

### Known limitations

- Images without a verified worksheet relationship cannot open at a cell. Workbook access remains read-only.

### APK version codes

| Edition | Original APK code | Rebuilt APK code |
| --- | ---: | ---: |
| Standard | [209](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v2.4.0-dev.1/bookcovermatcher-v2.4.0-dev.1.apk) | [2039901](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v2.4.0-dev.1/bookcovermatcher-v2.4.0-dev.1-code2039901.apk) |

**Version code/name: 2039901 / 2.4.0-dev.1.**
