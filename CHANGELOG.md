## v1.0.0 (2026-10-02)

# Camera capture, Excel cover matching and a local wishlist

The first public BookCover Matcher lets a reader photograph a cover, compare it with images in an Excel workbook and save the closest results on the device.

### Added

- Capture from the rear camera, use automatic scanning, or choose an existing image.
- Match workbook covers by perceptual image hashes, using OpenCV.js when available or a JavaScript discrete cosine transform fallback.
- Read images from Excel drawings and legacy comment fills. Save the selected results in the WebView wishlist.

### Changed

- None recorded.

### Fixed

- None recorded.

### Removed

- None recorded.

### Tests

- The original release log gives no automated test count.

### Verified

- The published APK declares the original application ID com.polo.bookcovermatcher, version 1.0.0 and code 2.

### Known limitations

- Floating pictures are scanned on the first worksheet. Legacy comment images use cell addresses without disambiguating worksheets.
- Rotation, perspective, blur and extreme lighting can affect perceptual matching. Clearing app data clears the WebView wishlist.

### APK version codes

| Edition | Original APK code | Rebuilt APK code |
| --- | ---: | ---: |
| Standard | [2](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v1.0.0/bookcovermatcher-v1.0.0.apk) | [1000000](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v1.0.0/bookcovermatcher-v1.0.0-code1000000.apk) |

**Version code/name: 1000000 / 1.0.0.**
