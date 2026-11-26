## v2.3.1 (2026-10-30)

# Bookmark card parity and a collapsible drawer

Wishlist bookmarks now use the Search results card presentation and keep their list position when switching tabs. Existing saved records use the same schema.

### Added

- None recorded.

### Changed

- Share bookmark presentation rules for thumbnails, match labels and fallback chips. Preserve the list state when navigating between tabs.

### Fixed

- Give the bookmarks drawer a recognizable handle and an expand/collapse animation. Show the price placeholder as 770円 and keep it out of saved data. Align Wishlist bookmark cards with the Search layout.

### Removed

- None recorded.

### Tests

- The historical JVM suite recorded 470 tests with zero failures and four skipped opt-in provider checks. Tests covered bookmark forms and presentation.

### Verified

- The public APK declares 2.3.1, code 208. The release reported compatibility with records saved by 2.3.0.

### Known limitations

- The original report had no device or emulator for checking the drawer animation and card layout.

### APK version codes

| Edition | Original APK code | Rebuilt APK code |
| --- | ---: | ---: |
| Standard | [208](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v2.3.1/bookcovermatcher-v2.3.1.apk) | [2030100](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v2.3.1/bookcovermatcher-v2.3.1-code2030100.apk) |

**Version code/name: 2030100 / 2.3.1.**
