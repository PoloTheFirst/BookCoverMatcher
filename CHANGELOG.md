## v2.7.0 (2026-11-18)

# Bundled plugins with live settings switches

This v2.7.0 framework checkpoint adds an in-process lifecycle for trusted bundled features. Debug editions demonstrate it with a switchable card.

### Added

- Added a Kotlin plugin API, registry validation, per-plugin activation and cleanup, and a Plugins screen that restores saved choices.

### Changed

- Toggling a compatible plugin updates the current activity immediately.

### Fixed

- None.

### Removed

- None.

### Tests

- The framework report recorded 1,434 passing GitHub JVM tests with 16 skipped and 1,325 passing Play tests with 12 skipped. Both debug lint tasks completed.

### Verified

- On an API 36 emulator, the demo toggled without a process restart and retained its switch state after relaunch. DEX inspection found the demo implementation only in debug APKs.

### Known limitations

- The framework checkpoint has an empty release registry. It does not distribute a standalone binary or download external plugin code.

**Version code/name: 226 / 2.7.0.**
