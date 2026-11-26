## v2.5.0-dev.1 (2026-11-05)

# Structured online queries and explainable catalogue matching

This development checkpoint introduces the query and matching logic for online discovery. The new logic is available in the core library ahead of its screen integration.

### Added

- Parse title, circle, author and product-code prefixes while preserving the original display text. Normalize identifier formatting and full-width Latin text for comparisons.
- Declare provider capabilities and skip unsupported query fields. Rank candidate matches using identifiers, available textual evidence and cover similarity. Keep ambiguity visible.

### Changed

- None recorded.

### Fixed

- None recorded.

### Removed

- None recorded.

### Tests

- OnlineSearchRefinementTest added 18 cases covering parsing, normalization, provider routing, identifier precedence, ambiguity and image evidence.

### Verified

- The published APK identifies as 2.5.0-dev.1, code 216. Its embedded source revision identifies the development checkpoint.

### Known limitations

- The unified search screen, side-by-side covers, collection controls and confirmation actions were still pending at this checkpoint.

### APK version codes

| Edition | Original APK code | Rebuilt APK code |
| --- | ---: | ---: |
| Standard | [216](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v2.5.0-dev.1/bookcovermatcher-v2.5.0-dev.1.apk) | [2049901](https://github.com/PoloTheFirst/BookCoverMatcher/releases/download/v2.5.0-dev.1/bookcovermatcher-v2.5.0-dev.1-code2049901.apk) |

**Version code/name: 2049901 / 2.5.0-dev.1.**
