## v3.0.0-dev.1 (2026-11-24)

# Material 3 Expressive for BookCoverMatcher

In this standalone GitHub build, import **marketplace.bcmplugin** from **Documents → BookCoverMatcher → Plugins** and enable **Plugin Marketplace**. It fetches the five available groups. Each group has its own switch and Remove action. Turning off the marketplace pauses its groups and preserves each switch selection.

### Added

- The design uses the Material 3 Expressive theme and shared settings rows.

### Changed

- Workbook, search, camera, wishlist, More and Plugins screens share updated typography, shapes and action styles.

### Fixed

- Large-text navigation keeps More and Plugins together. The Plugins heading uses the dark theme’s foreground color.

### Removed

- No user workflow was removed in this revision.

### Tests

- The GitHub debug JVM suite passed 1,441 tests and skipped 16.
- Four API 36 instrumentation tests passed, covering group activation and ordering, an Open Library search, OAuth browser request configuration and proprietary dependency isolation.

### Verified

- Android package inspection confirmed `com.bookcovermatcher`, version `3.0.0-dev.1` (227), minimum API 26 and target API 36.
- An in-place update from 2.7.0 retained the imported, enabled marketplace. Turning off the marketplace paused the enabled child and kept its switch checked. Re-enabling the marketplace resumed the group.

### Known limitations

- This local development build has not been published. Verification used a public Open Library query. Live Google and Microsoft sign-in and image uploads were outside the visual refresh.

**Version code/name: 227 / 3.0.0-dev.1.**
