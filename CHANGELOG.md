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

**Version code/name: 205 / 2.2.3.**
