## v2.4.0-dev.2 (2026-10-30)

# Workbook health, gallery, analytics and timeline

The Insights area turns the parsed workbook catalogue into reports and a browsable collection. A single catalogue supplies the health checks, records and counts.

### Added

- Check for unreadable sheets, missing fields, invalid dates or URLs, duplicates and unresolved image references.
- Add a searchable gallery, aggregate counts, a timeline and field mapping for workbook columns.

### Changed

- Let the reader inspect the affected source record or worksheet alongside the report whenever a verified location is available.

### Fixed

- None recorded.

### Removed

- None recorded.

### Tests

- The historical JVM run recorded 574 tests with zero failures, including 87 new mapping and Insights tests. Four opt-in provider checks were skipped.

### Verified

- The public APK declares 2.4.0-dev.2, code 210. Reports operate on the parsed catalogue without editing the workbook.

### Known limitations

- Reports depend on what can be parsed or mapped from the selected workbook. Missing or ambiguous data can remain unresolved.

**Version code/name: 210 / 2.4.0-dev.2.**
