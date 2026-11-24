## v2.7.0 (2026-11-24)

# Workbook Sources, Browser Sign-In and Optional Lens OCR

The standard v2.7.0 APK uses the Play edition from the `google_play` branch. It matches covers against your Excel workbooks on device, shows where each saved workbook came from, and lets you choose how to read cover text.

### Added

- Import XLSX and XLSM workbooks from Google Drive. Google Drive and OneDrive import buttons show the linked account.
- View a workbook’s source account, path and link status, and relink its source when needed.
- Open App Website under More → Author, or read the Privacy Policy and Terms and Conditions offline.
- Open Plugins to import a supported descriptor. This standard build starts with an empty plugin list.

### Changed

- PaddleOCR reads cover text on device by default. Selecting Chrome Lens sends covers to Google for OCR through the built-in `chrome-lens-py` bridge. Play stores this choice separately from the GitHub edition.
- Google Drive and OneDrive use browser-based AppAuth sign-in. Imported workbooks stay available locally between sign-in sessions.
- The Play edition preserves existing workbook and bookmark storage and uses neutral bookmark examples.

### Fixed

- **Use it now** opens the workbook just imported, including when another workbook has the same name. **Rescan** targets the displayed workbook.
- Relinking checks source ownership and discards responses from a previous account selection.
- OneDrive distinguishes a file-delivery failure from an expired sign-in.

### Removed

- Public catalogue searches, provider shortcuts, DLsite, the plugin marketplace, and SauceNAO and IQDB discovery clients are excluded from the standard APK.
- Removed the proprietary Google Identity Services and Microsoft MSAL runtime authentication dependencies and the automatic debug-key fallback for release signing.

### Tests

- The Play JVM suite passed 1,271 tests and skipped 12, with zero failures or errors.
- On API 36, instrumentation loaded the embedded Chrome Lens library and confirmed the absence of the reverse-image clients and SauceNAO key store.

### Verified

- The Play debug build updated in place on the API 36 CachyOS emulator. The Plugins tab showed **Import plugin** and **No imported plugins**, with zero switches.
- The Play release APK contains the configured Google and Microsoft client IDs, the cover-matching model, and OCR libraries for arm64 and x86-64. All 164 packaged 64-bit ELF files passed the 16 KB page check, and APK ZIP alignment passed.

### Known limitations

- Cloud sign-in sessions are held in memory. Restarting BCM requires signing in again for cloud access. Background synchronization and cloud write-back are unavailable.
- The latest verification did not exercise live cloud sign-in, revocation, cross-account changes or a signed upgrade on a physical phone.
- Google OAuth restricted-scope review and Google Play submission are separate from GitHub distribution. The repository records outstanding authentication lifecycle and licensing reviews, including redistribution terms for the bundled OpenCLIP weights.

**Version code/name: 226 / 2.7.0.**
