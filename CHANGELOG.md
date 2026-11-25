## v2.7.0-dev.1 (2026-11-10)

# Development Release: Google Drive Workbook Import

This development checkpoint adds Google Drive to Saved Workbooks alongside OneDrive and local import. Official Google authorization, My Drive browsing, both supported Excel formats, and offline opening/rescanning of saved copies were verified on the emulator using the owner's test account. Two unreliable test-fixture assumptions were corrected, and the final full JVM suite passed; public-production OAuth verification and existing distribution/licensing limitations remain separate from this development acceptance.

### Added

- **From Google Drive** immediately beside **From OneDrive**, with a separate My Drive browser, folder breadcrumbs, Up navigation, pagination, loading/error states, download progress, cancellation, and Use it now/Done results.
- **Official Google authorization and account selection** using AuthorizationClient and `drive.readonly`, requested only from the import action. The OAuth permission permits account-wide Drive reading; the application restricts imports to existing `.xlsx` and `.xlsm` files, not the permission itself.
- **Validated app-private copies.** Downloads stream through the existing safe downloader and OOXML validator. Native Google Sheets, shortcuts, trashed files and explicitly download-restricted files are excluded. Saved copies open and rescan locally without a Google session.
- **Google Cloud setup walkthrough** in `GOOGLE_DRIVE_SETUP.md`, with exact Android package/signing fingerprint, consent/test-user steps, scope selection, checkpoints and troubleshooting.

### Changed

- **Import layout.** Local Import has its own row; OneDrive and Google Drive remain adjacent underneath with multiline labels, verified at 150% font scale.
- **Origin persistence.** New Google entries atomically record `google_drive` without account identifiers, access tokens or remote file IDs. Duplicate bytes preserve the existing entry/provider. Local and OneDrive defaults remain compatible; Google entries cannot enter OneDrive rescan/update logic.
- **Google SDK.** Direct `play-services-auth:22.0.0` upgrades the older auth version already brought in transitively by MSAL. Publisher POM declarations and resolved Google runtime versions are recorded in `THIRD_PARTY_NOTICES.md`.
- **Version identity:** `2.7.0-dev.1 / 224`, incremented once from `2.6.0 / 223`.

### Fixed

- **Google authorization error reporting.** Non-success Android consent results are now parsed for Google status instead of all being labelled cancellation/denial. Missing results are identified separately; safe numeric error codes are shown and logged without tokens/account data. The separate Vivo X100 Pro incident was subsequently resolved by using a registered test account, as confirmed by the owner.

- **Unreliable test ZIP hashes.** TestWorkbooks now fixes ZIP entry timestamps, so identical fixture content yields identical bytes across separate calls instead of changing with the clock.
- **Host-dependent failed-write fixture.** The batch failure test now blocks the temporary output path with a directory, rather than assuming a read-only file cannot be deleted/replaced on Windows. Its original assertions about failure reporting and unchanged stored data remain intact. Application persistence behavior was not changed by these fixture repairs.

### Removed

- None.

### Tests

- Final full JVM suite: **1,393 passed, 0 failed, 16 skipped** (1,409 total), using `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug --console=plain`.
- New Google import/navigation/transport tests: **13 passed, 0 failed, 0 skipped**, included in the full suite.
- Existing instrumentation: **40 passed, 0 failed, 0 skipped** on Android 17/API 37; these tests cover existing OCR/author behavior, not live Google authorization.
- Kotlin and instrumentation compilation and debug APK assembly succeeded. Lint completed with **0 errors, 52 warnings**. Earlier failed full-suite runs are preserved in the agent report; the final green result follows the two fixture corrections above.

### Verified

- **Vivo X100 Pro - owner-confirmed resolution.** The owner identified that the selected account was not a registered OAuth test user. Signing in with a testing account resolved the failure. This is an owner-reported device result, not a new agent-run phone test; the earlier signing-certificate mismatch hypothesis was not established as its cause.

- Official Google account selection/consent, My Drive root listing, entering a folder and navigating Up succeeded on `Medium_Phone_API_37.0`, `emulator-5554`.
- Genuine owner-supplied `.xlsx` (**40,545,649 bytes**) and `.xlsm` (**40,494,234 bytes**) downloaded and reached Saved Workbooks. Both stored-file SHA-256 values matched the library index; both carried `google_drive` origin, no remote/account metadata, and left no partial files.
- After force-stopping the app, disabling networking and relaunching, both workbooks opened and rescanned successfully from local storage. The `.xlsx` parsed 8 sheets, 999 comments and 165 images; `.xlsm` parsed 8 sheets, 125 comments and 165 images. Macro execution was not enabled.
- Cancelling another real download returned to the browser, preserved the two existing entries and their hashes, and left no partial file. Offline sign-in and authorization cancellation messages were also exercised.
- Installed APK metadata: **224 / 2.7.0-dev.1**; signature verified. Redacted UI evidence, library summaries, test results and dependency audit are under `BookCoverMatcher-QA/v2.7.0-dev.1/`; the paired report records exact details.

### Known limitations


- Initial scope is My Drive. Shared drives, Shared with me, shortcuts, Google Sheets conversion, background synchronization, Google remote update checking, upload/write-back and remote deletion are excluded.
- Live tests exercised root and one child folder; deep nesting and pagination are covered by deterministic tests rather than a large live Drive fixture. Live OneDrive import/update UI regression and agent-run physical-device testing were not repeated; the Vivo resolution above is owner-confirmed.
- Public production approval is not established. `drive.readonly` is a restricted scope, the existing release configuration uses debug signing, and proprietary Google SDK/GPL redistribution compatibility and the existing `display-mask` licence issue remain unresolved.
- The final JVM suite has 16 skipped tests; opt-in online provider tests were not enabled. Lint still reports 52 warnings.
- The pre-existing fixed-width Back label clips at 150% font scale; both cloud import labels fit.
- This is a GitHub development prerelease. Publishing it does not change Google OAuth production/verification status. The attached APK is the matching debug-signed build; the user-exported release APK uses a different key and is not this artifact.

**Version code/name: 224 / 2.7.0-dev.1.**
