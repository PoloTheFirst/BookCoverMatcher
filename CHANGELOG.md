## v2.7.0 (2026-11-24)

# Reverse image discovery as a GitHub-edition plugin

This v2.7.0 source checkpoint moves SauceNAO and the IQDB browser handoff behind the Reverse Image Discovery switch. Chrome Lens remains a separate built-in OCR option.

### Added

- A GitHub user can import `reverse-image-discovery.bcmplugin` and toggle discovery in the running app. The group uses platform index 4.

### Changed

- Disabling discovery cancels its work and clears temporary results. Saved keys, workbooks and bookmarks remain available.

### Fixed

- None.

### Removed

- The Play variant excludes the SauceNAO and IQDB implementations.

### Tests

- The source adds Android instrumentation coverage for group registration and the Play boundary. This historical note correction included source inspection only.

### Verified

- The GitHub group registers the SauceNAO and IQDB adapters. The Play variant has its own discovery implementation and the descriptor cannot enable an absent adapter.

### Known limitations

- Live third-party search behavior and physical-device operation were outside this documentation audit. This branch has no standalone application release.

**Version code/name: 226 / 2.7.0.**
