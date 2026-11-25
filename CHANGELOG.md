## v2.7.0 (2026-11-18)

# Doujin Shop Shortcuts in the GitHub edition

This v2.7.0 source checkpoint provides the standalone Doujin Shop Shortcuts group. Import `doujin-shop.bcmplugin` from Plugins in a matching GitHub build, then use its switch.

### Added

- Opens browser searches for Melonbooks, Suruga-ya and Toranoana in that order.

### Changed

- The group uses platform index 2. Activating or disabling it updates the available platforms in the running app.

### Fixed

- None.

### Removed

- Other platform groups are excluded from this standalone registration.

### Tests

- The platform-group report records successful GitHub debug assembly and unit testing for each independent group. Its combined-build emulator tests covered manual descriptor import, ordering and switch persistence.

### Verified

- The branch contains the Doujin Shop Shortcuts implementation and its matching two-line descriptor. The group audit found exactly one expected concrete group in each independent GitHub registry.

### Known limitations

- Descriptors activate Kotlin code compiled into a compatible APK. The branch has no separate application release or public binary of its own. External service availability depends on the provider.

**Version code/name: 226 / 2.7.0.**
