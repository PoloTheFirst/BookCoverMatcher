## v1.1.0 (2026-10-03)

# Online result preview after capture

BookCover Matcher adds an online-results panel beneath the five local matches. This release demonstrates how the results will appear with placeholder shopping links.

### Added

- Render up to five placeholder links and thumbnails between the local results and Wishlist.
- Run the same online preview after a manual scan, an image import or an automatic capture.

### Changed

- The WebView capture workflow now sends the selected thumbnail to the online preview module.

### Fixed

- None recorded.

### Removed

- None recorded.

### Tests

- The original release log gives no automated test or live-provider result.

### Verified

- The distributed APK embeds the source revision that adds the WebView search preview. Its manifest declares com.polo.bookcovermatcher, version 1.1.0-dev.1 and code 2.

### Known limitations

- The links are demonstration data. The public GitHub tag is v1.1.0, while the historical Android package reports 1.1.0-dev.1 and reuses code 2.

### APK version codes

| Edition | Original APK code | Rebuilt APK code |
| --- | ---: | ---: |
| Standard | [2](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v1.1.0/bookcovermatcher-v1.1.0.apk) | [1010000](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v1.1.0/bookcovermatcher-v1.1.0-code1010000.apk) |

**Version code/name: 1010000 / 1.1.0-dev.1.**
