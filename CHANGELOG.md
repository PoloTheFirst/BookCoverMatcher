## v2.2.4 (2026-10-25)

# Toranoana and browser links that follow the current query

Browser-only shop cards now open with the search text currently on screen, including when the user has yet to run an in-app search.

### Added

- Add Toranoana as a browser-only shop card.

### Changed

- None recorded.

### Fixed

- Rebuild platform links when the query changes so browser shortcuts no longer reuse a previous keyword.

### Removed

- None recorded.

### Tests

- The historical suite recorded 295 unit tests, zero failures and four skipped opt-in checks.

### Verified

- The public APK declares 2.2.4, code 206. The original checks exercised the Toranoana destination and query propagation.

### Known limitations

- Toranoana search and product pages open in the browser. This version does not query an in-app Toranoana API or resolve a single product URL.

**Version code/name: 206 / 2.2.4.**
