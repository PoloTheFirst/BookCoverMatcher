## v2.4.0 (2026-11-05)

# Worksheet-scoped Insights, persistent field maps and linked catalogue cells

The stable 2.4 release brings workbook source navigation, catalogue tables and Insights together. Readers can scope reports by worksheet, save mappings and follow an evidenced source cell.

### Added

- Add separate worksheet filters for Health, Analytics and Gallery. Remember timeline year selection and support the workbook’s own ordering column.
- Open a catalogue cell to inspect its content, follow a supported link or locate the source. Save separate field mappings per workbook and worksheet.

### Changed

- Refresh Insights in place after rescanning. Display Excel dates and percentages consistently. Prefer manual mappings over inferred fields and distinguish saved maps from detected mappings.

### Fixed

- Warn when an empty row stops catalogue discovery. Correct the default matching range for the documented layout and prevent unrelated numeric values from being interpreted as dates.
- Keep image-to-record filtering functional with formulas and fix worksheet selection, order and formatting regressions.

### Removed

- Remove the global Sheets control in favor of report-specific scope. Remove Cover first from the gallery ordering choices.

### Tests

- The historical release-candidate checkpoint recorded 790 tests with no failures and seven skipped. The 2026 archive rebuild ran the restored source suite with 766 passing, 17 failing and seven skipped. Nine failures involved the historical sample workbook, seven involved parser fixtures and one involved POSIX read-only file behavior.

### Verified

- The rebuilt stable APK uses 2.4.0, code 215. The earlier release attachment identified as a different development version. Its verified original belongs to the 2.5.0-dev.3 release.

### Known limitations

- The original signed stable 2.4.0 APK could not be recovered. The archive rebuild uses the current owner signing key, which can prevent in-place updates of older installations. Seven historical ELF libraries fail 16 KB native alignment even though the APK passes ZIP alignment.
- The rebuilt test suite still has the 17 failures recorded above. Worksheet source and image links require a relationship the workbook actually supplies.

**Version code/name: 215 / 2.4.0.**
