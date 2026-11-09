## v2.6.0 (2026-11-09)

# BookCoverMatcher 2.6.0: Offline Licence Reader, Author Page and OneDrive Folder Access

This is the 2.6.0 build (`versionCode 223`). It adds a Markdown reader for the licence documents with a
full-screen reading mode, and an Author section that shows the owner's GitHub avatar, `PoloTheFirst`, `Polo01`
and a bundled snapshot of the `PoloTheFirst/PoloTheFirst` README. It also ships the OneDrive work that had been
in development: workbook import, the rescan version check, and the sign-in repair that finally made real OneDrive
folders list. Everything the offline app already does — scanning, matching, comments, saved workbooks — is
unchanged, and the workbook is always read from the app's own storage: there is no synchronisation, no background
refresh, no upload or write-back, and losing the network can never make a saved workbook unopenable.

**This entry documents an artefact that is not published.** Two stable-release gates are still open — the release
signing key and the `display-mask` licence — and both are listed under *Known limitations*. No stable tag was
created and `main` was not updated.

### Added

- **A Markdown reader for the licence documents.** The Licence page now renders what the APK ships instead of one
  block of plain text: `Markdown` parses headings, paragraphs and hard breaks, bold/italic/strikethrough, inline
  and fenced code, ordered, unordered and nested lists, links, blockquotes, tables and horizontal rules, and
  `MarkdownDocument` draws them in the app's own palette. The GNU GPL is *plain text*, not Markdown, and still
  displays correctly: a test parses the shipped `LICENSE` and asserts that no line's words are lost. Embedded HTML
  is never executed — a run of badge `<img>` tags becomes one line naming what was not displayed. **No dependency
  was added**: the reader is project code, so there is no new licence to audit.
- **Full-screen reading.** The reader opens at dialog size and can switch to a full-window view that maximises the
  text column, with an always-visible *Exit full screen* control. Back leaves full screen first and only then
  closes the reader. The chosen document and the scroll position live above the mode switch (`rememberSaveable`
  plus one hoisted `ScrollState`), so switching modes neither reloads the document nor jumps back to the top, and
  both survive rotation.
- **An Author section under the Licence page.** It shows the GitHub username `PoloTheFirst`, the nickname `Polo01`
  as its own labelled field, the profile picture and the profile address. Nothing is fetched: the picture and the
  README are packaged in the APK, so the section is complete with no network.
- **The profile README, bundled and rendered as Markdown.** A snapshot of the `PoloTheFirst/PoloTheFirst`
  `README.md` (5,038 bytes, retrieved 2026-11-09 from `raw.githubusercontent.com`) is rendered with the same reader
  and expands in place. Its multi-line HTML comment is stripped and its badge images are reported by alt text
  instead of being executed.
- **A working `Visit GitHub Profile` button.** It opens `https://github.com/PoloTheFirst` through the app's
  existing HTTPS-only external-link handler; a device with no browser gets a message, not a crash.
- **A safe OneDrive trace** (`RemoteDiagnostics`), because every earlier diagnosis of "folders do not open" had to
  be inferred: the app logged nothing at all. One line per operation records the stage (`signIn`, `token`,
  `list`, `probe`, `download`), the HTTP status, Graph's own `error.code`, Microsoft's `request-id` and the
  classified failure. The endpoint path is templated (`/items/{id}/children`) and the query string — which
  carries `$select`, page tokens and, on the content endpoint, a pre-authenticated storage URL — is dropped
  entirely. No header, token, body, account, folder or workbook name is ever recorded, the error body is read
  only up to 8 KiB and only for `code`/`request-id`, and a test asserts that a URL with a `tempauth` query logs
  nothing of it. The trace goes to logcat under the tag `OneDrive`.
- **Import from OneDrive** (`More → Saved workbooks → From OneDrive`). Sign in with a personal Microsoft
  account (public client, MSAL, `consumers` authority, exactly two delegated scopes: `Files.Read` and
  `User.Read`), browse your drive folder by folder with ↑ Up, a breadcrumb path and **Load more** pagination,
  and import a workbook. Only `.xlsx`/`.xlsm` are listed; folders are always shown. The download shows
  determinate progress when OneDrive reports a size and an indeterminate bar ("size unknown") when it does
  not, can be cancelled, and ends in *Use it now* / *Done*.
- **Remote check on Rescan.** For a workbook imported from OneDrive, **Rescan** still re-parses the local copy
  and additionally probes OneDrive once. The result is one of: *Up to date with OneDrive.*, a newer version
  (see below), *Could not tell whether the OneDrive copy changed…* when the metadata is not comparable, the
  agreed "no longer in OneDrive" sentence on a genuine `NOT_FOUND`, or the matching
  offline/timeout/throttled/auth sentence. A workbook that came from a file keeps the old behaviour exactly.
- **Confirmed, validated replacement.** A newer version is offered with the agreed wording ("A newer version
  is on OneDrive (<size>, <date>). Update the saved copy? The current copy stays until the new one is
  verified."). **Update** downloads to a temporary `.part`, validates it as a real OOXML workbook, swaps it in
  atomically, records the new version and re-opens the workbook under its **new** SHA-256. **Keep current**
  changes nothing but the check time. **Cancel** stops the download and deletes the temporary file.
- **Library schema 2** with optional remote metadata (`itemId`, `path`, `etag`, `ctag`, `modified`, `bytes`,
  `synced`, `checked`). A schema-1 library — including one with no `schema` key at all — is migrated in memory
  and rewritten once, keeping ids, names, files, pins, recency and parsed counts.
- **`ConnectivityMonitor`** (`core/net`) as the single answer to "may a remote operation start?", with a pure
  `UsableNetwork` rule (validated default network with `INTERNET`) and an injectable fake.
- **`SafeWorkbookDownload` + `WorkbookDownloadValidator`**: streams to `<itemId>.part`, validates extension,
  ZIP signature and OOXML parts, and deletes the temporary file on **every** failure path.
- **`GraphFailures`**: the HTTP status → `RemoteFailure` mapping (401/403/404/410/429/5xx, `Retry-After`
  parsing that tolerates absence and junk) as a pure, unit-tested object, plus the `ENOSPC`-versus-transport
  distinction.
- **Context-aware error wording** (`RemoteNotices`) and a Retry rule (`RemoteFailure.isRetryable()`): Retry is
  offered for a bad moment (offline, timeout, unstable, throttled, 5xx) and never for a state the user must
  change (sign in again, free space, pick another file, the remote file is gone).

### Changed

- **The build identifies itself as `2.6.0` (`versionCode 223`).** The 2.6.0 checkpoints shipped as `2.5.0 / 221`
  and then `2.6.0-dev.1 / 222`, which made a development build hard to tell apart from the released 2.5.0 app in
  the package metadata and on the More screen. The *THIS BUILD → Version* row now reads `2.6.0`, and `versionCode
  223` is above both earlier codes, so Android accepts this build as an upgrade. Stored data is untouched: the
  workbook library, wishlist, profiles and settings keep the same schema on disk.
- **The Licence page is now a reader plus an author section.** It keeps the summary lines and the single
  "Open-source licences" entry point, and gains the Author panel underneath. Licence documents are no longer
  shown as one plain-text blob; they open in the Markdown reader.
- **Rescan is routed through `MainViewModel.rescanOpenWorkbook()`** instead of calling
  `WorkbookCommentsController.reload()` from the screen. For local workbooks this is the same local reload,
  with no connectivity check and no account.
- **`WorkbookLibrary.writeAll` writes atomically** (temp file + `Files.move(ATOMIC_MOVE)`, with a backup and
  restore fallback) instead of delete-then-rename, closing a data-loss window in the index write.
- **`WorkbookLibrary.replaceFrom` recovers a lone backup before cleaning up**: if the workbook file
  is gone but `<file>.previous` survives, the backup is restored rather than deleted, so a recovery attempt
  can no longer destroy the only copy.
- **The OpenCLIP weights row in `README.md`** now states their declared licence (MIT,
  `cardData.license = "mit"`) instead of "no declared licence", matching `THIRD_PARTY_NOTICES.md` and the
  LAION model card.

### Fixed

- **No OneDrive folder could ever be listed, on any device, because the silent token request was built in a way
  MSAL rejects.** `accessToken()` called the *synchronous* `acquireTokenSilent(parameters)` while attaching a
  `SilentAuthenticationCallback`, and never supplied an authority. MSAL 8.6.0's `acquireTokenSilentInternal`
  opens with `if (getCallback() != null) throw new IllegalArgumentException("Do not provide callback for
  synchronous methods")`, then installs its own callback and calls
  `validateAcquireTokenSilentParameters`, which requires a non-null authority as well — and `forAccount(...)`
  does not set one. Either way the call threw, so a Graph access token was never obtained: sign-in appeared to
  work and every folder listing failed afterwards. Verified end to end against a real Microsoft account: the
  request was reduced to `.forAccount(account).fromAuthority(account.authority).withScopes(scopes)`, and the
  root, child-folder and content requests then answered **200**.
- **An unclassified sign-in failure was reported as a lost connection.** `AuthFailure.UNKNOWN` mapped to
  `RemoteFailure.Unstable`, whose sentence is *"try again when you are back online"*. MSAL's
  `IllegalArgumentException` from the bug above therefore reached the user as a network problem while the device
  was online — the misleading message this project has been chasing since crash-fix-4. An unattributed failure
  now has its own sentence (`RemoteFailure.AuthUnexpected`), which names neither the network nor Microsoft.
- **A sign-in whose browser never came back left the panel spinning forever.** MSAL answers through
  `BrowserTabActivity`; when the browser never redirects — a redirect-URI mismatch on the app registration, an
  error page, a tab the user simply left open — no callback is ever invoked, and the panel waited for one
  indefinitely with a disabled button and no message. That reads from the outside as "OneDrive folders do not
  open". The wait is now bounded (`MsalOneDriveAuth.INTERACTIVE_SIGN_IN_WINDOW_MS`, five minutes) and reports
  *"The OneDrive sign-in did not finish, so no folders were loaded. Sign in again; if the browser showed an
  error instead of returning to the app, the Azure app registration for this build needs checking."* Closing
  the panel cancels the wait immediately, so it is a backstop rather than the way out.
- **A failed OneDrive sign-in was reported as a lost connection.** Everything MSAL raised that was not a
  `MsalUiRequiredException` or an `IOException` was collapsed into `UNKNOWN`, and `UNKNOWN` renders as *"The
  connection to OneDrive failed, so nothing was saved. Try again when you are back online."* — so a cancelled
  page, a declined permission, an unregistered redirect or a rejected client all looked exactly like being
  offline. MSAL's exceptions are now classified through the pure, unit-tested `MsalFailureRules`
  (`MsalUserCancelException` → cancelled and deliberately silent, `MsalDeclinedScopeException` → a permission
  decision, `MsalClientException`/`MsalServiceException` → a rejected sign-in, with
  `DEVICE_NETWORK_NOT_AVAILABLE` the only client code treated as connectivity). A body the app cannot read, a
  3xx with no `Location`, and an unexpected exception during a transfer are no longer called "connection"
  failures either; they are `UnexpectedResponse`. Only `Offline` and `Unstable` now mention the network, and a
  test enforces that. A failed sign-in is also no longer invisible: `signIn` used to flatten every failure to
  "signed out", which the panel treats as cancellation, and the reason now travels back as `AuthState.Failed`
  with its own sentence and no misleading Retry.
- **Tapping Sign in with no network opened a browser onto an unreachable page.** The interactive sign-in had no
  connectivity preflight of its own, unlike the panel that hosts it: the app launched a Custom Tab at
  `login.microsoftonline.com`, MSAL could never deliver a callback, and the panel stayed in its signing-in
  state. The same preflight now runs before MSAL is started. Verified on the emulator with airplane mode on:
  the browser no longer opens, the offline sentence appears, and the app stays in the foreground.
- **Microsoft sign-in left the browser on a blank page instead of returning to the app.** The
  `com.microsoft.identity.client.BrowserTabActivity` intent filter declared the signature hash exactly as the
  Azure portal prints it, percent-encoded (`…%2B…%3D`), but Android compares a filter's literal `android:path`
  with the **decoded** `Uri.getPath()`. `msauth://com.bookcovermatcher/<hash>` therefore matched no activity:
  Chrome had nothing to hand the response to, showed a blank page, and the app never resumed — even though
  sign-in and consent had both succeeded. The path is now percent-decoded when the manifest placeholder is
  generated (`redirectParts`/`decodeManifestPath` in `app/build.gradle.kts`); the `redirect_uri` handed to MSAL
  is unchanged, so MSAL still receives exactly the redirect Azure registered.
  `MsalRedirectPath` (`core/remote`) is the single written rule, pinned by `MsalRedirectPathTest` (9 tests),
  including a test that a base64 `+` survives decoding instead of becoming a space. Verified on the emulator:
  before the change, `am start` with Azure's own encoded redirect resolved **no** activity; after it, the same
  intent resolves `com.microsoft.identity.client.BrowserTabActivity`, and the decoded form resolves too.
- **`@odata.nextLink` was appended to a built URL** instead of replacing it, producing a malformed request; a
  page token now replaces the URL and is validated as a trusted Graph API URL before use.
- **`acceptRemoteUpdate` could never run**: it was guarded by `canRescan`, which is false while the update
  question is on screen. It now checks only whether a probe or a download is in flight.
- **A cancelled check could leave the panel spinning**: every OneDrive job now settles its in-flight flags in
  a `finally`, guarded by a per-flow sequence number so a superseded job cannot clear a newer one's spinner.
- **The import panel offered Retry for failures retrying cannot fix** (a file that is not a workbook, a full
  device, a deleted remote file); Retry now follows `isRetryable()`.
- **`com.microsoft.identity:common4j` was marked "not separately verified"** in the notices; its POM declares
  MIT and it is now recorded as verified. The `display-mask` row was rewritten with the full evidence trail
  (POM, artifact, Maven Central, publisher feed, candidate repositories) instead of a bare claim.

### Removed

- None.

### Tests

- Run conditions: each suite was forced with `:app:cleanTestDebugUnitTest`, because Gradle otherwise reports the
  task `UP-TO-DATE` and repeats an old result. Figures are from 2026-11-09, after the sign-in redirect change.

- Full JVM suite: **1,295 tests, 0 failures, 0 errors, 16 skipped, 151 classes**
  (`.\gradlew.bat :app:testDebugUnitTest --no-build-cache`, executed 2026-11-09). The 16 skips are
  environmental `assumeTrue` guards — 4 live-network catalogue tests needing `-DonlineCheck=1`, 3
  real-workbook tests and 9 sample-workbook tests whose fixtures live outside the repository — not disabled
  tests.
- `MarkdownTest` **20 passed**: heading levels, paragraph soft-wrap, an explicit hard break, bold/italic/code/
  strikethrough, link targets, a `javascript:` link kept as text but refused, nested bullet and numbered levels,
  blockquotes, fenced code with a language, tables, horizontal rules, HTML badges reported by alt text, HTML
  wrappers, licence placeholders such as `<one line ...>` staying text, comments stripped, standalone and inline
  images, and unknown markup staying visible. Its `theShippedLicenseLosesNoText` case parses the real `LICENSE` and
  asserts that every line's words survive, which is the legal-accuracy guard for this feature.
- `AuthorProfileTest` **6 passed**: the exact username/nickname distinction, the profile URL, the HTTPS-only link
  rule (http, javascript, intent and file targets refused), link trimming, the avatar description, and that the
  bundled README (5,038 bytes) and avatar (15,848 bytes, real JPEG magic) exist and are not placeholders. Both new
  classes read files from the working directory behind an `assumeTrue` guard, and that guard did not fire, so they
  genuinely executed.
- Instrumented tests: **21 passed, 0 failed, 0 skipped** on `emulator-5554` (`Medium_Phone_API_37.0`, Android 17 /
  API 37) via `:app:compileDebugAndroidTestKotlin` then `:app:connectedDebugAndroidTest`. They cover on-device OCR
  only; nothing instrumented covers the licence reader, the author section or OneDrive. The first attempt reported
  0 tests because the emulator still held a differently-signed copy of the app and test packages; it ran properly
  only after uninstalling both, which is recorded rather than hidden.

- OneDrive/local regression classes added across Phases 1–7, with the number of tests in each:
  `OneDriveUpdateSessionTest` (25), `OneDriveSessionTest` (18), `RemoteFailureNoticesTest` (16),
  `GraphFailuresTest` (15), `WorkbookLibrarySchemaTest` (13), `WorkbookLibraryCrashRecoveryTest` (11),
  `OneDriveOfflineBehaviourTest` (11), `RemoteChangeTest` (11), `OneDriveNavigationTest` (10),
  `WorkbookLibraryReplaceTest` (10), `GraphUrlsTest` (10), `SafeWorkbookDownloadTest` (10),
  `ConnectivityMonitorTest` (10), `WorkbookLibraryTest` (9), `RemoteFailureMessageTest` (8), `GraphJsonTest`
  (8), `RemoteBrowsingTest` (8), `MsalConfigTest` (8), `WorkbookDownloadValidatorTest` (7),
  `OneDriveIntegrationInvariantsTest` (7), `OneDriveUiStateTest` (7), `RemoteUpdateUiTest` (7),
  `GraphRedirectTest` (5), `GraphWorkbookSourceAuthTest` (5), `OneDriveUseItNowTest` (4),
  `RemoteWorkbookFilterTest` (4), `LocalWorkbookOperationsTest` (3).
- The sign-in redirect rule added in the previous checkpoint: `MsalRedirectPathTest` **9 passed**, covering the
  percent-decoded intent-filter path, `+` not being turned into a space, MSAL's derived
  `msal<client-id>://auth` form, malformed and multi-byte escapes, and the packaged redirect end to end.
- The sign-in failure rules added in the previous checkpoint: `MsalFailureRulesTest` **8 passed**, covering
  cancellation, declined consent, a rejected client and service, the one MSAL client code that really is a
  network failure, and that a failed sign-in is reported while a cancelled one stays silent;
  `GraphWorkbookSourceAuthTest` **8 passed**, including that a rejected sign-in never becomes a connection
  failure and that only a genuine network failure still says "offline".
- The trace added in crash-fix-5: `RemoteDiagnosticsTest` **10 passed**, covering query stripping, the `{id}` placeholder,
  a pre-authenticated storage URL losing its `tempauth` value, unusable URLs, the line's fields, control-character
  injection and a sink that throws.
  consent, a rejected client and service, the one MSAL client code that really is a network failure, and that a
  failed sign-in is reported while a cancelled one stays silent; `GraphWorkbookSourceAuthTest` **8 passed**,
  including that a rejected sign-in never becomes a connection failure and that only a genuine network failure
  still says "offline".
- Instrumented tests: **compiled and executed** on `emulator-5554` (`Medium_Phone_API_37.0`, Android 17 /
  API 37, `sdk_gphone64_x86_64`), re-run on 2026-11-09 after the redirect change: `:app:compileDebugAndroidTestKotlin`
  then `:app:connectedDebugAndroidTest` → **21 tests, 0 failures, 0 errors, 0 skipped** across
  `NativeOcrDeviceTest` (10), `OcrDeviceTest` (3) and `OcrEngineRoutingTest` (8). These cover the on-device OCR
  paths only; there is no instrumented test for the OneDrive UI or for the sign-in callback.

### Verified

- **Real Microsoft account, real Graph, on the emulator** — the first time OneDrive folder access has been
  observed working. With a dedicated test account signed in through MSAL's own browser flow: the root children
  request answered **200** (two folders rendered), a folder opened (`/items/{id}/children` **200**, the file
  inside rendered with its size and modified time), *Up* returned to the root, an empty folder reported *"This
  folder has no .xlsx or .xlsm workbooks"* rather than an error, a 38.6 MB workbook downloaded
  (`/items/{id}/content` **200**) and was saved to the library, and the saved copy opened **with airplane mode on**
  with **zero** OneDrive log lines — the offline-first rule intact. Every Graph line was
  `status=200 … requestId=<guid>`; no token, account or file name was logged.
- `:app:compileDebugKotlin`, `:app:compileDebugAndroidTestKotlin` and `:app:assembleDebug` succeed.
- **Sign-in redirect handoff, verified on the emulator** (the fix in *Fixed*): the installed
  `BrowserTabActivity` filter now registers `Path: "PatternMatcher{LITERAL: /<signature-hash>}"` in its decoded
  form (base64 `+` and `=` present, no `%2B`/`%3D`), and `am start -a android.intent.action.VIEW -c
  android.intent.category.BROWSABLE -d "msauth://com.bookcovermatcher/<signature-hash>"` resolves
  `com.microsoft.identity.client.BrowserTabActivity` both for Azure's percent-encoded form and for the decoded
  form. Against the pre-fix build the same two intents failed with
  `Error: Activity not started, unable to resolve Intent` — which is exactly the browser side of the blank page
  the user saw. A double-encoded control URI resolved on the old build and no longer resolves on the new one,
  confirming the filter's literal is now the decoded path.
- **The same OneDrive chain re-run on this 2.6.0 build** (`versionCode 223`): sign-in completed, `/me/drive/root/children`
  **200**, the folder opened (`/items/{id}/children` **200**), a 38.6 MB workbook downloaded (`/items/{id}/content`
  **200**) and saved, and the saved copy opened with airplane mode on, producing **zero** OneDrive log lines.
- **Licence reader, on the emulator** (`emulator-5554`, Android 17 / API 37): the GPL and the third-party notices
  render as structured text — headings, joined paragraphs, and one of the notices' tables — and both documents still
  render with airplane mode enabled. Switching to full screen keeps the document *and* the reading position (the
  view stayed in the section it was left on), *Exit full screen* is always present, and Back leaves full screen
  first and closes the reader only on a second press. That Back behaviour was a defect found on the device and fixed
  before the final build, not a description of the first one.
- **Author section, on the emulator**: the bundled avatar decoded (the placeholder branch was not taken),
  `PoloTheFirst` and `Polo01` appear in two labelled fields, the bundled README rendered (its heading, the banner
  image row, and a line naming the badges that were not displayed), and *Visit GitHub Profile* opened Chrome — the
  running task's intent carried exactly `dat=https://github.com/PoloTheFirst`.
- **Packaging**: inside the built APK, `assets/author/README.md` (5,038 bytes), `assets/author/avatar.jpg`
  (15,848 bytes), `assets/licenses/GPL-3.0.txt` (35,149 bytes) and `assets/licenses/THIRD_PARTY_NOTICES.md`
  (17,736 bytes) are present; this was read from the archive, not assumed.
- **Release artefact**: `:app:assembleRelease` succeeds and produces a `223 / 2.6.0` APK, but `apksigner` reports it
  signed with `CN=Android Debug`. It is therefore installable and **not** a production release (see *Known
  limitations*).

- Debug APK (this build): `app/build/outputs/apk/debug/app-debug.apk`, **339,892,620 bytes**, sha256
  `cb0e0d108c74ecca10dbfe028361a0c8918d9328786703e86b74abf5aaf774b6`, `versionCode 223`,
  `versionName 2.6.0`, containing 28 native libraries across `arm64-v8a` and `x86_64` and 242 MB of assets (OCR and
  OpenCLIP models) — read from inside the archive, not assumed. Installed on the emulator, whose package manager
  reports `versionName=2.6.0`, and every device check above was run on **this** APK.
- **The request the app builds is accepted by the live service.** With a deliberately invalid bearer token, the
  exact root-children URL (`…/v1.0/me/drive/root/children?$select=…&$top=200`, the same `$select` and `$top`
  the app sends) answered **HTTP 401 `InvalidAuthenticationToken`**, not a 400: the endpoint, the query and the
  property names are valid at Microsoft's end. `GET /me/drive/root/children` and every `$select` field used here
  are also documented for this exact call, and `Files.Read` is the least-privileged delegated permission for a
  personal account.
- **Offline sign-in, verified on the emulator** (the fix in *Fixed*): with airplane mode on and the OneDrive
  panel open, tapping **Sign in** on the previous build launched Chrome at `login.microsoftonline.com`
  (`ERR_INTERNET_DISCONNECTED`) and the app never received a callback; on this build the browser does not open,
  `MainActivity` stays the resumed activity, and the panel shows *"No internet connection. OneDrive workbooks
  can only be imported online…"*.
- APK inspection: 733 entries, **no** `.env`; the packaged `res/raw/msal_config_default.json` carries
  `client_id` and **no** `client_secret`, and the `.env` `Directory_ID` value does not occur anywhere in the
  APK. Permissions: `CAMERA`, `INTERNET`, `ACCESS_NETWORK_STATE`, `NFC` and the generated
  `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. `DisplayMask` is present in `classes18.dex`/`classes19.dex`, so
  the unresolved artifact really is shipped.
- Signing state: the APK is signed with this machine's regenerated debug key (`CN=Android Debug`, SHA-1
  `ef2fb053…b611`, SHA-256 `169876f8…ce24`), and the MSAL redirect in `.env` carries the matching signature hash
  (the decoded form of that certificate's digest), i.e. the redirect agrees with the key actually in use. The
  `release` build type still uses `signingConfigs.getByName("debug")`.
- `python tools/check-licenses.py --refs --history` → **licensing audit passed**: canonical `LICENSE`
  (35,149 B, sha256 `3972dc9744f6…`), 378 project-owned sources carrying SPDX headers, every tag and every
  reachable commit clean.
- Device: the debug APK installs on `emulator-5554` (`Medium_Phone_API_37.0`, Android 17 / API 37,
  `sdk_gphone64_x86_64`), launches (`com.bookcovermatcher.MainActivity` becomes the resumed activity), renders
  Home and More, and does the same with airplane mode enabled; `logcat` contains no `FATAL EXCEPTION` and no
  `ANR in com.bookcovermatcher`. The in-app "THIS BUILD → Version" row reads `2.6.0`.
- **Still not verified:** a folder *inside* a folder, a listing long enough to paginate, a declined or failed
  consent, an expired token being refreshed mid-session, a real `Retry-After` from Graph, and an update check
  interrupted by losing the connection.

### Known limitations
- **Release signing is not production-ready, and this is the primary stable-release blocker.**
  `app/build.gradle.kts` still sets `signingConfig = signingConfigs.getByName("debug")`, so `assembleRelease`
  produces a `223 / 2.6.0` APK signed with the local debug key (`CN=Android Debug`, self-signed, created
  2026-11-08, living in `~/.android/`). It installs and was verified on the emulator, but it is not distributable:
  the key has no ownership or custody story, no backup, and it is the same key that signs debug builds. A real
  release keystore, kept outside the repository, is required before any store or public release.
- **`com.microsoft.device.display:display-mask:0.3.0` is still licence-unresolved — the second blocker.** It is a
  transitive runtime dependency of `com.microsoft.identity:common` and is packaged in the APK; its POM declares no
  licence and no authoritative statement was found in this environment. It has not been relabelled, and it has not
  been excluded, because excluding it has not been shown to be safe.
- **This build is therefore not published as stable.** No `v2.6.0` tag was created, `main` was not updated, and the
  work sits on `online_improvement`. The release decision is **NOT READY** for both reasons above.


- **Nested folder navigation and pagination are still unexercised against a real drive.** The test account's root
  holds two folders; one is empty and the other holds a single workbook with no subfolder, so neither the
  "folder inside a folder" case nor a second page was reachable. The `items/{id}/children` request itself — the
  path a nested folder uses — was verified with a real id, and pagination remains covered only by unit tests with
  recorded payloads.
- **The test account's password appeared in this emulator's accessibility dump** while an automated sign-in typed
  it, because Chrome exposes the field's value to `uiautomator`. Nothing was written to a tracked file and no
  credential is in this changelog, but that account's password should be rotated, and unattended sign-in should
  assume the field is readable by anything that can dump the screen.
- **Licence blocker:** `com.microsoft.device.display:display-mask:0.3.0` — a transitive runtime dependency of
  `com.microsoft.identity:common`, **packaged in the APK** — has no licence declaration in its POM, no licence
  file in its artifact, no Maven Central entry, and is not part of either Microsoft repository that could own
  it. v2.6.0 is therefore **not licence-clear** until the project owner decides (see
  `THIRD_PARTY_NOTICES.md` §1.1). A row-by-row pass over the ~200 shared AndroidX/Compose artifacts is also
  still outstanding; one Compose family was verified as the sample.
- **Signing blocker:** the `release` build type is signed with the **debug** key
  (`signingConfig = signingConfigs.getByName("debug")`), and no release keystore exists in this repository.
  A v2.6.0 binary produced today would therefore be signed with a throw-away debug key that was in fact
  regenerated on 2026-11-08 — it cannot be published or upgraded to. The owner must supply (or authorise the
  creation of) a real release keystore before a stable artifact is built.
- **Azure registration not verifiable here:** the redirect's signature hash matches the current debug
  certificate, but whether that hash is registered on the Azure app registration can only be checked in the
  Azure portal, and a real sign-in was never attempted.
- **Personal Microsoft accounts only**; work/school accounts are unsupported by construction.
- **No background synchronisation, no automatic refresh on open, no upload/write-back, no remote deletion.**
  Retry is manual: `Retry-After` is shown as a sentence, not enforced as a cool-down.
- **`MainViewModel`, the MSAL wrapper and the Compose UI have no executed tests** (the project has no
  Robolectric/Compose-test dependency); they are covered by inspection plus the pure/session-level tests they
  delegate to.
- **Persistence limitations A–E** (documented in `ARCHITECTURE.md` §"OneDrive"): stale metadata after a kill
  between the swap and the index write; an orphaned file cannot be re-imported; a listed entry whose file is
  missing cannot be repaired by re-import; no startup sweep of `.part`/`.previous`; no `fsync`. The recovery
  guard prevents deletion of the only copy but does not resolve these.
- **Sign-in and folder access are verified for one personal Microsoft account only.** Work or school accounts,
  conditional access, MFA-enforced sign-in and passkey enrolment were not exercised — the passkey prompt that
  appeared was dismissed, not completed — and no failure path (a declined consent, an expired token) was driven
  through the live service.
- **The version name is now `2.6.0`, which is an identity, not a release claim.** `versionCode 223` is set and the
  app reports `2.6.0`, but until the signing key and the `display-mask` licence are resolved this artefact must not
  be described as a published stable release, even though its feature work is complete and verified.
- **A very large workbook** is bounded only by free storage; there is no application-level size limit, and a
  multi-hundred-megabyte import has not been exercised.

**Version code/name: 223 / 2.6.0.**

