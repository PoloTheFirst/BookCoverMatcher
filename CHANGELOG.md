## v2.2.2 (2026-10-23)

# Selectable platforms, editable queries and batch collection actions

Choose the platforms used for an online search, open each platform with the current query and manage multiple saved items in one operation.

### Added

- Add platform cards, selected-state indicators and per-provider limits of 5, 10, 20, 30 or 50 results. Add catalogue destinations including Doujin no Mori, Doujinshi.info, MyAnimeList and nhentai.
- Show the selected image filename, make recognized text selectable and add batch selection, copy, move and removal actions.

### Changed

- Route searches only to enabled in-app providers. Report browser-only destinations and distinguish them from an empty response or a provider failure. Preserve independent bookmark and folder membership.

### Fixed

- Restrict blocked or unsupported providers to browser-assisted use. Apply batch changes to the visible selection and report a write failure without reporting a successful change.

### Removed

- None recorded.

### Tests

- The historical regression suite recorded 259 unit tests with zero failures. Separate opt-in checks exercised Open Library, Google Books and DLsite.

### Verified

- The distributed APK declares 2.2.2, code 204. The original checks covered query encoding, result counts, and selection and membership rules.

### Known limitations

- Provider support differs between in-app lookup and browser links. Visual reverse-image search was not available. The original release did not exercise the final batch UI on a device.

### APK version codes

| Edition | Original APK code | Rebuilt APK code |
| --- | ---: | ---: |
| Standard | [204](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v2.2.2/bookcovermatcher-v2.2.2.apk) | [2020200](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v2.2.2/bookcovermatcher-v2.2.2-code2020200.apk) |

**Version code/name: 2020200 / 2.2.2.**
