# Scope every report by worksheet, follow the workbook's own order, read dates as dates

**v2.4.0 is a stable release.** The Insights gallery can now be limited to **any set of worksheets** â€”
all of them, one, or several â€” and that selection composes with its search, cover and missing-data
filters. Every worksheet selection and every timeline year range is **remembered per workbook**, when two
records share a date the timeline now follows the workbook's own **Default order** column, the rarely used
**Imported** timeline is gone (Timeline keeps **Published** and **Acquired**), and in *Catalogue â†’ Table* a
date-formatted cell is shown as a date (`2026-10-31`, or `2026-10-31 14:30`) with a percentage rounded to
**two decimals at most** (`12.35%`). One display rule now caps every measurement at two decimals, while the
stored values are left exactly as the workbook wrote them.

The **Source location** preview reads its cells with that same rule and is now tappable like the table â€” every
value, link and copy describes the **original worksheet cell**, not the position on screen â€” and the
**Default order** column can be changed from *Insights â†’ Fields* with a real button and is remembered per
workbook.

## v2.5.0 (2026-11-08)

**Version code/name: 221 / 2.5.0.** The first stable release of the 2.5 line. It carries two OCR engines
(Chrome Lens and the native PP-OCRv6 medium engine, both selectable per READ THE COVER request), and this
release fixes four user-reported defects on top of that work. No feature was redesigned and no parsing,
matching or catalogue rule was changed.

### Fixed

* **The cache-clear confirmation is readable again.** *More â†’ Clear online session cache* showed
  `Session cache cleared Ã¢â‚¬" saved bookmarks and wishlist items are untouched.` because the Kotlin source
  had been read as Windows-1252 and written back as UTF-8, so the em dash (U+2014) survived as the three
  characters `Ã¢â‚¬â€`. The text is now `Session cache cleared â€” saved bookmarks and wishlist items are
  untouched.`, the sentence lives in `SessionCacheMessages.CLEARED`, and the same repair was applied to
  every other occurrence of the defect in `app/src` and `ONLINE_SEARCH.md` (109 lines across 4 files).
  A unit test pins the exact sentence, refuses the damaged characters and scans the packaged sources for
  the `Ã¢â‚¬` signature so it cannot come back quietly.
* **A saved result profile keeps its original picture.** A profile used to keep only the 44Ã—58 row
  thumbnail â€” and an online result profile kept no image at all â€” so opening a profile inside its folder
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
* **Search â†’ Add entire result profile** placeholder is `e.g. ãƒ–ãƒ«ã‚¢ã‚«` (was `e.g. Doujinshi`).
* **Wishlist â†’ Create folder** placeholder is `e.g. ãƒ–ãƒ«ã‚¢ã‚«` (the `e.g. ` prefix had been lost). Both
  dialogs now read the same `BookmarkForm.FOLDER_NAME_PLACEHOLDER`, so they cannot drift apart again.

### Tests

* `OriginalProfileImageTest` (8 tests): the original is its own file with its own bytes, separate from
  the thumbnail; it survives a reopened repository; two profiles never share one; deleting a profile
  removes exactly its own files; a profile without an image still loads; a `records.json` written before
  the field loads; a storage failure leaves the library readable and records nothing; a copied folder
  gets its own copy of the file.
* `SessionCacheMessageTest` (5 tests): the exact sentence, no damaged characters, the packaged sources
  carry no `Ã¢â‚¬` double encoding, and the folder placeholder carries its `e.g. ` prefix.
* `BookmarkFormTest` updated for the new placeholder text.

### Verified

* Full JVM suite: **981 tests, 0 failures, 7 skipped** (see `README.md` Â§7 for the exact command).
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


## v2.4.0-rc.7 (2026-11-05)

**Version code/name: 214 / 2.4.0-rc.7.** A QA-remediation build: the two defects the previous emulator
engagement left open are fixed and re-verified on the Pixel 8 AVD. No feature was added and no parsing
rule was changed.

### Fixed

* **A catalogue that stops at an empty row now says so.** The reader ends a table at the first all-empty
  row, which is deliberate â€” skipping blank rows could merge two unrelated blocks that share one sheet â€”
  but a sheet with data below that row was reported with fewer records than it holds and nothing
  explained the difference: `layout_blank_rows.xlsx` showed 8 comments and **3** records, silently
  (QA defect XLS-01). The reader now counts the rows below the blank row **that carry data in the
  table's own columns** (`CatalogueTable.truncation`), and that fact is shown in three places: a warning
  line under the record count in *Catalogue*, the same line under the worksheet's header in the Table
  view ("5 rows below empty row 5 were not read â€” a blank row ends a table"), and a warning finding in
  *Insights â†’ Health* ("Rows below an empty row were not read", with the row numbers and how to fix the
  sheet). The stop rule, every fixture's record count and every worksheet's mapping are unchanged.
* **The matcher's default range covers the layout the app documents.** A fresh install started on
  `A1:B20`, two columns wide, while the doujin catalogue keeps its 24 columns in `A:X` and its cover
  pictures in the title column `D`, so a first run against a real workbook found nothing at all (QA
  defect UI-01). The default is now `A1:Z5000` â€” a wide range costs nothing, because planning an
  extraction reads the workbook's own drawing and comment anchors and keeps the ones inside the range â€”
  and the empty-result message now names the range and says where covers usually sit, instead of only
  reporting the emptiness. A range the user has already typed is never overwritten.

### Tests

* `CatalogueTruncationTest` (6): the record count still stops at the blank row, the rows below are
  counted with their row numbers, leading blank rows and out-of-table cells are not a truncation, and the
  health finding is a per-worksheet warning that names the rows.
* `DefaultRangeTest` (3): the default range parses, contains the documented title column `D` and column
  `X`, spans 5000 rows, is what a fresh `UiState` starts on, and the old `A1:B20` would have missed
  column `D`.
* Full suite: **790 tests, 0 failures, 0 errors, 7 skipped** across 96 result classes.

### Verified on the emulator

Pixel 8 AVD (`emulator-5554`, Android 17 / API 37, `sdk_gphone64_x86_64`), debug APK installed with
`adb install -r`, app data cleared first so a **fresh install** was tested:

| Check | Result |
|---|---|
| Fresh install's range field | `A1:Z5000` |
| `layout_blank_rows.xlsx` import | staged `layout_blank_rows.xlsx Â· 34 KB` |
| Catalogue record count (unchanged) | `3 record(s) shown Â· all worksheets` |
| Catalogue warning | `LIST: 5 rows below empty row 5 not read` |
| Table view note | `5 rows below empty row 5 were not read â€” a blank row ends a table` |
| Health finding | "Rows below an empty row were not read": the catalogue stops at row 5, 5 rows from row 6 are not counted, delete the empty row and rescan |
| Fatal exceptions during the run | 0 |

### Known limitations

* A sheet whose records continue after an empty row is still read as one table up to that row: the app
  reports what it skipped instead of guessing that the rows below belong to the same catalogue.
* The "no images found" wording is verified by inspection; the full scan-and-find path was not re-driven
  end to end in this round (it needs a camera capture or an image upload first).
* Camera content scenarios (angled, low light, blurry, obstructed, several books) remain blocked on the
  emulator: its virtual scene cannot be posed over adb, so those were neither tested nor claimed.

## v2.5.0-dev.5 (2026-11-05)

**Version code/name: 220 / 2.5.0-dev.5.** The first stage of the OCR-engine migration: the native Rust
PP-OCRv6 core is started, builds for both Android ABIs and is unit-tested, and the feasibility of the whole
migration was verified against the real model artifacts. **The application's OCR behaviour is unchanged in this
build** â€” the existing embedded-Python engine is still the only OCR path the app uses, because the native inference
pipeline is not finished or device-verified yet.

### Added

* **`native/ocr`, a Rust crate for the native OCR core.** `cdylib` + `rlib`, no dependencies yet, `cargo test`
  green (13 tests) and cross-compiled for `aarch64-linux-android` and `x86_64-linux-android` with NDK r27d
  (`libbc_ocr.so`, 300.4 KB and 326.2 KB). Implemented and tested so far:
  * `dict` â€” PP-OCR `CTCLabelDecode` index space (`blank = 0`, dictionary from class 1, optional trailing space
    class), greedy CTC decoding with repeat collapsing, blank skipping and an honest per-character confidence;
  * `geom` â€” corner ordering by PaddleOCR's `get_mini_boxes` rule, quad size and vertical/horizontal
    classification (a vertical Japanese column must be recognisable as vertical), the affine solve and the
    bilinear perspective crop that turns a detected quad into an upright recogniser input, with clamping for
    degenerate or out-of-bounds quads.
* **`tools/fetch-ppocrv6-models.ps1`** â€” reproducible download of the official ONNX artifacts with size checks,
  plus extraction of the recognition dictionary from the model's own `inference.yml`.
* **`native/ocr/README.md`** â€” toolchain, build commands, model provenance and an explicit statement of what is
  and is not implemented.

### Verified (feasibility, not implementation)

* `PaddlePaddle/PP-OCRv6_medium_det_onnx` â†’ `inference.onnx`, **62,032,837 bytes**; DB post-processing parameters
  from its `inference.yml`: `thresh 0.2`, `box_thresh 0.45`, `unclip_ratio 1.4`, `max_candidates 3000`.
* `PaddlePaddle/PP-OCRv6_medium_rec_onnx` â†’ `inference.onnx`, **76,554,979 bytes**; `PostProcess: CTCLabelDecode`
  with an inline **18,704-character** dictionary; `RecResizeImg` input `[3, 48, 320]`.
* `ort` 2.0.0-rc.15 offers `api-17` â€¦ `api-31`, `load-dynamic` and `nnapi`; the app already ships ONNX Runtime
  **1.20.0**, so the only conflict-free arrangement is `api-20` + `load-dynamic` against that existing runtime.
* Toolchain obtained on this machine: NDK r27d, Rust Android targets `aarch64-linux-android` /
  `x86_64-linux-android`, host `cargo test`.

### Not done (and why)

* Detector/recogniser stages, ONNX session layer, JNI exports and Kotlin wiring are **not** implemented; there is
  therefore **no** native inference, **no** dictionary-vs-graph validation, and **no** NNAPI/CPU-fallback
  measurement yet. `historical_prompt/v2.5.0-dev.5-ag.md` records the exact remaining work.
* `chrome-lens-py`, Chaquopy and the Python runtime are **retained**: removing them before the native engine is
  proven would leave the app without OCR, which the release instructions forbid.
* No claim is made about OCR accuracy, latency or memory of the native path â€” nothing was measured.

### Known limitations

* The 139 MB of model weights are downloaded on demand and git-ignored; they are not bundled in the APK.
* `libbc_ocr.so` is built but not packaged into the app; `assembleDebug` does not depend on the Rust toolchain.


**Version code/name: 219 / 2.5.0-dev.4.** Discovery parity: a SauceNAO result is now as usable in the collection
workflow as an IQDB result. Every discovery card can carry a picture, be dismissed, be bookmarked and be added to a
wishlist; the profile-wide wishlist action includes SauceNAO; and the two bulk bookmark actions now say which set of
results they act on.

### Added

* **SauceNAO results show a picture when one can be obtained.** The card gains a fixed 60Ã—82 dp thumbnail that fills
  from, in order: the provider's own result image (`header.thumbnail`), then the result page's declared metadata â€”
  `og:image:secure_url` / `og:image`, then `twitter:image` / `twitter:image:src`, then `<link rel="image_src">` /
  `itemprop="image"`. The first `<img>` on a page is deliberately **not** used. The box is sized before anything is
  known about the image, so a picture arriving later cannot move the text, and it says `loadingâ€¦`, `not loadable` or
  `no image` instead of leaving a gap. Provenance is kept on the candidate (`PROVIDER` vs the webpage metadata that
  declared it) and in the image's accessibility label.
* **`Bookmark all discovery results`**, under the reverse-image section, next to the candidates it acts on. It
  bookmarks the eligible discovery results of the current search (SauceNAO and IQDB alike) and reports what happened
  per provider, e.g. *"Bookmarked 1 discovery result(s) â€” 1 already there (IQDB 2)."*
* **Per-card `Bookmark` / `+ Wishlist` on discovery cards**, mirroring the platform result cards: a bookmark writes to
  `Bookmarks` only, the wishlist action opens the same folder picker every other card uses, and the chips state what
  is already true (`Bookmarked âœ“`, `In wishlist âœ“`).

### Changed

* **`Bookmark all results` is now `Bookmark all platform results`** (Online tab and the search's Bookmarks tab), with
  a caption that says so. Its behaviour is unchanged: it bookmarks the platform search results and nothing else.
* **`Add entire result profile to wishlist` now includes the reverse-image discovery results.** It previously built
  its session from the offline top-5 plus the platform results and silently left discovery out, even when SauceNAO had
  found the match â€” measured on an API 37 emulator as 5 items for a search whose discovery list held a further result.
  The profile is now offline + platform + eligible discovery, deduplicated by item identity.
* A discovery result is stored with `MatchType.VISUAL` and `matchTypeVerified`, which is exactly what a reverse-image
  provider did (it compared the pictures); the previous comment claiming nothing produces `VISUAL` is corrected.

### Fixed

* **SauceNAO cards were not dismissible.** v2.5.0-dev.3 gave the rubbish-bin control to IQDB-only cards, so the
  primary provider's cards could not be removed from the list. Every card a reverse-image provider reported now has
  the same control, labelled with the provider whose evidence the tap removes (`Dismiss SauceNAO result`,
  `Dismiss IQDB result`, and `Dismiss discovery result` for a card both providers reported, which is one card holding
  both references).
* **A dropped discovery picture could have re-inserted a dismissed card.** Image loading is now patched by candidate
  key through a rule that refuses a key that is no longer present, so a picture that arrives after a dismissal â€” or
  after a newer search started â€” is discarded instead of restoring the card.

### Security

* Picture retrieval is bounded and guarded: **HTTPS only**, no credentials, no non-default ports, no bare/private
  hosts (`localhost`, `*.local`, `10/8`, `172.16/12`, `192.168/16`, `127/8`, `169.254/16`, `::1`, `fc00::/7`,
  `fe80::/10`, IPv4-mapped IPv6), and **every redirect hop is validated** before it is contacted (`HttpURLConnection`
  follows none by itself here). Pages are read up to 512 KB (a longer page is truncated at the cap, because the
  metadata lives in the head), images up to 400 KB, with connect/read timeouts and a content-type check; image bytes
  are decoded header-only to reject a payload that expands beyond 4096 px. No JavaScript is executed, no browser
  automation is used, and a refusal is reported as "no image" â€” a valid result is never removed because its picture
  could not be fetched.

### Known limitations

* Not every result has a usable picture. A page may declare no image, refuse the request (`HTTP 429` from
  `upload.wikimedia.org` was observed during live verification), or serve a format the app does not decode; the card
  then shows its placeholder and stays a perfectly valid, bookmarkable result.
* DNS is not resolved before a connection, so a hostname that resolves to a private address at fetch time is outside
  the guard; only URLs a provider returned or a result page declared are ever fetched.
* Dismissal stays in memory: a new search may show the same result again. There is no persistent block-list.


**Version code/name: 218 / 2.5.0-dev.3.** A repair-and-refinement build: SauceNAO's API key can now actually be
configured (it never could), the duplicate "Listing Candidate" flow is gone from the Online tab, and three IQDB
UI defects are fixed.

### Fixed

* **SauceNAO automation: the API key was never stored, so the provider could never leave `NOT_CONFIGURED`.**
  `AesGcmSecretBox.seal` passed a **caller-supplied GCM IV** to `Cipher.init(ENCRYPT_MODE, â€¦)`, which an
  `AndroidKeyStore` AES/GCM key forbids: the Keystore refused the operation (`CALLER_NONCE_PROHIBITED`, measured
  on an API 37 emulator as `keystore2 â€¦ enforcements.rs: NONCE is present, although CALLER_NONCE is not present`).
  `SauceNaoKeyStore.save` caught that failure and returned `false`, the UI reported it as "that does not look like
  a SauceNAO key", and nothing was ever written to the secrets file. Encryption now lets the **cipher generate the
  IV** and stores it in the envelope (`[12-byte IV][ciphertextâ€–tag]`), which works for both a Keystore key and a
  plain JVM key; decryption still uses the stored IV, which the platform allows.
* **A failed save is no longer indistinguishable from a typo.** `SauceNaoKeyStore.save` now returns a
  `SauceNaoKeySaveResult` â€” `Saved`, `Rejected(MALFORMED)` or `Rejected(STORAGE_FAILED)` â€” with two different,
  key-free messages (`SauceNaoKeyRules.describe`). The save also **round-trips**: the value is read back through
  the same decrypt path before the app claims success, which is the check that would have caught this defect the
  moment the user pressed the button.
* **A stale run no longer blocks a fresh configuration.** Saving a key discards the previous SauceNAO run (a run
  recorded before the key existed says `NOT_CONFIGURED`), and the write uses `commit()` so the round-trip reads
  what was actually persisted.
* **The IQDB suggestion line is readable.** `Suggestion â€” not confirmed by you` was drawn in the card's own navy
  (`Palette.Navy700`) and was effectively invisible; the decision line now uses explicit **white** for
  `SUGGESTED` (and keeps green/red for confirmed/rejected).

### Added

* **A rubbish-bin control on every IQDB result card.** It sits at the card's top-right, is labelled
  `Dismiss IQDB result` for accessibility, and removes **only that card** from the current list: no other card, no
  SauceNAO result, no decision record, no wishlist entry and no cached image is touched, and a later search may
  legitimately show the result again. It is deliberately **not** shown on cards SauceNAO also found, so one tap
  cannot discard the first-priority provider's evidence (`ReverseCandidateUi.isIqdbOnly`).
* **`Replace` for the SauceNAO key.** A stored key now offers *Replace* next to *Remove*, opening the field without
  deleting the working key first â€” a mistyped replacement cannot leave the user with no key at all.

### Removed

* **The "Listing Candidate" flow from the Online tab** (`Add listing`, its seven-field form, its controller state
  and `core/online/ManualCandidate.kt` plus its tests). It duplicated what the Wishlist already does properly: the
  Wishlist tab owns bookmarks, manual bookmarks and their deduplication/profile isolation, and this build leaves
  that untouched. No stored data was migrated, deleted or rewritten. The IQDB paste box â€” the remaining way user
  material enters the Online tab â€” is unaffected.

### Changed

* **The `Paste an IQDB result` section is always expanded.** The expand/collapse button and its state are gone, so
  the field and its *Add results* action are visible as soon as the reverse-image section is reached.


### Tests

* `SauceNaoKeyRulesTest` (3) â€” key shape rules and the two distinct, key-free rejection messages.
* `SecretBoxTest` additions (2) â€” the envelope carries the **cipher's** IV (the regression that broke SauceNAO),
  and no IV ever repeats.
* `IqdbCandidateUiTest` (5) â€” which cards are dismissible: IQDB-only yes, SauceNAO-only no, found-by-both no, and
  provider attribution survives the UI mapping.
* `ManualCandidateTest` (8) deleted with the feature it covered. Suite: **876 tests / 108 classes / 0 failures /
  7 skipped**, no live network.

### Live SauceNAO integration test (2026-11-05, API 37 emulator; key redacted)

| Item | Result |
|---|---|
| API key configured through the app's own field | **PASS** â€” `API key: [REDACTED]` |
| Secure storage written | **PASS** â€” `bookcover_online_secrets.xml`, base64 ciphertext only; no plaintext key in the file |
| Key retrieval after save | **PASS** â€” masked hint `â€¢â€¢â€¢â€¢â€¢â€¢â€¢â€¢19a3` |
| Provider readiness | **PASS** â€” row changed `NEEDS KEY` â†’ `READY` |
| Provider invoked / real request | **PASS** â€” `POST https://saucenao.com/search.php`, HTTP 200, `header.status = 0` |
| Response parsing | **PASS** â€” 8 normalised hits, e.g. `SauceNAO 40.27%`, 38.26%, 38.16%, 37.83%, 36.81% |
| Quota reported by the provider | **PASS** â€” `quota: 99 of 100 left` |
| UI presentation | **PASS** â€” `RESULTS`, `8 candidate(s) Â· 8 undecided`; IQDB still `MANUAL ONLY` |
| Key replacement | **PASS** â€” `â€¢â€¢â€¢â€¢â€¢â€¢â€¢â€¢19a3` â†’ `â€¢â€¢â€¢â€¢â€¢â€¢â€¢â€¢3333` â†’ `â€¢â€¢â€¢â€¢â€¢â€¢â€¢â€¢19a3`, `READY` throughout |
| Secret-exposure check (logcat + prefs) | **PASS** â€” the full key, its first 8 and its last 4 characters do not appear in `logcat`; prefs hold ciphertext only |

### Known limitations

* The second live call after the key replacement was **not** re-executed: reinstalling the app cleared the
  captured cover, and the call needs one. Readiness and decryption were verified after replacement instead, and the
  provider reads the key at call time.
* IQDB remains **manual only** (no API; its `robots.txt` disallows its own search routes) â€” unchanged by design.
* Candidate cards still do not show the per-candidate OpenCLIP/catalogue comparison (`CollectionStatus`), which
  remains wired only in `core`.


## v2.5.0-dev.2 (2026-11-05)

**Version code/name: 217 / 2.5.0-dev.2.** Second development build of the online-discovery refinement.
It adds **reverse-image discovery** with two providers, **manual/browser-assisted discovery**, **bounded cover
caching**, a **user-controllable secondary-source switch** and **persisted candidate decisions** â€” and it is the
first build where the dev.1 core is reachable from the screen (`Online search â†’ C Â· Reverse-image discovery`).

### Added

* **SauceNAO â€” first-priority reverse-image provider (automated).** `SauceNaoReverseImageSource` submits the
  captured cover through SauceNAO's documented JSON API: a `multipart/form-data` POST to
  `https://saucenao.com/search.php` carrying `output_type=2`, `db=999`, `numres`, an optional `minsim` floor and
  the user's own `api_key`. The credential travels in the request **body**, never in a URL. The parser
  (`SauceNaoResponseParser`) reads the documented `header`/`results` envelope, preserves each row's own
  similarity, source URL, thumbnail, index name and every other scalar it sent, and maps the provider's report to
  a named state: `SUCCESS`, `NO_RESULTS`, `QUOTA_EXCEEDED`, `ERROR` â€” plus `TIMEOUT`, `RATE_LIMITED`, `BLOCKED`
  and `UNAVAILABLE` from the transport. No retry loop exists, because retrying a soft limit is what turns it into
  a long one.
* **The SauceNAO API key is stored with a Keystore-backed key.** `SauceNaoKeyStore` seals the key with
  AES-256-GCM (`AesGcmSecretBox`, a new random IV per write) under a key generated inside `AndroidKeyStore`, and
  keeps only the ciphertext in its own preference file. The panel shows `â€¢â€¢â€¢â€¢â€¢â€¢â€¢â€¢ab12` after saving and offers
  *Remove*; the key is never logged, echoed, placed in a URL, or shown again. A blob that cannot be opened (new
  device, cleared app data) is deleted and reported as "not configured" instead of being sent to the provider.
* **IQDB â€” second-priority reverse-image provider, implemented as manual-only, with the verification on the
  record.** Verified 2026-11-05: `https://iqdb.org/` is an HTML form with **no documented public API**, and
  `https://iqdb.org/robots.txt` disallows `/multi-search`, `/db-search`, `/kc-search`, `/mi-search`, `/index` and
  `/?url=` for every agent. The app therefore **does not submit images to IQDB**. `IqdbReverseImageSource`
  reports `MANUAL_ONLY`, its row opens IQDB's own search page, and the user brings results back: a pasted result
  line or a saved result page is normalised by `IqdbResultParser`, which keeps each row's own percentage and
  source URL and drops rows with no URL. No IQDB throttling, quota or timeout is incurred at all; the adapter
  still declares a longer 90 s budget so the provider's character is documented in one place.
* **A reverse-image pipeline that keeps its providers apart.** `ReverseImageSearchRunner` always tries SauceNAO
  first and **still tries IQDB afterwards even when SauceNAO answered**, publishes each provider's row as it
  runs, treats a call that exceeded *that provider's* budget as `TIMEOUT` (never as an empty result), records a
  cancelled provider as `CANCELLED` without sending anything, and merges only genuine answers.
  `ReverseImageAggregator` deduplicates only on proof of identity (a shared product code or a shared normalised
  URL) and keeps **both** providers' references, similarities and URLs. There is deliberately **no combined
  score**: agreeing providers are stronger provenance, not a probability.
* **Manual and browser-assisted discovery (Approach D).** `ManualCandidates` validates a listing the user typed
  (URL must be `http(s)`; a title or a product code is required) and converts it into the *same*
  `ProductSearchResult` model every automated provider produces, stamped with the `manual` provider id. It is
  never fetched, never called an automated extraction, and it flows through the same evidence and decision
  pipeline. The panel adds the form inline, plus a *Paste an IQDB result* block.
* **Persisted confirmations and rejections.** A candidate starts as `SUGGESTED` â€” a machine suggestion, labelled
  as such â€” and only an explicit tap produces `CONFIRMED`/`REJECTED`. `CandidateReviews` keeps the rules
  (idempotent by candidate identity, a rejection can be revisited, a blank key decides nothing) and
  `CandidateDecisionStore` writes one JSON file per wishlist profile. Deciding does **not** write to the workbook
  or the catalogue.
* **Ownership, catalogue presence and wanted status, kept independent.** `CollectionStatus` holds three separate
  facts with "not stated" as a real value, and `CollectionStatusOf` derives only catalogue presence from the
  matcher â€” `MATCHED`, `AMBIGUOUS`, `ABSENT` or `UNKNOWN`, where an empty workbook can never produce `ABSENT`.
  `LocalProducts.of(CatalogEntry)` maps only **mapped, present** cells, so a record without a circle is unknown
  rather than contradicting.
* **A user-controllable secondary-source switch.** `ProviderCapability` gained a `tier` (`PRIMARY_DOUJIN` for
  DLsite, `SECONDARY_BOOK` for Open Library and Google Books). Switching the general-book sources off **does not
  issue their requests**: the controller filters them out before any call, their rows say "switched off", and the
  outcome is a new `CatalogueUi.SwitchedOff` state rather than an empty result.
* **Bounded cover caching.** `BoundedImageCache` is a byte-budgeted LRU with a per-entry ceiling, refusal
  counters, invalidation and a `describe()` line â€” plain Kotlin, so the policy is unit-tested rather than assumed.
* **The dev.1 core is now wired.** The panel shows the two provider rows with their own states, the key
  configuration, the *Search by this cover* action, the candidate list with attributed evidence and
  Confirm/Reject, the manual-entry form, the IQDB paste block and the general-book switch.

### Fixed

* **The application crashed on every launch (startup `NullPointerException`).** `OnlineSearchController` builds
  its UI state in a Kotlin property initializer, and that initializer asked for the reverse-image provider rows
  *before* the `reverseRuns` map it read had been assigned â€” the map was declared further down the class body,
  and Kotlin runs property initializers in declaration order, so it was still `null`. The constructor therefore
  threw `NullPointerException` inside `MainViewModel`'s construction and the process died before the first frame
  (`OnlineSearchController.reverseProviderRows` â†’ `<init>` â†’ `MainViewModel.<init>`). Fixed in two steps: the
  reverse-image state is now declared **above** the UI state, and the row builder was extracted into
  `ReverseProviderRows.of(sources, runs)`, which takes the map as a **parameter**, so the initializer calls it
  with an explicit `emptyMap()` and can no longer read uninitialised state. Verified on the emulator: cold start,
  the online panel, the reverse-image section, a manual candidate with Confirm, and the general-book switch all
  run without a fatal exception. Regression test: `ReverseProviderRowsTest` (5 tests).

### Changed

* `ProviderCapabilities.eligible`/`skipped` take an `includeSecondary` flag (default `true`, so existing callers
  keep their behaviour), and `ProviderCapability` gained a tier.
* `HttpJsonClient` gained `postMultipart` for the one upload in the app; the credential-bearing body is built and
  tested in `core` (`MultipartForm`) rather than assembled in the transport.
* `OnlineSearchPrefs` gained `secondarySources` (default on). The `INTERNET` permission and the off-by-default
  master switch are unchanged.

### Tests

* 61 new deterministic tests in 9 classes: `SauceNaoProtocolTest` (documented request parts, masked key, response
  parsing, quota, non-zero status, unreadable document, multipart body), `IqdbProtocolTest` (automation off,
  robots routes, saved-page parsing, pasted lines), `ReverseImageRunnerTest` (SauceNAO-first order, IQDB still
  runs, failure isolation, timeout-not-empty, no-query states, cancellation, stale token, cross-provider dedupe,
  title-is-not-identity), `ManualCandidateTest`, `CollectionStatusTest`, `CandidateReviewTest`,
  `ProviderTierTest`, `CoverCacheTest` and `SecretBoxTest`. The suite is now **869 tests / 106 classes /
  0 failures / 7 skipped** and makes no live network request.

### Known limitations

* **IQDB is manual-only, not automated.** No automated IQDB search is implemented or claimed; the documented
  reason and the disallowed routes are recorded in `IqdbProtocol`. Automation would need a permitted integration
  route that does not exist today.
* **SauceNAO was not verified live in this session.** `saucenao.com` answered HTTP 403 to the documentation
  tooling used here, so the request/response schema is implemented from the provider's published API description
  and the parser is deliberately defensive (any status other than `0` becomes a named failure carrying the
  provider's own number). No live request was made, so no result quality is claimed.
* **The IQDB result parser is exercised only against sanitised fixtures** â€” it is never run against the live
  service.
* **Catalogue matching is not yet shown per candidate.** `CollectionStatus` and `LocalProducts.of` exist and are
  tested, but the candidate card does not display the per-dimension evidence against the open workbook yet;
  wiring the OpenCLIP cover comparison into reverse-image candidates is the next step.
* Confirm/Reject records a decision; it does not modify workbook cells and does not add wishlist entries. Adding
  a candidate to a profile stays the existing explicit action, so duplicates keep being prevented by the store's
  own identity rules.



## v2.5.0-dev.1 (2026-11-05)

**Version code/name: 216 / 2.5.0-dev.1.** First development build of the online-discovery refinement. It
delivers the audited **core** of the feature; the screens that would consume it are not wired yet, so
nothing user-visible changes in this build (see *Known limitations*).

### Added

* **A structured search query.** `OnlineQuery` splits what the user types into title, circle, author and
  product code â€” `title:`/`circle:`/`author:`/`code:` prefixes, and a bare `RJ01234567` (or `RJ 01234567`,
  `RJ-01234567`) recognised wherever it appears â€” and keeps the original text untouched for display and
  diagnostics. Full-width Latin and the ideographic space fold to half-width; kana, kanji and long-vowel
  marks are never rewritten. Identifiers lose only spaces, hyphens and case, and only for comparison.
* **Declared provider capabilities.** `ProviderCapabilities` records what each in-app provider actually
  searches â€” Open Library and Google Books: title, author, ISBN; DLsite: title, circle, RJ code â€” and
  reports the parts of a query it must ignore. An author-only search is therefore not sent to DLsite's
  keyword box, and a circle-only search is not sent to the bibliographic catalogues; the provider is
  reported as unable to search, not as having failed.
* **An explainable candidate matcher.** `CandidateMatcher` compares an online candidate with a local
  record one dimension at a time (identifier 0.45, title 0.30, circle 0.15, author 0.10, cover 0.35 â€” the
  weights are documented constants), renormalises over the dimensions both sides can supply so a field a
  provider never sends is never held against it, treats an exact product identifier as decisive, refuses to
  call a shared title identity, and returns per-dimension evidence plus an ambiguity list instead of
  silently choosing a record.

### Tests

* `OnlineSearchRefinementTest` (18): query parsing and normalisation, capability routing, identifier
  precedence, missing-evidence handling, cover participation, ambiguity and similarity. The suite also
  caught three real defects during development (a title-only match was being called *likely*, a spaced
  `RJ` code was not parsed, and ranking used the enum's declaration order instead of a quality order), all
  fixed before the build below.

### Known limitations

* **No UI wiring yet.** The unified search screen, source-grouped results, side-by-side cover comparison,
  confirm/reject actions, collection-status controls, cover-image cache and wishlist integration described
  in the prompt are **not implemented**; the new core is not reachable from the UI.
* Image similarity is accepted as an input from the existing OpenCLIP pipeline but is not called from it
  yet, and local catalogue records are not yet read into `LocalProduct`.
* Provider access is unchanged: Open Library, Google Books and DLsite are queried; Melonbooks, Suruga-ya,
  Doujin no Mori, E-Hentai, Doujinshi.info, MyAnimeList, nhentai and Toranoana stay browser-only for the
  measured reasons in `ONLINE_SEARCH.md`. No provider was newly enabled and **no reverse-image discovery**
  is claimed or implemented.

## v2.4.0 (2026-11-05)

*The stable release is the `2.4.0-rc.7` code base â€” the QA-remediation build documented in the section
above â€” with the application version set to `2.4.0` (versionCode 215). `rc.5` and `rc.6` were pre-release
builds of the same release at versionCode 213; `rc.7` was 214. No feature, parsing rule or test expectation
changed between `rc.7` and this release.*

### Added

* **A worksheet filter on the Gallery.** Inside *More â†’ Workbook comments â†’ Insights â†’ Gallery* the filter
  bar now starts with *Worksheets: All worksheets / LIST / 2 of 3 worksheets*, which opens the same
  worksheet picker the Health and Analytics reports use: all worksheets, one, or any combination, with each
  worksheet's record count and hidden state, and **Use these worksheets** to commit.
  The selection is a set of worksheet **identities** (the package part, `xl/worksheets/sheet5.xml`), not
  display names â€” renaming a worksheet in Excel keeps the selection, and a key the workbook no longer has
  is reconciled instead of hiding every card. It filters the records **before** anything else runs, so it
  changes what the grid holds rather than hiding cards after they were chosen: search, *With cover*,
  *No cover*, *Missing title*, *Missing metadata*, every sort order, the count line (`12 of 40 record(s)`)
  and the scroll reset all work with it. Gallery, Health and Analytics each keep their **own** selection.
  A workbook opened in a fresh session starts at *All worksheets*, so a selection cannot follow the user
  into another file.
* **A worksheet filter on Health, Analytics and the Gallery that is remembered.** Each of the three screens
  keeps its own selection â€” all worksheets, one, or several â€” and it now survives navigation, closing the
  workbook and restarting the app: `files/mappings/insights-views.json` stores it per workbook (its SHA-256),
  never per display name, and a saved key the workbook no longer has is dropped instead of matching some
  other sheet. None of the three can overwrite another's selection.
* **The timeline's year filter is remembered too**, separately for Published and Acquired, so reopening a
  workbook comes back to the period the user was reading.
* **Linked cells in the Catalogue table, and a cell dialog to act on them.** The workbook's own hyperlinks
  (`<hyperlinks>` plus the worksheet's `.rels`) are now kept with the cell they belong to: a linked value is
  drawn in the accent colour and underlined, and tapping any cell opens a centred dialog with its value and
  three stacked actions â€” **Copy** (the displayed text, exactly as shown), **Open Link** (only for a link the
  workbook really stores: an `http(s)`/`mailto` destination goes to the browser, an in-workbook `location`
  opens that cell in the app's own source view) and **Back** â€” outside a tap on the card the dialog closes.
  Nothing is inferred from the text: a cell that merely looks like a URL has no link, and the destination
  never replaces the displayed value.
* **A "Default order" field for the workbook's own ordering column.** *Insights â†’ Fields* now has an
  optional **Default order** field â€” a serial number, a shelf position, anything the user picks. It is
  mapped like any other field (any header, any language, any position), can be changed or cleared, and the
  screen shows what the chosen column really holds (how many values are numbers, how many are text, how many
  are blank, plus examples). Nothing is inferred from a header name: the field is offered, never forced.
  When two records share a Published or Acquired date, the timeline now orders them by this column â€”
  **ascending**, numerically (`2` before `10`, `0012` the same as `12`), and text values predictably after
  numbers. A record with no value sorts last and keeps its verified source row as the final tie-break, so
  nothing is invented. Changing the mapping changes the order; without it, the timeline keeps its previous
  behaviour.
* **The Source location preview reads and behaves like the table.** The worksheet window that *go to source*
  opens used to print the raw cell â€” a date-formatted serial showed as `45292`, a percentage as `0.123456` â€”
  and no cell could be inspected. Every cell is now drawn the way the workbook shows it, through the **same**
  number-format rule the Catalogue table uses (one call, two screens, so the same cell can never read two
  ways), every cell is a touch target that opens the same **Copy / Open Link / Back** dialog, and a cell the
  workbook links is drawn in the accent colour and underlined. The dialog's identity is the worksheet's own
  row and column â€” the window shows about thirteen rows around the verified cell, so the drawn position is
  never used as the identity â€” and the value, the hyperlink and *Open Link* therefore always describe the
  cell that was tapped. Header cells are inspectable too, whether the header row is inside the window or
  carried above it.
* **The mapping is remembered per workbook.** *Insights â†’ Fields* now stores the mapping in effect next to the
  other per-workbook settings (`files/mappings/workbook-mappings.json`, keyed by the workbook's SHA-256), so
  a manual choice â€” the Default order column included â€” is still in effect after navigating away, closing the
  workbook or restarting the app, instead of falling back to detection. Picking **Detected** removes the
  stored entry; nothing unapplied is ever stored.

### Changed

* **The Rescan button is now the blue of the Saved workbooks Import button** (`Palette.NavyGradient`, the
  same token, not a new blue), so it reads as the primary action of the header next to the grey Back button.
  Its size, typography, and its loading/disabled behaviour are unchanged.
* **Cover first is no longer a gallery order.** Cover state stays a **filter** (*With cover* / *No cover*)
  driven by the verified image association; the separate "Cover first" ordering option is gone, so the four
  remaining orders describe the workbook (title, Circle, Artist, source order) and nothing ranks cards by
  their picture.
* **Numbers are displayed with at most two decimals, everywhere.** One shared rule
  (`Formatting.decimal`) now formats every measurement the app shows: a similarity score reads `0.81`
  instead of `0.812`, a workbook cell whose number format declares decimals reads `1234.57` instead of
  `1234.5678`, and percentages keep their own formatting (`12.35%`). This is a **display** rule only â€”
  nothing is rounded before it is stored, aggregated, sorted, filtered or exported, and a value that is not
  a measurement is left alone: `General` and integer cells (identifiers, serial numbers, row numbers, page
  counts) keep every digit they had.
* **Timeline: Published and Acquired only.** The **Imported** mode was removed from the timeline: it
  repeated every record once, under the app's own import date rather than a date the collection has. Both
  remaining modes, their manual column choices, their filters, their ordering and the scroll reset are
  unchanged. Nothing else about import metadata changed â€” the saved-workbook library still records and sorts
  by its import timestamp.
* **Catalogue â†’ Table shows dates as dates.** A cell whose number format is a date was printed as the
  serial number Excel stores (`45292`); it now reads `2024-01-01`, and `2024-01-01 14:30` when the cell also
  carries a time. The workbook's own date system is honoured (@1900/@1904), and the record's stored value is
  untouched, so filters, search, exports and the canonical catalog keep reading exactly what the file
  contains.
* **A percentage cell is rounded to two decimals.** A cell formatted as a percentage was printed as the
  stored fraction (`0.123456`); it now reads `12.35%`, `50%`, `100%`, `0%`. Trailing zeros are dropped, the
  fraction is scaled exactly once, and a cell that is *not* percentage-formatted never gains a `%`. Nothing
  is rounded before it is stored.

### Fixed

* **Rescan now refreshes every Insights function in place.** Clicking **Rescan** while the Insights
  directory was open dropped the parsed data but rebuilt nothing, so Health, Analytics, Gallery, Timeline and
  Fields only came back after leaving the directory and returning. A finished rescan now rebuilds the catalog
  and all five functions from the new analysis immediately, re-previews the Fields draft, and keeps every
  choice the user made (profiles, worksheet scopes, gallery and timeline filters, chosen date columns) â€” the
  panels show a reading state instead of an empty report while it runs, and a failed scan clears that state
  instead of leaving them stuck.
* **The global Sheets button is gone.** One button in the shared header opened the worksheet picker for every
  directory, including the three that keep their own independent worksheet scope. It was removed; the picker
  is still opened where it belongs (Comments, Images, Catalogue), and no worksheet feature was touched.
* **A manual mapping now outranks automatic detection.** A mapping applied from the Fields screen without
  being saved as a profile was silently reverted to the detected one on the next rebuild (and would have been
  on a rescan). It is now kept in the state, used by every report until the user picks a profile or
  **Detected**, and the Fields screen says which of the three is in effect.
* **The Default order column can be changed in Fields.** The field had no visible control of its own: the row's
  only affordance was a muted word, and the read-only card beside it looked like the whole story, so the one
  column the timeline depends on appeared locked. Every mapped field now shows a bordered **Change** button, and
  **Default order** has its own card with **Change / Choose column**, **Clear** and **Use detected** â€” the same
  column picker the other fields use, with *Not mapped* available â€” plus a line saying which column detection
  would use. The picker also no longer fails when two columns carry the same header (both stay choosable, and
  the resolver keeps reporting the ambiguity).
* **Applying an unchanged mapping keeps the Detected status.** The indicator was a pair of state flags ("no
  profile picked, no manual mapping applied"), so **Apply mapping** on a mapping that was identical to the
  detected one turned it grey even though nothing had changed â€” it reported the click instead of the mapping.
  It now compares the mapping **in effect** with the mapping detection would choose for that worksheet, by
  meaning rather than by object: trimmed, case-folded headers, blank ("not assigned") values ignored, fields
  present on one side only a difference. Applying exactly what detection found stores no override at all, so
  the status stays blue and nothing is written; changing one or more assignments turns it grey; putting the
  detected assignments back â€” or picking **Detected** â€” turns it blue again.
* **Detected and a saved profile are two independent views, and picking one only changes what is shown.** The
  Fields screen's chips used to *apply* a profile to the worksheet on screen, and the indicator compared state
  flags, so it was never clear whether a profile had been picked or a mapping merely matched. Picking **Detected**
  or a profile now **switches which configuration is displayed and used**: Detected is derived from the workbook's
  own headers whenever it is needed and is never written to, and each saved profile keeps its own record. A switch
  in either direction copies, merges and rewrites nothing, so Detected â†’ profile â†’ Detected â†’ profile shows each
  configuration exactly as it was, and the draft, the reports and the "mapping in effect" line follow the view.
  Applying a mapping on top of the selected view stays a separate, explicit action; switching the view drops the
  edits that belonged to the previous one (they are this workbook's session state, not saved data), and **Save
  profile** is how an edit is kept.
* **A saved profile holds one mapping per worksheet, and saving one carries the others forward.** A profile was a
  single column set, so saving it while `LIST` was on screen made `LIST`'s header names the mapping of **every**
  worksheet: the other worksheets were re-resolved with them, their own detected mappings were lost, and `LIST`'s
  blank fields appeared as unmapped everywhere. A profile is now a snapshot of **several worksheets**, keyed by
  worksheet identity (the worksheet's package part), because two worksheets rarely share a header row. Saving a
  new profile records the worksheet on screen with the user's edits and **every other worksheet with the mapping
  it already has** (the selected profile's entry, or its own detected headers), so untouched worksheets are
  inherited rather than reset or overwritten, an empty value stays where it was cleared, and the worksheet on
  screen is never a template for the others. Deriving a profile from another profile keeps that profile's
  customisations and replaces only the worksheets actually edited. A worksheet a profile says nothing about (one
  added later, or another workbook's) is read with its own detected headers instead of borrowing a mapping.
  Deleting a profile changes no worksheet: the view falls back to Detected. `profiles.json` is now schema 2 (one
  entry per worksheet; a schema-1 profile still loads as the single column set it was), and
  `files/mappings/workbook-mappings.json` remembers the selected view and the per-worksheet edits, migrating a
  file written by the previous build (one column set for the whole workbook) to the worksheets that really have
  those headers.
* **A price, a page count or an identifier can no longer look like a date.** The display decides what a
  number means from the cell's **own number format** (`xl/styles.xml` + the cell's style index), never from
  its value: `45292` is a date only in a date-formatted cell, and a bare number in a `General` or integer
  cell keeps its digits. Text cells are never formatted, even when their style says otherwise, and invalid
  or ambiguous values (`0`, Excel's fictitious `1900-02-29`) keep their stored text rather than being
  shifted to a neighbouring day.
* **The Images tab's catalogue filter keeps working with formatted columns.** Its value list and its
  matching now use the same displayed text the Catalogue table shows, so a date filter still finds the rows
  it lists.

### Verified

* 781 JVM tests, 0 failures, 7 skipped by design (4 opt-in live provider checks and 3 real-workbook tests
  whose 40 MB files are not committed), across 94 result classes. 64 of them are new for this release: the
  Gallery worksheet filter 11, date and percentage formatting 11, the formatting workbook 4, the Default
  Order mapping and its timeline tie-break 10, the persisted views 8, the rescan state 4, the cell dialog and
  mapping precedence 5, the hyperlink parser 7, the real-workbook date/order/link checks 3 and the 1904
  date-system case 1, minus one test for the removed import timeline, plus 26 for the source-preview and
  mapping-persistence corrections (`SourceWindowFormattingTest` 14, `MappingPersistenceTest` 11 and the real
  workbook reading identically in both table views 1), 29 for the Detected-status and per-worksheet-mapping
  corrections (`DetectedStatusTest` 14, `WorksheetMappingIsolationTest` 12, three more in
  `MappingPersistenceTest`), and 5 more for this round: `ProfileViewSwitchingTest` 7 (the switching matrix:
  Detected â†’ profile â†’ Detected â†’ profile, two profiles in turn, the draft following the view, no profile
  changing) plus `WorksheetMappingIsolationTest` 11 and `DetectedStatusTest` 14 rewritten around profile
  inheritance (`MappingPersistenceTest` 13 covers the view, the edits and both migrations). The regression
  suite â€” health, gallery, analytics, timeline, catalogue, source navigation, rescan and the persisted
  views â€” still passes unchanged.
* The new date and percentage reading was run against the real `test_excel/doujin.xlsx`: its `å‡ºç‰ˆæ—¥ä»˜` and
  `è³¼å…¥æ—¥` columns are `yyyy"å¹´"m"æœˆ"d"æ—¥"`-formatted serials and display as dates, while `ä¾¡æ ¼ï¼ˆç¨…è¾¼ï¼‰` and
  `ãƒšãƒ¼ã‚¸æ•°` stay numeric. Its `é€šã—ç•ªå·` column is detected as the Default order and really does order the
  records that share a publication date, and its stored hyperlinks (the circle column links to a shop search)
  reach the right cells while the cells keep their own text.
* The workbook's date system is honoured everywhere a serial is read â€” the table, the date-column preview,
  both timelines, the analytics' year buckets and the source preview â€” so a 1904 workbook cannot show one year
  in the table and another on the timeline (pinned by a dedicated test).
* The source preview was checked against the same real `test_excel/doujin.xlsx`: every column of a record reads
  identically in the Catalogue table and in the window, and the workbook's stored hyperlinks reach the same
  cells in both.
* Debug build: BUILD SUCCESSFUL, version 213 / 2.4.0.

### Known limitations

* The formatted text is a **display** value: the CSV export, the catalogue filters' identity and the
  canonical catalog keep the stored text, in both views alike. A filter is chosen from what the table shows,
  so two source values that display identically are one filter chip.
* Percentages are capped at two decimals even when a format asks for more (`0.00000%` still shows two), and
  are not padded when a format asks for fewer, so the two-decimal rule is applied to the value rather than
  copied from the format's own precision.
* The two-decimal rule reaches a workbook cell only when its number format declares decimals (`0.00`,
  `#,##0.00`); `General` and integer cells are shown exactly as stored, because an integer cell is as likely
  an identifier as a measurement. The workbook's own currency symbol is not re-rendered â€” the table shows the
  number, as it always did.
* Only `http`, `https` and `mailto` destinations are handed to the browser, and only `Sheet!Cell` locations
  are followed inside the workbook. A `file://` link, a defined name or a scheme with no handler keeps its
  value and its link, but **Open Link** is not offered for it.
* The cell dialog, the source preview's cells and the Fields buttons were compiled and covered by state tests,
  but not tapped through on a device; the dialog's clipboard and browser calls need an Android runtime and are
  not unit-tested.
* The source preview draws at most 96 columns of a worksheet (and says how many it left out), and a hyperlink
  whose `ref` covers more than 1024 cells is attached to that range's first cell rather than expanded.
* The stored mapping is the one that was **applied**; a draft that was never applied is not remembered. It is
  stored per worksheet, so each worksheet of a workbook keeps its own mapping â€” and a mapping identical to what
  the selected view already gives that worksheet is not stored at all, because it changes nothing.
* Switching the view drops the edits applied on top of the previous view: they are the session state of one
  configuration, not saved data, so an edit that should outlive a switch has to be saved as a profile.
* A profile covers the worksheets it was saved with. A worksheet it has no entry for (one added to the workbook
  later, or another workbook's) is read with its own detected headers rather than with a column set borrowed
  from a sibling, so reusing a profile on a different workbook is safe but not automatic.
* When a profile whose mapping is exactly detection's is selected, the **Detected** chip stays blue (the mapping
  in effect *is* detection) while that profile's chip is also highlighted (it is the selected view): both
  statements are true, and the line under the chips names the view that is showing.
* The Images and Catalogue worksheet picker is session state: it is not remembered per workbook the way the
  three Insights scopes are.
* None of the layout (the gallery's worksheet bar, the picker, the table cells, the source window's cells, the
  Fields buttons) was driven on a device or an emulator, and no screen width was measured.

**Version code/name: 215 / 2.4.0.**

## v2.4.0-dev.4 (2026-10-31)

### Fixed

* **The timeline kept the previous mode's scroll position.** Switching from Published to Acquired (or back)
  left the list where the other dataset had been scrolled to. The list now owns its `LazyListState` and
  returns to the first item when the meaning, the ordering or the filter actually changes â€” and only then,
  so a rebuilt report, a thumbnail or any other recomposition no longer moves the user.
* **The Insights count disappeared when a spreadsheet was opened.** The section entry rendered its count
  inline (`Insights (3)`) and dropped it entirely at zero or while the report was being (re)built, and one
  line could not hold the name and the count together. Each directory entry now has two lines â€” the name on
  top, the count underneath â€” and the count is never hidden: `0 errors`, `1 error`, `2 errors`, `readingâ€¦`
  while a build runs, `â€”` when no report exists yet. It is a separate `Text`, so opening, reloading or
  replacing a workbook cannot push it out of the entry.
* **The gallery's search bar was crowded against its filters.** The bar now has its own margins, a hairline
  in the palette's line colour separates it from the chip rows, and the chips keep their own spacing. No
  font size changed and no filter behaves differently.

### Added

* **Timeline filtering.** A text search over the record's own fields (title, circle, artist, product ID and
  the worksheet row) and a year range chosen from *Years: â€¦* (from/to, either bound may be open, and
  `Any year` clears it). The filter bar shows how many records are shown out of how many, a **Clear filters**
  action appears only while a filter is active, an empty result says "No record matches this filter", and the
  two date meanings keep **separate** filters, so a search typed for Published cannot silently narrow
  Acquired. A date range never judges a record whose own date is unknown: those records are excluded from
  the range and counted in an explicit line, never given a year that fits.
* **Worksheet filters for Health and Analytics.** A single bar on each report â€” *Worksheets: All worksheets /
  LIST / 2 of 3 worksheets* â€” opens a picker of the workbook's own worksheets (with each one's record count
  and hidden state). All, one, or any combination can be selected, "All worksheets" is always one tap away,
  and the picker edits a draft until **Use these worksheets** is pressed.
  The scope is a filter on the **calculation**, not on the rendering: the report and the analytics are
  recomputed from the canonical catalog for exactly those worksheets, so summary tiles, error/warning/info
  counts, category chips, duplicate groups, distributions, the year breakdown and the record/cover totals all
  come from the same scoped record list. Health and Analytics each keep their **own** scope; changing one
  never changes the other. A selection stored for a worksheet that no longer exists is reconciled (the
  surviving sheets are kept, and a selection that matches nothing falls back to *All worksheets*), and a
  different workbook starts from *All worksheets*.
* **The Insights directory order** is now **Health â†’ Analytics â†’ Gallery â†’ Timeline â†’ Fields**: the two
  analysis screens sit together and the two browsing screens sit together, with the mapping screen last
  because it configures the data the others read.

### Changed

* **The directory count is textual for Insights** (`3 errors`, `0 errors`) because it counts confirmed
  problems rather than rows; the other three entries keep their plain item counts. The meaning of the count
  did not change.
* **The year picker offers only years the timeline really has**, so a range cannot be set to a year that
  would produce an empty result by construction.

### Verified

* 658 JVM tests, 0 failures, 7 skipped by design (4 opt-in live provider checks and the 3 real-workbook tests
  whose 40 MB files are not committed), across 81 result classes. 37 of them are new: the timeline filter 10,
  worksheet scope 5, the scoped reports 8, the filter/scope/count state 11, and the directory order 3.
* Debug build: BUILD SUCCESSFUL, version 212 / 2.4.0-dev.4.

### Known limitations

* The timeline filter is a text search plus a year range; there is no per-column filter and no month-level
  range, because the timeline is grouped by year and a record's date may only be known to the year.
* A worksheet that carries no catalogue table can be selected, but it contributes no records â€” the picker
  says so ("no catalogue table") instead of hiding it.
* Health and Analytics do not share a stored preference: opening the app again starts both at *All
  worksheets*, and the choice is not persisted per workbook.
* None of the new layout â€” the two-line directory entries, the gallery divider, the timeline filter bar, the
  worksheet picker â€” was driven on a device or an emulator, and no screen width was measured.

**Version code/name: 212 / 2.4.0-dev.4.**

## v2.4.0-dev.3 (2026-10-31)

### Fixed

* **Incomplete worksheet column display.** A worksheet was drawn as the sixteen columns nearest the verified
  cell, so a wide sheet (the sample `LIST` has 24, Aâ€“X) silently ended at column P: `ãƒ„ã‚¤ãƒƒã‚¿ãƒ¼`,
  `ãƒ„ã‚¤ãƒƒã‚¿ãƒ¼ (ãƒ¦ãƒ¼ã‚¶ãƒ¼å)`, `ãƒŸã‚¹ã‚­ãƒ¼` and `ãƒŸã‚¹ã‚­ãƒ¼ (ãƒ¦ãƒ¼ã‚¶ãƒ¼å)` were unreachable. The source view now draws
  every column the worksheet uses, at a hard bound far above any real catalogue, and says how many columns it
  left out when a sheet is wider than that instead of pretending it ended. The view keeps Excel's own column
  letters, so a value can still be read as a cell reference.
* **A blank header no longer shifts a column.** The reader built its column list from the header cells that
  had text, so a blank header cell between two named columns dropped that column and moved every value to its
  right one position to the left. The columns between the first and the last header are kept, a gap column is
  kept when it carries data, and one without a header is labelled by its letter (`Column C`) â€” never printed
  as an empty name, and never mapped to a field by guesswork.
* **Directory labels are readable in full.** The four sections of the workbook preview â€” *Comments, Images,
  Catalogue, Insights* â€” were each given a quarter of one line, so their labels were truncated to
  `Catalogue (5â€¦`. They now wrap onto a second line at the same font size: nothing is shrunk, no card inside
  the sections changes, and the tab, its count and the selected state stay where they were.
* **The Insights gallery starts at the top of a new order.** The grid kept the old scroll offset after a sort
  or filter changed the displayed order, so the first card of the new order could be off screen. The grid now
  owns its scroll state and returns to the first item when â€” and only when â€” the ordering or the filtered
  dataset actually changes. A thumbnail finishing its decode, a dialog opening or a report being rebuilt
  leaves the scroll untouched.

### Added

* **Cards or the whole worksheet.** The Catalogue tab has a **Cards / Table** switch. *Table* draws the
  worksheet: every column in the workbook's own order, Excel's letter above each header, the row number frozen
  on the left, one horizontal scroll shared by the header row and every data row so a header can never sit
  above another column's values, lazy rows for long sheets, and a row number that opens that row in the
  verified source view. Switching worksheet switches the columns to that worksheet's own structure.
* **Published and Acquired date columns, chosen by you.** *Insights â†’ Timeline â†’ Choose date columnsâ€¦* assigns
  two independent columns per worksheet. The dialog names the workbook and the worksheet, lists the
  worksheet's own columns with their letters, previews a few real values (`43465 â†’ 2018-12-31`), reports how
  many of them can be read and how many cells are blank, and marks a column whose header has vanished or
  become duplicated instead of reading it. Nothing is pre-selected from a header name, and a workbook with no
  assignment shows a setup state that says how to configure it. The choice is scoped to the workbook (its
  SHA-256) **and** the worksheet, stored as `files/mappings/dates.json` â€” header names, not positions â€” so it
  survives a restart, a rescan and a profile change, and never follows a shared profile into another workbook.
  The two timelines never borrow each other's column.
* **Excel date serials are read in the columns you chose.** A bare number is still not a date on its own; in a
  configured date column it is an Excel serial (1927 onwards, with Excel's 1900-leap-year base), so a workbook
  that stores `43465` gets a real date. A four-digit value remains a year.

### Changed

* **The Published timeline no longer comes from the mapped "Publication date" field.** It reads only the
  column you assigned, so a workbook whose header merely looks like a date has no timeline until you say which
  column it is. Selected-record health checks and the year distribution still use the mapped field, where an
  Excel serial is now accepted as a date too.
* **Acquired is a real timeline when it is configured,** instead of a permanent "unavailable": the reason it
  was unavailable was that the app does not record an acquisition date, and now the workbook supplies one if
  you point at it. *Imported* is unchanged and still comes from the saved-workbook library.
* **A wide worksheet is no longer shortened by the source view** â€” the window's cap went from 16 columns to a
  bound no real catalogue reaches, and the number of undrawn columns is reported rather than implied.

### Verified

* 621 JVM tests, 0 failures, 7 skipped by design (4 opt-in live provider checks and the 3 real-workbook tests
  whose 40 MB files are not committed), across 76 result classes. 47 of them are new: worksheet columns 7,
  date columns 17, the date dialog state 9, the gallery scroll key 6, the sample workbook 5, and 3 more
  timeline cases.
* The supplied sample workbook `test_excel/doujin.xlsx` (40 MB, not committed) is used by
  `SampleWorkbookColumnsTest`: `LIST` reads as 24 columns Aâ€“X with 24 values on every record, the Twitter and
  Misskey columns resolve to S/T/U/V, `å‡ºç‰ˆæ—¥ä»˜` (G) and `è³¼å…¥æ—¥` (R) are readable date columns, and the source
  window draws the whole width.
* Debug build: BUILD SUCCESSFUL, version 211 / 2.4.0-dev.3.

### Known limitations

* The date-column dialog is per worksheet: a workbook with four catalogue worksheets needs four assignments
  (the dialog switches between them, and each keeps its own two columns).
* A column whose header cell is blank is visible and readable in *Table*, but it cannot be *mapped* to a field:
  a profile stores header names, and an unnamed column has no name to store.
* The hard column bound of the source window (96) is not reachable by any real catalogue, but a sheet wider
  than that still reports the columns it did not draw instead of drawing all of them.
* Opening a card in the gallery and returning keeps the scroll, but leaving the Insights sub-tab and coming
  back rebuilds the panel and starts at the top.
* The five Insights screens still cover one workbook at a time, group the timeline by year, and do not split
  Artist values on separators.
* None of the new layout â€” the two-line section labels, the worksheet table, the date dialog â€” was driven on a
  device or an emulator, and no screen width was measured.

**Version code/name: 211 / 2.4.0-dev.3.**

## v2.4.0-dev.2 (2026-10-30)

### Added

* **Spreadsheet health report.** *Insights \u2192 Health* checks the workbook that was actually parsed and lists
  what is wrong with it: worksheets that could not be read, records with no title, missing optional values,
  URLs and dates that cannot be read, duplicate product IDs, rows that look like duplicates, note references
  that point at an image the package does not contain, media nothing points at, and records with no verified
  cover. Every finding names the worksheet row or image it is about, states the evidence, suggests what to do,
  and is classified as a confirmed problem or information. Nothing is repaired and the file is not touched.
* **Visual spreadsheet browser.** *Insights \u2192 Gallery* shows one card per record with its cover, title,
  Circle, Artist, product ID and source row. It filters by cover state, missing title, missing metadata and
  free text, sorts by title, Circle, Artist, source order or cover, and shows a placeholder \u2014 not a padded
  card \u2014 when a cover is missing or unresolved.
* **Column mapping profiles.** *Insights \u2192 Fields* maps a worksheet's own headers to the app's fields (Title,
  Circle, Artist, Product ID, URL, Price, Description, Publication date, Tags), previews the records before they
  are applied, reports headers that are missing, duplicated or shared, and saves a profile as **header names**
  rather than column positions, so it survives a column being inserted. Saved profiles live in the app's files
  and can be reused on another workbook; Circle and Artist are never merged.
* **Collection analytics.** *Insights \u2192 Analytics* counts records, worksheets, verified covers, missing
  metadata, duplicate product IDs and possible duplicates, and groups by Circle, Artist, tag, worksheet and
  publication year. The counting rules are printed on the screen.
* **Collection timeline.** *Insights \u2192 Timeline* groups records by publication year, newest or oldest first,
  and keeps records whose date is absent or unreadable in an explicit *No date known* group instead of dropping
  them.

### Changed

* **One catalog behind all five screens.** They read a single `WorkbookCatalog` built from the analysis the
  preview already ran: one record identity (worksheet part + row), the same verified image associations, the
  same mapped values. A finding, a gallery card, an analytics bucket and a timeline entry all point at the same
  record, and every one of them can open that record's worksheet row.
* **A mapping change rebuilds the reports instead of leaving them stale,** and a rescan drops them until they
  are rebuilt. A build that finishes after you switched workbooks is discarded.
* **The workbook stays read-only.** A mapping profile is app data, never an edit to the workbook, and no macro
  is run.
* **The three date meanings stay apart.** Publication dates come only from the mapped date column; *Acquired*
  says it is unavailable because the app does not record an acquisition date; *Imported* uses the saved-workbook
  library's import time and says so when the workbook was only staged.

### Verified

* 574 JVM tests, 0 failures (4 opt-in live catalogue checks skipped by design), 87 of them new: column mapping
  14, the profile store 5, the shared catalog 9, the health report 18, analytics 9, the timeline 12, the gallery
  10 and the Insights state 10.
* Debug build: BUILD SUCCESSFUL, version 210 / 2.4.0-dev.2.

### Known limitations

* The report can only check what the parser can see. A picture anchored to a drawing has no record association,
  so its record is reported as having no verified cover rather than as broken.
* Artist values are counted exactly as the workbook writes them; they are not split on separators. Tags are
  split on commas and ideographic commas because a tag column is a list.
* Analytics and the timeline cover one workbook at a time: there is no cross-workbook total yet.
* The timeline groups by year, not by month, and has no acquisition mode for the reason above.
* The Insights screens were not driven on a device or an emulator: their layout, the scrolling and the dialogs
  are compiled and unit-tested, not tapped through.

**Version code/name: 210 / 2.4.0-dev.2.**

## v2.4.0-dev.1 (2026-10-30)

### Added

* **Go to source.** An image with a verified association opens its worksheet cell from the Images list, from
  the full-screen image view and from a comment's detail; a catalogue record opens its verified row. The view
  shows Excel's own column letters, the surrounding rows, the catalogue header when the sheet is a table, and
  a clear highlight on the verified row and cell, so a location can be checked instead of trusted.
* **A location is shown only when the workbook proves it.** Images that no note references, notes whose
  image is missing, and records whose worksheet is no longer in the workbook each say so. When one note
  states more than one location, all of them are listed and you choose: the app never silently picks one.
* **A missing workbook is reported, not faked.** If the file was moved or deleted after the preview opened,
  the app says the workbook is no longer available instead of drawing an empty grid that would look like an
  empty row.

### Changed

* **Your place is kept.** Opening a source location leaves the section you were in, the worksheet filter, the
  image search, the association and catalogue filters and the sort order untouched, and closing it returns
  to the same list.
* **Worksheet names are matched exactly as the workbook spells them,** including spaces, punctuation,
  Unicode and trailing spaces, and each location is resolved through the worksheet's own part in the package
  rather than through its display name.
* **Sheets are read once.** The cells decoded for one location are reused for the next location in the same
  sheet, and a read that finishes after you switched workbooks is discarded instead of painted over the
  current one.

### Verified

* 487 JVM tests, 0 failures (4 opt-in live catalogue checks skipped by design), including 17 new tests for
  source mapping, worksheet identity, the worksheet window and the state transitions.
* Debug build: BUILD SUCCESSFUL, version 209 / 2.4.0-dev.1.

### Known limitations

* The source view is a window of six rows either side of the verified row and the sixteen columns nearest
  the verified cell, not a spreadsheet. Rows and columns outside it are not shown.
* A picture anchored to a drawing rather than to a note has no cell association, so it has no source
  location. The Images list already labels it as having no association found.
* Threaded comments are still reported but not shown, and only legacy note pictures carry an image.
* The new screens were not driven on a device or an emulator: the placement of the source buttons, the
  highlight and the scrolling are compiled and unit-tested, not tapped through.

**Version code/name: 209 / 2.4.0-dev.1.**
