# Changelog

## v2.6.0-dev.1 (2026-11-09)

# Development Checkpoint: OneDrive Import, Rescan Version Check and Sign-In Redirect Repair

This is the v2.6.0 development checkpoint, not a stable release: the app still builds as version 2.5.0 / 221
and no stable `v2.6.0` tag exists. It adds OneDrive workbook import and an on-demand "is there a newer
version?" check when a workbook is rescanned. It also repairs the Microsoft sign-in handoff, which previously
left the browser on a blank page instead of returning to the app. Everything the offline app already does —
scanning, matching, comments, saved workbooks — is unchanged, and the workbook is always read from the app's
own storage: there is no synchronisation, no background refresh, no upload or write-back, and losing the
network can never make a saved workbook unopenable.

### Added

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

- Full JVM suite: **1,259 tests, 0 failures, 0 errors, 16 skipped, 148 classes**
  (`.\gradlew.bat :app:testDebugUnitTest --no-build-cache`, executed 2026-11-09). The 16 skips are
  environmental `assumeTrue` guards — 4 live-network catalogue tests needing `-DonlineCheck=1`, 3
  real-workbook tests and 9 sample-workbook tests whose fixtures live outside the repository — not disabled
  tests.
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
- The sign-in failure rules added here: `MsalFailureRulesTest` **8 passed**, covering cancellation, declined
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
- Debug APK (rebuilt after the fix): `app/build/outputs/apk/debug/app-debug.apk`, **344,758,949 bytes**, sha256
  `da20e33cf772da314a659bd1938896fb484ad83a838f7164a6e7d8f9bd65ed12`, `versionCode 221`, `versionName 2.5.0` —
  deliberately **not** bumped, because the licence and signing gates are still open.
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
  `ANR in com.bookcovermatcher`. The in-app "THIS BUILD → Version" row reads 2.5.0.
- **Not verified:** no Microsoft sign-in was completed in this environment, so MSAL actually processing the
  callback, obtaining an account and updating the UI remain unverified; there is no live Microsoft Graph call,
  no workbook import/open/rescan driven through the UI, no real `Retry-After` and no
  update-during-connection-loss scenario. The rendered OneDrive dialogs have still never been seen on a screen.

### Known limitations

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
- **Sign-in is repaired and now truthfully reported, but not proven end to end.** The blank-page cause and the
  offline sign-in hang are fixed, and a failure now says what actually went wrong instead of "try again when you
  are back online". No Microsoft sign-in was completed in this environment, though: MSAL processing the
  callback, the account appearing, the UI reaching its signed-in state and the loading state settling are all
  **not verified**, and the exact MSAL/Graph response behind the reported failure has not been observed. The
  next run on a device with an account will name it, and the message will point at the right thing to fix.
- **The stable version was deliberately not applied.** The app still builds as `2.5.0 / 221`; the proposed
  `2.6.0 / 222` needs a licence-clear dependency set and a real release keystore, and neither exists yet.
- **A very large workbook** is bounded only by free storage; there is no application-level size limit, and a
  multi-hundred-megabyte import has not been exercised.

**Version code/name: 221 / 2.5.0.**

