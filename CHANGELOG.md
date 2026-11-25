## v2.4.0-dev.3 (2026-10-31)

# Full worksheet tables and user-selected dates

Catalogue can now switch between cards and a worksheet table. The reader chooses the Published and Acquired date columns used by the timeline.

### Added

- Show every worksheet column in its original order with Excel letters and a fixed row number. Read Excel date serials in the date columns chosen by the user.

### Changed

- Use the explicit Published and Acquired selections for the timeline. Restore the gallery to the top when its order changes.

### Fixed

- Keep blank headers in their original positions, display wide worksheets without truncating their columns and make all four Insights section labels readable.

### Removed

- None recorded.

### Tests

- The historical run recorded 621 JVM tests with zero failures and seven skipped environmental checks.

### Verified

- The public APK declares 2.4.0-dev.3, code 211. The archive records tests for column positions and date mapping.

### Known limitations

- A timeline requires a configured column and usable dates. The app does not infer that a column is a date solely from its header.

**Version code/name: 211 / 2.4.0-dev.3.**
