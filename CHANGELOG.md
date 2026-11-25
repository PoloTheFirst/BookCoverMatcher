## v2.5.0-dev.2 (2026-11-05)

# Optional reverse-image discovery and persisted candidate decisions

The online workflow now combines a structured catalogue query, optional SauceNAO reverse-image search and a browser-assisted IQDB workflow.

### Added

- Send a chosen cover to SauceNAO using its documented API. Show similarity, provider details, errors and quota outcomes. Store a user-supplied API key using Android Keystore-backed encryption.
- Accept manually supplied IQDB results. Keep sources separate, cache covers within fixed bounds and persist confirmations or rejections separately from wishlist and workbook records.

### Changed

- Wire the previous query and capability logic into online discovery. Let users decide whether to include a secondary source.

### Fixed

- Repair the initialization order that could crash OnlineSearchController at application startup.

### Removed

- None recorded.

### Tests

- The historical deterministic suite recorded 869 tests across 106 classes, zero failures and seven skipped, including 61 new protocol, storage, routing and cache tests.

### Verified

- The published APK declares 2.5.0-dev.2, code 217. Its embedded source checkpoint includes the startup repair.

### Known limitations

- IQDB requires manual browser interaction. Live SauceNAO was not verified in this release’s original test run. A subsequent development release fixed persistence of the key.

**Version code/name: 217 / 2.5.0-dev.2.**
