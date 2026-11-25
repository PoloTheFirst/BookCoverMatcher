## v2.5.0 (2026-11-08)

# Choose Chrome Lens or on-device PaddleOCR and retain profile images

Choose the recognition engine for each cover. The stable release also preserves a larger image for saved profiles and fixes feedback in the online cache and wishlist screens.

### Added

- Bundle the native PP-OCRv6 Medium detection and recognition models, their dictionary and the ONNX Runtime integration. Remember the selected engine and keep a failed request on the engine the user chose.

### Changed

- Save the profile image separately from its row thumbnail, preserve it across reloads and show it at the top of the opened profile.

### Fixed

- Restore readable cache-clear confirmation, improve the folder prompts in Search and Wishlist, and keep original profile images independent when deleting individual profiles.

### Removed

- None recorded.

### Tests

- The historical JVM suite recorded 981 tests, zero failures and seven skipped. Image storage and engine-routing tests covered isolation, persistence and failure behavior.

### Verified

- The release recorded Android emulator checks for the cache message, folder prompts and camera-captured profile display. The distributed APK declares 2.5.0, code 221.

### Known limitations

- A saved original is a re-encoded image rather than the original camera file. Existing profiles that only have a thumbnail cannot restore the larger image retroactively.

**Version code/name: 221 / 2.5.0.**
