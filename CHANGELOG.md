## v2.3.0 (2026-10-28)

# Read workbook notes, browse pictures and save workbook copies

BookCover Matcher opens the selected spreadsheet as a read-only ZIP package and displays its legacy Excel notes, their authors and associated pictures. Imported copies can be reopened without the original file.

### Added

- Add a worksheet-filtered comments viewer, an all-images inventory, full-screen previews and export of the original media bytes. Identify unresolved image references explicitly.
- Add Saved Workbooks, a searchable worksheet picker, catalogue cards and filters, and storage controls. Home and Search can use an imported workbook as their scan source.

### Changed

- Associate comments, catalogue rows and images only when the workbook provides a verifiable worksheet or cell relationship. Sort media filenames naturally and exclude pivot-table sheets from catalogue discovery.
- Improve the bookmark form and its cover, source and description fields.

### Fixed

- Repair opening a saved workbook, switching worksheets and filtering images by their catalogue associations.

### Removed

- None recorded.

### Tests

- The release notes record a JVM checkpoint of 352 tests with zero failures, including tests built from synthetic OOXML packages. Four opt-in provider tests were skipped.

### Verified

- The public APK declares 2.3.0, code 207. The original validation covered note and image relationships, filtering and read-only access.

### Known limitations

- Only legacy notes are displayed. Threaded comments can be detected but are not rendered. Encrypted workbooks are unsupported. The media list and individual image size are bounded. A two-workbook comparison overview was deferred.

**Version code/name: 207 / 2.3.0.**
