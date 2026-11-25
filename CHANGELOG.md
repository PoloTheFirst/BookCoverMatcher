## v3.0.0-dev.8 (2026-11-26)

# Open the workbook viewer from Home

BCM and BCM Plus put the workbook viewer next to workbook selection. The online search settings now explain how Chrome Lens reads and translates a cover.

### Added

- Each Chrome Lens language field has a persistent hint with a two-letter example. For a Japanese cover, use `ja` as the printed language and `en` to request English. Leave the first field blank for automatic detection and the second blank to keep the original text.

### Changed

- On Home, tap **Workbook comments & images** below **Spreadsheet & range**. One compact row opens the existing viewer for comments, pictures, catalogue tables, insights and original-image export.
- The Author profile README opens expanded in More. Tap **Collapse** to use the shorter preview.
- The bundled Privacy Policy and Terms and Conditions now cover both dev.8 editions. Revision `2026-11-26.2` incorporates the website source’s edition-specific disclosures and clarifies when the Author profile refreshes.

### Fixed

- None.

### Removed

- Removed the outer glow from **Search selected platforms** and **Search by this cover** where those features are available.

### Tests

- BCM passed 1,271 JVM tests with 12 skipped. BCM Plus passed 1,444 with 16 skipped. Lint reported zero errors in both editions.
- Four Android 16 instrumented tests passed per edition, including opening the selected workbook from Home, reading both language hints, and collapsing and expanding the Author README.

### Verified

- Both signed APKs use the existing application ID, code 234 and the dev.7 release certificate. The packaged privacy and terms files match the canonical Markdown byte for byte.

### Known limitations

- The language hints apply to Chrome Lens. On-device PaddleOCR reads the cover in its original language.
- Verification used an emulator. Live provider searches, translation results and physical-device appearance were outside this test run.

**Version code/name: 234 / 3.0.0-dev.8.**
