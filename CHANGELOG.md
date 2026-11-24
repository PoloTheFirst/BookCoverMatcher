## v2.7.0 (2026-11-17)

# Workbook Sources, Browser Sign-In and App Website

This local v2.7.0 build consolidates the dev.2 workbook-source and policy work and adds the app website beneath the author-profile link. The owner-signed APK has been installed as an update on Vivo. External publication and Google approval remain pending.

### Added

- Plugins now accepts manually selected `.bcmplugin` files. GitHub platform plugins now group ordinary libraries, DLsite, doujin-shop shortcuts and database shortcuts in that order. Each group has a live switch and Doujinshi.info follows the active groups. Fresh installs and upgrades that used the implicit provider default start off. First import honors an explicitly saved DLsite selection, and subsequent launches restore the plugin switch. Both debug editions support the demo card.

- Separate GitHub and Play build variants: GitHub retains all providers; Play excludes SauceNAO, IQDB, public catalogue discovery and shortcuts.

- App Website in More > Author, opening the BookCoverMatcher homepage.
- Offline Privacy Policy and Terms and Conditions, with canonical Markdown packaged in the app.
- Workbook source account/status/location, explicit relinking and account labels on cloud-import buttons.
- Original-derived OAuth logo, master artwork and Play branding assets with provenance.

### Changed

- Play uses neutral bookmark-form examples and selectable Chrome Lens or on-device PaddleOCR, and preserves existing catalogue bookmarks as unavailable entries without deleting their stored data. Both editions retain the same package identity, storage and release-signing setup.

- Version code/name: 226 / 2.7.0; compile/target API36.
- Direct Google Drive and OneDrive imports use AppAuth browser sign-in with process-memory sessions.
- Policies apply to v2.7.0: privacy revision 2026-11-17.2 adds the owner-approved Google Limited Use commitment; terms remains 2026-11-17.1. Source metadata and retained selected-document access remain disclosed.
- Native dependencies and Python wheels include the dev.2 16 KB packaging improvements.

### Fixed

- Use it now opens the exact imported workbook, including duplicates; Rescan targets the displayed workbook.
- Source checks preserve account ownership and reject stale account-switch results.
- OneDrive recovery distinguishes storage-delivery failures from genuine expired authorization.
- Removed two source-file byte-order marks and excluded IDE metadata from the source licence check.

### Removed

- Proprietary GIS/MSAL runtime authentication dependencies; Google direct browsing is retained.
- Automatic debug-key fallback for release signing.

### Tests

- Distribution split: GitHub 1430 JVM tests passed/16 skipped; Play 1321 passed/12 skipped; zero failures/errors. Both debug/release and instrumentation APK builds passed; lint has zero errors and 58 warnings per edition. Instrumentation execution and signed cross-edition updates remain unverified. Earlier device evidence below predates this split.

- 1429 JVM tests passed, 16 skipped, zero failures/errors; debug and test APK builds passed.
- Lint: zero errors, 58 warnings. Source licence checker passes.
- API36 emulator: 17 navigation, authentication-dependency and native/OCR regression cases passed on code226.
- Both APK variants: 164 ELF64 entries checked with zero 16 KB failures; ZIP alignment passes.

### Verified

- App Website is readable on the emulator at normal/150% fonts, opens the correct URL and handles a missing browser.
- Owner-signed code226 APK matches the Vivo installation's certificate and installs as an update without uninstalling. App Website appears beneath the profile link and launches the correct Firefox URL.
- The regenerated owner-signed APK contains canonical privacy .2 and terms .1 byte-for-byte. API36 emulator checks passed for launch, policy tabs, fullscreen/Back/Close, App Website routing and cold restart. Branding hashes and dimensions match their inventory.

### Known limitations

- This is a local signed build, not a published GitHub release or Play submission. The publication guard remains.
- Live cloud sign-in, grant revocation and workbook changes were excluded by the owner from this verification. Expiry/refresh, interrupted authorization and live cross-account/changed-content checks remain.
- Website policy publication is complete per owner handback; fresh plain-URL browser checks returned HTTP200 with privacy .2 and terms .1, effective 17 November 2026. The regenerated signed Android artifact now matches privacy .2; Vivo has not been updated to this regenerated artifact.
- Discovery-provider distribution review, OAuth restricted-scope approval and remaining release review are not established by build/test success. The Play build requires review of Lens data disclosures and saved external links.
- Physical arm64 camera/OCR/model end-to-end tests are not claimed. Intermittent USB disconnection interrupted the additional Vivo Back/policy-reader check.
- Google/Microsoft sessions remain memory-only; restart requires sign-in. Cloud write-back and background synchronization are not implemented.

**Version code/name: 226 / 2.7.0.**
