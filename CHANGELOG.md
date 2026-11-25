## v2.7.0 (2026-11-18)

# A GitHub marketplace for optional BCM plugins

This v2.7.0 source checkpoint lets a GitHub-edition user import marketplace.bcmplugin once, then manage the supported groups from Plugins.

### Added

- The marketplace fetches descriptors for the compiled groups when enabled. Users can toggle or remove downloaded groups.
- Catalogue setup links to the import guide, which shows where to save and select the marketplace descriptor.

### Changed

- Disabling the marketplace pauses its groups and preserves their individual switch positions. Platform groups sort by numeric index and then by plugin ID.

### Fixed

- None.

### Removed

- None.

### Tests

- The marketplace report recorded 1,456 GitHub and 1,339 Play JVM tests, including 16 and 12 skips respectively, with zero failures. The initial marketplace implementation also passed five GitHub and one Play instrumented cases.

### Verified

- The source registry includes Ordinary Library Fetch, DLsite Fetch, Doujin Shop Shortcuts, Explicit Database Shortcuts and Reverse Image Discovery on the GitHub side. A descriptor activates one of those compiled implementations.

### Known limitations

- Descriptor downloads depend on GitHub availability. This branch is a source checkpoint and has no standalone APK release.

**Version code/name: 226 / 2.7.0.**
