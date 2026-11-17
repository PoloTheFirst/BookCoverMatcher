## Google Drive workbook import ? development prerelease

Version **2.7.0-dev.1 / 224** adds **From Google Drive** beside **From OneDrive** in Saved Workbooks. Browse My Drive folders and import existing `.xlsx`/`.xlsm` files as validated app-private copies; saved copies open/rescan offline.

Verified on the API 37 emulator with the owner's test account: official consent, folder navigation, both genuine workbook formats, restart/offline opening/rescan and early download cancellation. Final JVM: **1,393 passed, 0 failed, 16 skipped**. Existing instrumentation: **40 passed**; lint: **0 errors, 52 warnings**. Diagnostic error reporting was rebuilt/unit-tested and successful authorization retested on the emulator.

### APK and setup

The attached **debug-signed** APK is built from this release's source. SHA-256: `45984026f86d24599633205a9770ba0cdde88127357e9695f18cca7ce565bb89`.

Register Android package `com.bookcovermatcher` and debug signing SHA-1 `EF:2F:B0:53:4A:6D:52:0C:B5:58:C1:C3:62:BF:4F:4D:19:84:B6:11` in your Google Cloud project; enable Drive API and configure consent/test users and `drive.readonly`. See [setup guide](https://github.com/PoloTheFirst/BookCoverMatcher/blob/v2.7.0-dev.1/GOOGLE_DRIVE_SETUP.md). A differently signed APK needs its own Android OAuth client registration and cannot be updated in place with this debug key.

### Known limitations

- **Vivo X100 Pro:** owner-reported authorization failure remains under investigation. The locally exported signed release APK uses a different key; setup step 5A documents its registration. Successful emulator tests do not prove a phone fix.
- My Drive only: no Google Sheets conversion, shortcuts, shared-drive browser, Google remote refresh/sync, upload, write-back or remote deletion.
- `drive.readonly` gives account-wide read access; the app's Excel filter is not an extension-limited OAuth permission.
- This GitHub prerelease does not grant Google OAuth production approval. Restricted-scope verification, branding/legal pages, proprietary Google SDK/GPL compatibility, existing display-mask licensing and production signing remain release considerations.
- Sixteen JVM tests skipped, 52 lint warnings, and the pre-existing Back-label clipping at 150% font scale remain disclosed.

See [CHANGELOG](https://github.com/PoloTheFirst/BookCoverMatcher/blob/v2.7.0-dev.1/CHANGELOG.md) and [agent report](https://github.com/PoloTheFirst/BookCoverMatcher/blob/v2.7.0-dev.1/historical_prompt/v2.7.0-dev.1-ag.md) for details. Google production-readiness work is planned for dev.2, which is not included here.
