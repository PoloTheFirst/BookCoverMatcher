# Changelog

## v2.6.0 — 2026-11-08

# OneDrive import, and a check for newer versions — prepared, not yet released

This entry was written in Phase 8 and re-verified in Phase 9 of the v2.6.0 plan. The work it describes is
implemented and development-verified in this tree; the release itself has **not** happened and the version is
still **2.5.0 / 221**. Phase 9 deliberately **withheld** the bump to 2.6.0 / 222 because two hard gates are
still open — the unresolved `display-mask` licence and the absence of a release keystore (see *Known
limitations*). What Phase 9 did publish is the development checkpoint: branch `online_improvement` and the
annotated tag `v2.6.0-dev.1`. No stable `v2.6.0` tag, GitHub release, store upload or binary distribution was
made.

v2.6.0 adds one feature and changes nothing about the offline app. You can import an `.xlsx`/`.xlsm`
workbook from your own OneDrive, keep using it exactly like any other saved workbook, and — only when you
press **Rescan** — let the app check whether OneDrive has a newer version and offer to replace the copy. The
workbook is always read from the app's own storage: there is no synchronisation, no background refresh, no
upload or write-back, and losing the network can never make a saved workbook unopenable.

### Added

* **Import from OneDrive** (`More → Saved workbooks → From OneDrive`). Sign in with a personal Microsoft
  account (public client, MSAL, `consumers` authority, exactly two delegated scopes: `Files.Read` and
  `User.Read`), browse your drive folder by folder with ↑ Up, a breadcrumb path and **Load more** pagination,
  and import a workbook. Only `.xlsx`/`.xlsm` are listed; folders are always shown. The download shows
  determinate progress when OneDrive reports a size and an indeterminate bar ("size unknown") when it does
  not, can be cancelled, and ends in *Use it now* / *Done*.
* **Remote check on Rescan.** For a workbook imported from OneDrive, **Rescan** still re-parses the local copy
  and additionally probes OneDrive once. The result is one of: *Up to date with OneDrive.*, a newer version
  (see below), *Could not tell whether the OneDrive copy changed…* when the metadata is not comparable, the
  agreed "no longer in OneDrive" sentence on a genuine `NOT_FOUND`, or the matching
  offline/timeout/throttled/auth sentence. A workbook that came from a file keeps the old behaviour exactly.
* **Confirmed, validated replacement.** A newer version is offered with the agreed wording ("A newer version
  is on OneDrive (<size>, <date>). Update the saved copy? The current copy stays until the new one is
  verified."). **Update** downloads to a temporary `.part`, validates it as a real OOXML workbook, swaps it in
  atomically, records the new version and re-opens the workbook under its **new** SHA-256. **Keep current**
  changes nothing but the check time. **Cancel** stops the download and deletes the temporary file.
* **Library schema 2** with optional remote metadata (`itemId`, `path`, `etag`, `ctag`, `modified`, `bytes`,
  `synced`, `checked`). A schema-1 library — including one with no `schema` key at all — is migrated in memory
  and rewritten once, keeping ids, names, files, pins, recency and parsed counts.
* **`ConnectivityMonitor`** (`core/net`) as the single answer to "may a remote operation start?", with a pure
  `UsableNetwork` rule (validated default network with `INTERNET`) and an injectable fake.
* **`SafeWorkbookDownload` + `WorkbookDownloadValidator`**: streams to `<itemId>.part`, validates extension,
  ZIP signature and OOXML parts, and deletes the temporary file on **every** failure path.
* **`GraphFailures`**: the HTTP status → `RemoteFailure` mapping (401/403/404/410/429/5xx, `Retry-After`
  parsing that tolerates absence and junk) as a pure, unit-tested object, plus the `ENOSPC`-versus-transport
  distinction.
* **Context-aware error wording** (`RemoteNotices`) and a Retry rule (`RemoteFailure.isRetryable()`): Retry is
  offered for a bad moment (offline, timeout, unstable, throttled, 5xx) and never for a state the user must
  change (sign in again, free space, pick another file, the remote file is gone).

### Changed

* **Rescan is routed through `MainViewModel.rescanOpenWorkbook()`** instead of calling
  `WorkbookCommentsController.reload()` from the screen. For local workbooks this is the same local reload,
  with no connectivity check and no account.
* **`WorkbookLibrary.writeAll` writes atomically** (temp file + `Files.move(ATOMIC_MOVE)`, with a backup and
  restore fallback) instead of delete-then-rename, closing a data-loss window in the index write.
* **`WorkbookLibrary.replaceFrom` recovers a lone backup before cleaning up** (Phase 7): if the workbook file
  is gone but `<file>.previous` survives, the backup is restored rather than deleted, so a recovery attempt
  can no longer destroy the only copy.
* **The OpenCLIP weights row in `README.md`** now states their declared licence (MIT,
  `cardData.license = "mit"`) instead of "no declared licence", matching `THIRD_PARTY_NOTICES.md` and the
  LAION model card.

### Fixed

* **`@odata.nextLink` was appended to a built URL** instead of replacing it, producing a malformed request; a
  page token now replaces the URL and is validated as a trusted Graph API URL before use.
* **`acceptRemoteUpdate` could never run**: it was guarded by `canRescan`, which is false while the update
  question is on screen. It now checks only whether a probe or a download is in flight.
* **A cancelled check could leave the panel spinning**: every OneDrive job now settles its in-flight flags in
  a `finally`, guarded by a per-flow sequence number so a superseded job cannot clear a newer one's spinner.
* **The import panel offered Retry for failures retrying cannot fix** (a file that is not a workbook, a full
  device, a deleted remote file); Retry now follows `isRetryable()`.
* **`com.microsoft.identity:common4j` was marked "not separately verified"** in the notices; its POM declares
  MIT and it is now recorded as verified. The `display-mask` row was rewritten with the full evidence trail
  (POM, artifact, Maven Central, publisher feed, candidate repositories) instead of a bare claim.

### Removed

* None.

### Tests

Phase 9 figures for this checkout (2026-11-08). The suite was re-executed rather than re-reported: the task
was forced with `:app:cleanTestDebugUnitTest` because Gradle reported it `UP-TO-DATE` from the earlier run.

* Full JVM suite: **1,238 tests, 0 failures, 0 errors, 16 skipped, 146 classes**
  (`.\gradlew.bat :app:testDebugUnitTest --no-build-cache`, executed 2026-11-08). The 16 skips are
  environmental `assumeTrue` guards — 4 live-network catalogue tests needing `-DonlineCheck=1`, 3
  real-workbook tests and 9 sample-workbook tests whose fixtures live outside the repository — not disabled
  tests.
* OneDrive/local regression classes added across Phases 1–7, with the number of tests in each:
  `OneDriveUpdateSessionTest` (25), `OneDriveSessionTest` (18), `RemoteFailureNoticesTest` (16),
  `GraphFailuresTest` (15), `WorkbookLibrarySchemaTest` (13), `WorkbookLibraryCrashRecoveryTest` (11),
  `OneDriveOfflineBehaviourTest` (11), `RemoteChangeTest` (11), `OneDriveNavigationTest` (10),
  `WorkbookLibraryReplaceTest` (10), `GraphUrlsTest` (10), `SafeWorkbookDownloadTest` (10),
  `ConnectivityMonitorTest` (10), `WorkbookLibraryTest` (9), `RemoteFailureMessageTest` (8), `GraphJsonTest`
  (8), `RemoteBrowsingTest` (8), `MsalConfigTest` (8), `WorkbookDownloadValidatorTest` (7),
  `OneDriveIntegrationInvariantsTest` (7), `OneDriveUiStateTest` (7), `RemoteUpdateUiTest` (7),
  `GraphRedirectTest` (5), `GraphWorkbookSourceAuthTest` (5), `OneDriveUseItNowTest` (4),
  `RemoteWorkbookFilterTest` (4), `LocalWorkbookOperationsTest` (3).
* Instrumented tests: **compiled and executed** on `emulator-5554` (`Medium_Phone_API_37.0`, Android 17 /
  API 37, `sdk_gphone64_x86_64`): `:app:compileDebugAndroidTestKotlin` then `:app:connectedDebugAndroidTest`
  → **21 tests, 0 failures, 0 errors, 0 skipped** across `NativeOcrDeviceTest` (10), `OcrDeviceTest` (3) and
  `OcrEngineRoutingTest` (8). These cover the on-device OCR paths only; there is no instrumented test for the
  OneDrive UI.

### Verified

* `:app:compileDebugKotlin`, `:app:compileDebugAndroidTestKotlin` and `:app:assembleDebug` succeed in Phase 9.
* Debug APK: `app/build/outputs/apk/debug/app-debug.apk`, **344,758,949 bytes**, sha256
  `05248cf698b9e684bd36369593a5be6b1626cb41bd26deaa…` (full digest in the Phase 9 report), `versionCode 221`,
  `versionName 2.5.0` — deliberately **not** bumped, because the licence and signing gates are still open.
* APK inspection: 733 entries, **no** `.env`; the packaged `res/raw/msal_config_default.json` carries
  `client_id` and **no** `client_secret`, and the `.env` `Directory_ID` value does not occur anywhere in the
  APK. Permissions: `CAMERA`, `INTERNET`, `ACCESS_NETWORK_STATE`, `NFC` and the generated
  `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. `DisplayMask` is present in `classes18.dex`/`classes19.dex`, so
  the unresolved artifact really is shipped.
* Signing state: the APK is signed with this machine's regenerated debug key (`CN=Android Debug`, SHA-1
  `ef2fb053…b611`, SHA-256 `169876f8…ce24`), and the MSAL redirect in `.env` carries the matching signature
  hash (`7y+wU0ptUgy1WMHDYr9PTRmEthE=`), i.e. the redirect agrees with the key actually in use. The `release`
  build type still uses `signingConfigs.getByName("debug")`.
* `python tools/check-licenses.py --refs --history` → **licensing audit passed**: canonical `LICENSE`
  (35,149 B, sha256 `3972dc9744f6…`), 378 project-owned sources carrying SPDX headers, every tag and every
  reachable commit clean, 132 commits across 25 refs. (It failed first on a stray `p9-ui.xml` UI dump in the
  repository root; that file was moved to `BookCoverMatcher-QA/phase9-evidence/` — see the Phase 9 report.)
* Device, Phase 9: the debug APK was installed on `emulator-5554` (`Medium_Phone_API_37.0`, Android 17 /
  API 37, `sdk_gphone64_x86_64`), launched (`com.bookcovermatcher.MainActivity` became the resumed activity),
  rendered Home and More, and did the same with airplane mode enabled; `logcat` contains no
  `FATAL EXCEPTION` and no `ANR in com.bookcovermatcher`. The in-app "THIS BUILD → Version" row reads 2.5.0.
* **Not verified:** no Microsoft sign-in, no live Microsoft Graph request, no workbook import/open/rescan
  driven through the UI (no account and no fixture workbook in this environment), no real `Retry-After`, no
  `msauth://` round trip and no update-during-connection-loss scenario. The rendered OneDrive dialogs have
  still never been seen on a screen.

### Known limitations

* **Licence blocker:** `com.microsoft.device.display:display-mask:0.3.0` — a transitive runtime dependency of
  `com.microsoft.identity:common`, **packaged in the APK** — has no licence declaration in its POM, no licence
  file in its artifact, no Maven Central entry, and is not part of either Microsoft repository that could own
  it. v2.6.0 is therefore **not licence-clear** until the project owner decides (see
  `THIRD_PARTY_NOTICES.md` §1.1). A row-by-row pass over the ~200 shared AndroidX/Compose artifacts is also
  still outstanding; one Compose family was verified as the sample.
* **Signing blocker:** the `release` build type is signed with the **debug** key
  (`signingConfig = signingConfigs.getByName("debug")`), and no release keystore exists in this repository.
  A v2.6.0 binary produced today would therefore be signed with a throw-away debug key that was in fact
  regenerated on 2026-11-08 — it cannot be published or upgraded to. The owner must supply (or authorise the
  creation of) a real release keystore before a stable artifact is built.
* **Azure registration not verifiable here:** the redirect's signature hash matches the current debug
  certificate, but whether that hash is registered on the Azure app registration can only be checked in the
  Azure portal, and a real sign-in was never attempted.
* **Personal Microsoft accounts only**; work/school accounts are unsupported by construction.
* **No background synchronisation, no automatic refresh on open, no upload/write-back, no remote deletion.**
  Retry is manual: `Retry-After` is shown as a sentence, not enforced as a cool-down.
* **`MainViewModel`, the MSAL wrapper and the Compose UI have no executed tests** (the project has no
  Robolectric/Compose-test dependency); they are covered by inspection plus the pure/session-level tests they
  delegate to.
* **Persistence limitations A–E** (documented in `ARCHITECTURE.md` §"OneDrive" and
  `historical_prompt/v2.6.0-dev.1-ag.md` §24.10): stale metadata after a kill between the swap and the index
  write; an orphaned file cannot be re-imported; a listed entry whose file is missing cannot be repaired by
  re-import; no startup sweep of `.part`/`.previous`; no `fsync`. The Phase 7 guard prevents deletion of the
  only copy but does not resolve these.
* **A very large workbook** is bounded only by free storage; there is no application-level size limit, and a
  multi-hundred-megabyte import has not been exercised.

**Version code/name: 221 / 2.5.0 — unchanged.** The proposed target for the v2.6.0 release remains
`222 / 2.6.0`, but Phase 9 did not apply it: a stable release needs a licence-clear dependency set (see the
`display-mask` blocker) and a real release keystore, and neither exists yet. Bumping the version without them
would have produced a distributable-looking artifact that cannot legally or practically be released.

## v2.5.0 — 2026-11-08



**Version code/name: 221 / 2.5.0.** The first stable release of the 2.5 line. It carries two OCR engines
(Chrome Lens and the native PP-OCRv6 medium engine, both selectable per READ THE COVER request), and this
release fixes four user-reported defects on top of that work. No feature was redesigned and no parsing,
matching or catalogue rule was changed.

### Added

* **Two OCR engines, chosen per request.** *READ THE COVER* can run through Chrome Lens (the embedded
  Python bridge) or through the native `PP-OCRv6` medium engine (`native/ocr`: Rust core plus the
  shipped ONNX Runtime), the choice is remembered, and a request never falls back to the other engine
  silently — `OcrEngineRoutingTest` pins the routing, the persistence across controller recreation and
  the absence of a fallback.
* **The PP-OCRv6 medium models ship inside the APK** (`assets/ocr/ppocrv6/`: detector 62,032,837 B,
  recogniser 76,554,979 B, dictionary 74,947 B), stored uncompressed and re-hashed out of the built
  archive, so the native engine works offline on a clean install.

### Fixed

* **The cache-clear confirmation is readable again.** *More → Clear online session cache* printed a
  mangled em dash between "cleared" and "saved": the Kotlin source had been read as Windows-1252 and
  written back as UTF-8, so the em dash (U+2014) survived as three double-encoded characters. The text is
  now `Session cache cleared — saved bookmarks and wishlist items are untouched.`, the sentence lives in
  `SessionCacheMessages.CLEARED`, and the same repair was applied to every other occurrence of the defect
  in `app/src` and `ONLINE_SEARCH.md` (109 lines across 4 files). A unit test pins the exact sentence,
  refuses the damaged characters and scans the packaged sources for the double-encoding signature so it
  cannot come back quietly.
* **A saved result profile keeps its original picture.** A profile used to keep only the 44×58 row
  thumbnail — and an online result profile kept no image at all — so opening a profile inside its folder
  showed no picture. The original captured or uploaded image is now JPEG-encoded at its own resolution
  (quality 92; `Config.MAX_IMAGE_DECODE` still bounds a picked photo) and stored as its own file in
  `originals/`, referenced by a nullable `originalImage` field, written atomically (temp file + rename)
  and deleted with the profile only when that profile owns it. Copying a folder duplicates the image
  rather than sharing one file. Profiles saved before this release have no reference and say so instead
  of showing a thumbnail in the original's place.
* **The opened profile shows that picture at the top.** The result-profile detail view inside a wishlist
  folder now draws the original image above the saved results and their metadata, aspect ratio preserved
  (`ContentScale.Fit`, never cropped into a tile), height capped at 240 dp, and tappable to open it full
  size. Folder and session rows keep their existing thumbnails.
* **Search → Add entire result profile** placeholder is `e.g. ブルアカ` (was `e.g. Doujinshi`).
* **Wishlist → Create folder** placeholder is `e.g. ブルアカ` (the `e.g. ` prefix had been lost). Both
  dialogs now read the same `BookmarkForm.FOLDER_NAME_PLACEHOLDER`, so they cannot drift apart again.

### Tests

* `OriginalProfileImageTest` (8 tests): the original is its own file with its own bytes, separate from
  the thumbnail; it survives a reopened repository; two profiles never share one; deleting a profile
  removes exactly its own files; a profile without an image still loads; a `records.json` written before
  the field loads; a storage failure leaves the library readable and records nothing; a copied folder
  gets its own copy of the file.
* `SessionCacheMessageTest` (5 tests): the exact sentence, no damaged characters, the packaged sources
  carry no double-encoded text, and the folder placeholder carries its `e.g. ` prefix.
* `BookmarkFormTest` updated for the new placeholder text.

### Verified

* Full JVM suite: **981 tests, 0 failures, 7 skipped** (see `README.md` §7 for the exact command).
* Emulator `Medium_Phone_API_37.0` (Android 17, x86_64): cache-clear text, both folder placeholders, and a
  camera-captured profile showing its original image at the top of its detail view, still there after a
  process restart. See `historical_prompt/v2.5.0-ag.md` for the scenario-by-scenario record.
* Release APK built with the packaged PP-OCRv6 assets and both ABIs; contents, version and signature
  reported in the same document.

### Known limitations

* The pipeline holds decoded pixels, not the camera's own file, so a saved original is a re-encoded JPEG
  rather than the untouched original file. A picked photo is bounded by `Config.MAX_IMAGE_DECODE`.
* Profiles saved before v2.5.0 have no original image; one cannot be recovered from a thumbnail, and the
  detail view says so instead of inventing one.

---

Older releases (`2.5.0-dev.*`, `2.4.0` and earlier) are recorded in the Git tags, the GitHub Releases
and the release commits themselves; this file describes the current stable release only.
