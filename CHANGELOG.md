## v3.0.0-dev.1 (2026-11-24)

# Material 3 Expressive for BookCoverMatcher

The explicit GitHub edition includes Plugin Marketplace and five enabled groups on a fresh install, including offline. Existing selections and removals are respected on upgrade.

### Added

- The explicit edition validates the six bundled descriptors on-device. Use **Restore** to bring back a removed group without downloading its descriptor.
- Shared Material 3 Expressive settings rows show each available action or switch with its label and state.

### Changed

- **More → Display** offers Follow system, Light and Dark. The app follows the phone by default and remembers a manual choice. The interface uses the Atelier website’s Sophie colors with a serif Home title and the book artwork’s cyan and teal glow on the scanner.
- Workbook rows group the source path and link state beneath the title. Search, wishlist and reader controls use shared typography, shapes and action styles.
- Bottom navigation adapts to narrow windows and enlarged text, keeping **More** beside **Plugins**.

### Fixed

- Long action labels can wrap. Short document windows let the header scroll with the text. Section headings inherit a readable foreground on dark surfaces.

### Removed

- Replaced the fixed single-row navigation layout on compact screens with adaptive rows.

### Tests

- The GitHub JVM suite passed 1,444 tests and skipped 16. The Play flavor on this branch passed 1,271 and skipped 12. GitHub lint reported zero errors, 63 warnings and one hint.

### Verified

- A fresh, offline API 26 installation showed six enabled entries. Disabling the marketplace preserved child switches. Removed groups stayed removed across restart and switching editions.
- A debug-signed explicit → Play → explicit update preserved the pinned workbook, source metadata, wishlist and bookmark records.
- On emulators running Android 8 and 16, Follow system tracked day/night and a manual selection survived a restart.

### Known limitations

- Local release-variant APKs are unsigned. Emulator upgrade checks used matching debug certificates. Physical-device release-key upgrades and live cloud sign-in were outside this development run.

**Version code/name: 227 / 3.0.0-dev.1.**
