## v2.5.0-dev.4 (2026-11-05)

# Pictures and collection actions on reverse-image results

SauceNAO and IQDB results can be dismissed, bookmarked or added to a wishlist. Profile-wide collection actions include reverse-image results.

### Added

- Show an available picture on a SauceNAO result. Add per-card bookmarking and wishlist actions and a bulk **Bookmark all discovery results** action.

### Changed

- Label the separate platform bulk action **Bookmark all platform results**. Include discovery cards when adding an entire result profile to a wishlist.

### Fixed

- Make SauceNAO results dismissible. Prevent a late image load from restoring a card that was already dismissed.

### Removed

- None recorded.

### Tests

- The historical report describes deterministic coverage for result images, dismissals and collection actions. It does not give a final suite count.

### Verified

- The public APK declares 2.5.0-dev.4, code 219. Its embedded source revision includes the final bulk-action naming change.

### Known limitations

- A result can lack an accessible image or the image provider can rate-limit requests. Dismissal applies to the current list. A new search can show the same result again.

**Version code/name: 219 / 2.5.0-dev.4.**
