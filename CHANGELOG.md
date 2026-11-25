## v2.2.1 (2026-10-23)

# DLsite results and Japanese shop shortcuts

The provider selector now distinguishes catalogues that return results inside the app from shops that open in the browser.

### Added

- Query DLsite’s public books search alongside Open Library and Google Books. Parse the work identifier, title, circle, current price when supplied, cover and destination link.
- Add Melonbooks and Suruga-ya as browser shortcuts.

### Changed

- Run supported providers concurrently with independent timeouts. Display which providers returned results and which are browser-only.

### Fixed

- None recorded.

### Removed

- None recorded.

### Tests

- The historical JVM report lists 184 tests and zero failures. It also records a separate opt-in run that exercised all three live provider checks.

### Verified

- The distributed APK declares 2.2.1, code 203. Its catalogue, author and price behavior is documented for the providers available at the time.

### Known limitations

- Melonbooks and Suruga-ya require the browser. DLsite integration covers the first page of its books search.

**Version code/name: 203 / 2.2.1.**
