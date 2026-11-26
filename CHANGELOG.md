## v2.2.3 (2026-10-25)

# Multiword DLsite queries and saved profile controls

This patch fixes the DLsite search encoding and restores two actions on saved profiles. The records schema remains version 3.

### Added

- None recorded.

### Changed

- None recorded.

### Fixed

- Encode spaces in a multiword DLsite keyword in the form accepted by its search endpoint.
- Restore renaming for result profiles and bookmarking for an individual result inside a profile.

### Removed

- None recorded.

### Tests

- The historical JVM suite recorded 285 tests, zero failures and four skipped opt-in checks.

### Verified

- The historical report compared the two encodings against the DLsite books search. The public APK declares 2.2.3, code 205.

### Known limitations

- DLsite may change its handling of requests. The historical test proves the behavior observed for this release.

### APK version codes

| Edition | Original APK code | Rebuilt APK code |
| --- | ---: | ---: |
| Standard | [205](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v2.2.3/bookcovermatcher-v2.2.3.apk) | [2020300](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v2.2.3/bookcovermatcher-v2.2.3-code2020300.apk) |

**Version code/name: 2020300 / 2.2.3.**
