## v3.0.0-dev.1 (2026-11-24)

# Material 3 Expressive for BookCoverMatcher

The standard Play edition refreshes workbook, camera, search, wishlist, More and Plugins screens. It opens with an empty plugin host.

### Added

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

- The Play JVM suite passed 1,271 tests and skipped 12. Play lint reported zero errors, 61 warnings and one hint. Three API 36 instrumentation checks passed for the Play boundary, workbook navigation and source-grant handling.

### Verified

- A Play 226 → 227 update on API 36 retained the installation date and identical saved workbook, source metadata, wishlist and bookmark files. Plugins showed **Import plugin**, **No imported plugins** and zero switches.
- Play debug and release APKs contain zero plugin descriptors. Their DEX excludes the five provider group implementations, SauceNAO and IQDB clients, and SauceNAO key storage.
- On emulators running Android 8 and 16, Follow system tracked day/night and a manual selection survived a restart.

### Known limitations

- Local release-variant APKs are unsigned. Emulator upgrade checks used matching debug certificates. Physical-device release-key upgrades and live cloud sign-in were outside this development run.

**Version code/name: 227 / 3.0.0-dev.1.**
