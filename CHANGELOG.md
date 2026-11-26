## v2.0.0 (2026-10-06)

# Native Android cover matching with OpenCLIP

The native edition uses CameraX and an on-device OpenCLIP image encoder to rank workbook covers. GitHub offers full and lite APKs and the separate model file.

### Added

- Encode captured and workbook images as 512-dimensional vectors, then rank them by exact inner product after normalization. Cache workbook embeddings by workbook, range and model.
- Add in-app model setup, cancellation, tap-to-focus, auto-scan feedback and tooling for model export and benchmarking.

### Changed

- Replace the WebView and perceptual-hash workflow with Kotlin, Compose and ONNX Runtime. Similarity percentages are relative to the selected workbook.
- The application ID changes to com.bookcovermatcher. The native wishlist has its own storage.

### Fixed

- Correct the automatic scanner’s armed state so another scan can be triggered after the previous capture.

### Removed

- Remove the previous online preview, JavaScript library dependencies, CDN fallbacks and Internet permission.

### Tests

- The release record lists 67 JVM unit tests. The original authoring environment exercised the Kotlin core and compared its ranking with FAISS.

### Verified

- The full and lite public APKs both declare version 2.0.0, code 200 and package com.bookcovermatcher. The separate ONNX model is present as its own release asset.

### Known limitations

- The original testing did not measure accuracy on real covers using the final model weights. Similar volumes can receive similar scores. Devices need Android 8 or later and a supported ABI.

### APK version codes

| Edition | Original APK code | Rebuilt APK code |
| --- | ---: | ---: |
| Full | [200](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v2.0.0/bookcovermatcher-full-v2.0.0.apk) | [2000000](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v2.0.0/bookcovermatcher-full-v2.0.0-code2000000.apk) |
| Lite | [200](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v2.0.0/bookcovermatcher-lite-v2.0.0.apk) | [2000000](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v2.0.0/bookcovermatcher-lite-v2.0.0-code2000000.apk) |

**Version code/name: 2000000 / 2.0.0.**
