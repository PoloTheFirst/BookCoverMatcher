# Changelog

All notable changes are recorded here. Versions follow the project's user-visible feature notes.

## 2.2.1 — 2026-10-22

Design correction for the online provider layer. 2.2.0 shipped two providers, both bibliographic
catalogues, which is not the workflow this app exists for (Japanese doujinshi, commercial doujinshi,
secondhand listings, digital works) and which gave the panel no way to say which provider had
actually been asked. The saved-data model, the offline matcher and the bookmark rules are unchanged.

### Added

* **Provider expansion — five providers declared, three actually queried.** `ProviderCatalog` lists
  **Open Library**, **Google Books**, **Melonbooks**, **Suruga-ya** and **DLsite**; the controller
  searches the first two plus DLsite concurrently, each with its own 20-second deadline.
  * **DLsite adapter** (`DlsiteSource` + `DlsiteResponseParser` + `HtmlText`): public books search
    page, Japanese query percent-encoded as UTF-8, reading the work id (`RJ…`), title, circle, the
    shop's *current* price and the 240×240 cover. No HTML dependency; the parser is fixture-tested
    against captured pages in `app/src/test/resources/fixtures`.
  * **Melonbooks and Suruga-ya are browser-only.** Both answered HTTP 403 to `robots.txt` *and* to
    their search URL, with a browser user agent and with the app's own (measured 2026-10-22). The app
    sends them no request at all; their provider row opens the shop's own search page instead.
  * **Per-provider status rows** (`ProviderStatusUi`): results / no match / failure / manual, so a
    partial search can never read as a complete one and a browser-only provider is never counted.
  * Result cards name their provider; a shop listing shows the price the shop printed, while
    availability stays `—` because no provider sends it.

### Changed

* `OnlineFailure.BLOCKED` classifies HTTP 401/403/451 instead of calling them a bad response.
* A shop listing ranks `RANK_MARKETPLACE` above a catalogue record (`RANK_CATALOGUE`); provider
  identity (`dl:rj…`) keeps a DLsite work and a library record of a similar title as two results.
* Result cards show the provider chip where they used to show only the domain chip.
* Version code/name: **203 / 2.2.1**.

### Verified

* JVM: **184 tests**, 0 failures (`testDebugUnitTest`; 3 live checks skipped by design).
* Live providers (`-DonlineCheck=1`): **184 tests**, 0 failures and all three live checks executed —
  Open Library returned real records, **DLsite returned real works for `東方`** (ids, Japanese
  titles, circles, `924円`-style prices, `img.dlsite.jp` covers, `dlsite.com` product URLs), Google
  Books was found or correctly reported `RATE_LIMITED`.
* Provider access probe (2026-10-22): Melonbooks and Suruga-ya answered **HTTP 403** to `robots.txt`
  and to their search URL, with both a browser UA and the app UA. Browser hand-off only.
* `assembleDebug` green.

### Known limitations

* **Melonbooks and Suruga-ya cannot be searched by the app.** Both refuse automated clients (HTTP 403,
  `robots.txt` included); their rows open a browser search and are never counted as answered.
* **DLsite results are its books section, first page only.** The listing has no stock, description or
  rating; the app shows the shop's current price and nothing it did not receive.
* Visual image-similarity search is not implemented; a provider text search is not a visual match.


## 2.2.0 — 2026-10-22

Corrective release. Supersedes the unreleased 2.3.0 working-tree iteration; nothing under that label
was published, and its "folder containing five items" model was removed.

### Fixed

* **Online search could never be used.** The online controller's `StateFlow` was not mirrored into
  `UiState.online`, so the panel always rendered the default off state and returned early. The
  controller is now collected into `UiState`; the persisted switch and the UI agree.
* **Offline saves had the wrong data shape.** A saved scan used to become a new folder containing
  five independently removable workbook rows. It now becomes one **session** inside a folder the
  user chooses. Scans never create folders, folders can hold many sessions/results, and workbook
  rows are read-only comparison output.
* **Bookmarks could not be ordered inside a session** because there were no sessions. Bookmarked
  results are now shown first inside their session; bookmark membership and folder membership stay
  independent.
* **OCR geometry could be misaligned**: a Lens block containing an internal newline shifted
  `block_heights` relative to lines. `lens_bridge.py` now emits one line per block, and
  `OcrQueryExtractor` pairs each height with its source line before filtering.
* **Instrumentation contexts with a null `applicationContext`** made the embedded Python runtime
  report `RUNTIME_MISSING`; `PythonRuntime` now falls back to the supplied context.
* **Catalogue result count** is the merged, unique, displayable list; failures and placeholders are
  not counted.

### Added

* Bottom navigation with **Home**, **Search**, **Wishlist**, **More**.
* Persistent folder → session → results model (`records.json` **v3**) with:
  * idempotent reserved `Bookmarks` folder;
  * `sessions`, `entries` and direct `memberships`;
  * migrations from v1 records (unfiled sessions, no invented folder) and v2 collections (workbook
    rows repaired into sessions; bookmarks preserved; legacy payload retained).
* Session screen with comparison-only offline rows and bookmarks-first ordering.
* Google Books as a second real catalogue, merged with Open Library. Per-catalogue failures are
  reported (`CatalogueUi.Found.note`), and a total failure is `CatalogueUi.Failed`.
* **Search Results tabs** as the workflow image draws them: `Workbook (n) / Online (n) /
  Bookmarks (n)` with live counts, `Found n workbook match(es)` above the comparison, the bulk
  `Add entire result profile to wishlist` on the Workbook tab, and `Bookmark all results` plus a
  collection shortcut on the Bookmarks tab. Built on a shared `SegmentedTabRow` / `SegmentedTab`
  component that the Wishlist sections reuse.
* Folder picker for both individual adds and session saves; explicit "create new folder" remains a
  user action.
* `Bookmark all results`, `Add entire result profile to wishlist`, and per-card bookmark/wishlist
  actions with real counts and undo.
* Opt-in live catalogue tests (`-DonlineCheck=1`) and three on-device tests (OCR, catalogues,
  controller).

### Changed

* `WishlistRepository` is now folder/session based; the v1 `add/rename/delete` record API was
  removed (its migration path is retained).
* `WishlistOverlay` was replaced by the Wishlist destination; the header star button was removed.
* Online panel copy and settings say "catalogues" and name both providers; the "visual match" claim
  is explicitly absent.
* Version code/name: **202 / 2.2.0**.

### Verified

* JVM: **165 tests**, 0 failures (`testDebugUnitTest`, and with `-DonlineCheck=1`).
* Python bridge contract: `tools/lens_bridge_check.py` passes.
* On-device (emulator `Medium_Phone_API_37.0`, x86_64): **3/3** instrumented tests pass, including a
  real Lens OCR read of the bundled Hobbit cover and a real Open Library query through the
  controller.
* Debug APK builds; manual smoke test covered navigation, folder creation, bookmark creation,
  bookmark-to-folder, and persistence across restart.

### Known limitations

* Visual image-similarity search is not implemented; OCR + catalogue text search is not a visual
  match.
* Google Books can return HTTP 429 without a key; the app reports `RATE_LIMITED` (Open Library still
  works). No API key is shipped.
* Camera capture and a full workbook match were not exercised on the available emulator.
* The supplied workflow image is kept outside this repository
  (`.../Downloads/workflow_image.png`). Its Search screen - result tabs, `Bookmark all results`,
  bulk add, bookmarked-first ordering - is implemented; its profile cards are user-named folders with
  no built-in genre taxonomy, and the `Visual match`, price, availability and marketplace chips stay
  honest empty states because no provider in this build supplies them.
* Screenshots/interaction timings in the image (a back arrow inside the results screen, cover art
  inside profile cards) are approximated by the app's own components; the information structure, not
  the pixels, is what was matched.

## 2.1.0 — 2026-10-21

* Native Android rewrite with OpenCLIP offline matching, Excel picture extraction, CameraX and
  Auto Scan.
* Optional online panel: chrome-lens-py OCR + Open Library catalogue lookup, switched off by
  default.
* Wishlist records (a saved scan plus its five matches), thumbnails, shared `MiniJson` codec.
* 110 JVM tests at the time; OCR on a device remained unverified.

## 2.0.0 — 2026-10-20

* First native Kotlin/Compose rewrite of the v1 web prototype.

## 1.1.0 / 1.0.0

* Original web prototype releases (see the repository history).
