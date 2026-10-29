# Coding-Agent Implementation Prompt — BookCoverMatcher
## Online Search Results, Bookmarks, and Wishlist Profiles

> **Superseded for v2.2.0 (2026-10-22).** This file is kept as the historical task prompt. The
> implemented model is folder → session → results (see `README.md` §3); it is not the older
> "profiles of items" wording below. Where this prompt and the current README disagree, the README
> and the source are authoritative. The Phase 1 panel numbering (`1 · ...`, `2.5 · ...`) described
> below no longer exists in the UI: the Search destination now shows `Workbook (n) / Online (n) /
> Bookmarks (n)` tabs, and the numbered panel titles were removed.
>
> **Current development build: `v2.4.0-dev.2` (2026-10-30).** Since this prompt was written, the
> wishlist/bookmark model landed, v2.3.x added the offline workbook-comments preview, v2.4.0-dev.1
> added **verified source navigation** (image/catalogue record → worksheet cell or row, via
> `core/excel/comments/SourceLocation` + `SheetWindow` and the `WorkbookCommentsUi.source` overlay), and
> v2.4.0-dev.2 added the **workbook insights** built on one shared `core/catalog/` layer (`WorkbookCatalog`,
> `ColumnMappingProfile`, `HealthReportBuilder`, `CollectionAnalyticsBuilder`, `CollectionTimelineBuilder`,
> `GalleryView`) shown as the `InsightsTab` sub-tabs of the existing preview. Do not
> re-implement any of those as if they were missing; read `README.md`, `ARCHITECTURE.md`, `USER_GUIDE.md`
> and `CHANGELOG.md` first. Real workbook fixtures live in `test_excel/` and are read-only.

> **How to use this file.** Everything below is the prompt. Give it to the coding agent as the task
> description, together with `workflow_image.png` if you have it. It is self-contained: it does not
> depend on any previous conversation. Sections 2, 3 and 3A are a verified audit of this repository
> (re-checked **2026-10-22** at `HEAD cb8a253` *"Prepare release v2.1.0-dev.1"*, working tree with only
> `CHANGELOG.md` modified) — trust the repository over this document if they ever disagree, and say so
> in your report.
>
> **Scope decision (maintainer, 2026-10-22).** The work is split into **two phases** (§3A). Phase 1 is
> data, behaviour and the new actions on the **existing single-screen layout**; the bottom-navigation
> restructure is **Phase 2** and must not be started during Phase 1. Section 16 used to leave this as an
> open default; it is now decided.

---

## 0. Mission

Implement, in this repository, the **online-result bookmark + wishlist-profile** feature described in
§4–§11 and illustrated in `workflow_image.png`, then update the documentation (§12), add the tests (§11)
and produce the report (§15).

You are extending a working app, not redesigning it. The offline matcher (camera → OpenCLIP embedding →
`.xlsx`/`.xlsm` workbook range → top-5 ranking) must behave exactly as it does today: offline, with the
online switch off and the phone in airplane mode.

Do not stop after a plan, a data model, or a mock UI. Implement the whole feature as far as this
repository and environment allow, verify it, document it, and report honestly.

### Hard rules

1. **Do not rewrite the offline matcher, the Excel extraction, Auto Scan, or the CLIP pipeline** to make
   room for this feature.
2. **Preserve the existing convention:** one responsibility per Kotlin file, plain-JVM logic in
   `core/**` (no `android.*` imports, unit-tested on a desktop JVM), Android-dependent code in
   `data/`, `vision/`, `camera/`, `online/`, `ui/`. Keep the existing comment/KDoc style: precise,
   dry, states the "why" and states the limitation.
3. **No new dependencies** unless a feature cannot be built without one. Persistence in this app is
   hand-rolled JSON through `core/wishlist/MiniJson.kt` — there is no Room, no SQLDelight, no DataStore,
   no kotlinx-serialization, and adding one is the maintainer's decision, not yours.
4. **Never fabricate data.** No invented titles, prices, availability, thumbnails, URLs, providers or
   "visual match" verdicts. If a provider cannot do something, the UI must not claim it and the docs
   must say so. See §14.
5. **Backward compatibility.** Existing `records.json` data must survive the change untouched, and the
   migration must be covered by a test.
6. Do not claim a test passed, a build succeeded, or a device behaviour works if you did not observe it.
7. **Phase discipline (§3A).** Phase 1 changes no navigation and no screen ownership: panels 1 / 2 / 2.5 / 3
   and the wishlist overlay keep their current host composables and the panel numbering keeps working.
   Do not begin Phase 2 in the same pass as Phase 1; finish, test and report Phase 1 first.

---

## 1. Reference image

`workflow_image.png` is the primary visual/workflow reference — read it in **both** phases, but it is
the authority on structure mainly in Phase 2 (§3A). **If the image is not attached or not readable, say
so explicitly in the final report (§15)** and implement from the written requirements in §4–§11 plus the
transcription in §10. Do not invent details you could not see, and do not claim you inspected a file you
could not open.

Repository fact: the app ships **no image assets at all** — the workflow picture was never committed.
The original lives outside the repository at
`C:\Users\ASUS\Downloads\workflow_image.png` (1 780 862 bytes, checked 2026-10-22), and a copy is placed
at the repository root as `workflow_image.png` (untracked; the maintainer may ignore or delete it) so it
can be opened without leaving the project. If neither path resolves for you, say so and work from §10.

---

## 2. Verified repository baseline

Do not trust the repository's own prose as a source of truth without checking it: `README.md`,
`ONLINE_SEARCH.md` and `CHANGELOG.md` have drifted behind the code before, and `CHANGELOG.md` is not
even committed (§2.4). Re-verify with `git log`, `git show HEAD:<file>` and the compiler anything you
intend to repeat as a fact.

Toolchain: Kotlin **2.2.10**, AGP **9.4.1**, Gradle **9.8.0** wrapper, `compileSdk 35`, `minSdk 26`,
`targetSdk 35`, `jvmTarget 17`, Jetpack Compose (BOM 2024.10.01, Material 3, Material Icons Core),
CameraX 1.3.4, ONNX Runtime Android 1.20.0, Chaquopy **17.1.0** (embedded CPython **3.12**,
`chrome-lens-py==3.8.2`, `protobuf==6.33.6`), coroutines 1.9.0, JUnit **4.13.2** (unit tests only —
there is **no `androidTest/` source set**, no Espresso, no Robolectric).
`app/build.gradle.kts`: `versionCode 201`, `versionName "2.1.0"`, `abiFilters ["arm64-v8a","x86_64"]`,
`lint { abortOnError = false }`, release signed with the debug key.
Manifest: `CAMERA` + `INTERNET` (the latter used only by panel 2.5), `allowBackup="false"`, portrait, and
only two hosts are ever contacted (`lens.google.com` through the embedded package, `openlibrary.org` /
`covers.openlibrary.org`).

```bash
./gradlew testDebugUnitTest     # 110 JVM unit tests, no emulator needed
./gradlew assembleDebug         # APK (Chaquopy needs CPython 3.12 on the build host)
python tools/lens_bridge_check.py          # bridge contract, LensAPI stubbed, no network
python tools/online_search_check.py "The Hobbit"   # exact catalogue request, live
```

### 2.1 Wishlist today (`core/wishlist/`)

* Storage: `File(app.filesDir, "wishlist")/records.json` + one JPEG per thumbnail under `thumbs/`,
  written with `MiniJson.write`, temp file + rename; an unreadable file is renamed
  `records.json.corrupt` and the wishlist reads as empty. Root JSON is `{"version": 1, "records": []}`.
* `class WishlistRecord(id, name, createdAtMillis, scanThumbName, matches: List<WishlistMatch>)`
* `class WishlistMatch(cell, cosine, percent, source, thumbName)` — **workbook-only fields**.
* `class NewWishlistMatch(cell, cosine, percent, source, thumbJpeg)` for saves.
* API: `load()`, `add(scanThumbJpeg, matches)` (auto-names the record `#<count+1>`), `rename(id, name)`
  (blank keeps the old name), `delete(id)` (also deletes its thumbnails), `thumbFile(name)` (flattens
  the name so a path cannot escape `thumbs/`), plus `encode`/`decode` in a `companion object` holding
  `private const val VERSION = 1`.
* Not thread-safe by itself; `MainViewModel` calls it from a single-threaded executor
  (`wishlistExecutor` / `wishlistDispatcher`) and publishes on the main thread.
* `MainViewModel.addToWishlist()` saves **one record per scan** from `lastRanked` + `targetThumb`, then
  shows the green 5-second prompt (`showPrompt`, `Config.WISHLIST_PROMPT_MS = 5000L`) with Remove / ✕.
* Wishlist UI is a full-screen overlay (`ui/WishlistOverlay.kt`: `WishlistOverlay`,
  `WishlistRecordCard`, `MatchLine`), opened by `vm.openWishlist()` from the ⭐ header button. `UiState`
  fields: `wishlistOpen`, `wishlist: List<WishlistRecordUi>`, `pendingDeleteId`, `renaming: RenameUi?`.
* Existing coverage: `WishlistRepositoryTest` and `MiniJsonTest` inside
  `app/src/test/java/com/bookcovermatcher/core/PipelineTest.kt` (that file holds several small test
  classes — follow that pattern; temp dirs come from `Files.createTempDirectory("wl")`).

### 2.2 Online search today (v2.1.0 — committed in `cb8a253`, verified present in `git ls-files`)

* `online/OnlineSearchController.kt` — owns `MutableStateFlow<OnlineSearchUi>`; a plain class owned by
  `MainViewModel` (`val online = OnlineSearchController(app, viewModelScope, onMessage = ::showToast)`),
  **not** a second ViewModel. Holds `private var capture: ByteArray?` (current cover JPEG), a single
  `private var job: Job?`, and `bridgeChecked`. `setEnabled(on)` persists the switch, resets
  `ocr`/`catalogue`/`query`, and cancels `job`; `cancel()` cancels `job` and resets only the `Running`
  states; `shutdown()` runs from `onCleared()`. OCR timeout is 60 s. Covers are fetched one at a time
  after the text (`loadCovers`) and patched into `CatalogueUi.Found` **by index**.
* Two deliberately separate services: **A · OCR** through embedded `chrome-lens-py`
  (`online/ChromeLensPyBridge.kt`, `PythonRuntime.kt`, `OcrTextSource.kt`, `OcrResultParser.kt`,
  `OcrErrorMessages.kt`, `app/src/main/python/lens_bridge.py`) and **B · catalogue search** through
  `online/OpenLibraryBooksSource.kt` + `online/HttpJsonClient.kt` (the only socket, `https` only).
* **`chrome-lens-py` is OCR + translation only.** It returns no products, prices, shopping links or
  visual product matches; documented in `ONLINE_SEARCH.md` §2 and asserted by the `SUPPORTS` /
  `DOES_NOT_SUPPORT` lists in `lens_bridge.py`. The panel literally prints "OCR only, no product search".
* `core/online/ProductSearchResult(id, title, authors, publishedYear, publishers, pageCount, languages,
  coverUrl, detailUrl, source)` + `authorLine` / `yearText` / `publisherLine` / `metaLine`;
  `core/online/ProductSearchDataSource.kt` defines the contract, `ProductSearchOutcome` and
  `OnlineFailure` (`OFFLINE`, `TIMEOUT`, `RATE_LIMITED`, `SERVER_ERROR`, `BAD_RESPONSE`,
  `NOT_CONFIGURED`); `OpenLibraryResponseParser.kt` and `OcrQueryExtractor.kt` are pure JVM.
* `OnlineSearchUi.kt`: `BridgeUi` (Checking/Ready/Unavailable), `OcrUi` (Idle/Running/Done/NoText/
  Failed), `CatalogueUi` (Idle/Running/Found/Empty/Failed), `ProductResultUi(id, title, authors, meta,
  languages, coverJpeg, detailUrl, source)`, `data class OnlineSearchUi(enabled, bridge, ocr, query,
  catalogue, autoSearch, ocrLanguage, targetLanguage)`. `UiState.online` is the only online field.
* `data/OnlineSearchPrefs.kt` — `SharedPreferences` file `bookcover_online`; keys `online_enabled`
  (default **false**), `online_ocr_language`, `online_target_language`, `online_limit` (default 5, max
  10), `online_auto_search` (default true).
* `ui/OnlineSearchPanel.kt` — `Panel("2.5 · Online search")` with `Switch(checked = ui.enabled,
  onCheckedChange = controller::setEnabled)`, an early `return@Panel` while disabled, then `BridgeLine`,
  `OcrSection`, `CatalogueSection`. Always rendered by `ui/MainScreen.kt` between panels 2 and 3
  (`OnlineSearchPanel(ui.online, vm.online, actions.onOpenUrl)`).
* `ui/ProductResultCard.kt` — collapsed-only card (52×74 cover, title, authors, meta, languages) with
  exactly **one** action, `Open <source> page ↗` → `actions.onOpenUrl(item.detailUrl)`. No expansion
  state, no bookmark state, no wishlist state.
* Links open in the system browser through `AppScreen.openExternal`, which **refuses any URL that is not
  `https://`**; the app renders no third-party pages (no WebView, by design).
* `online/CoverImageLoader.kt` is a memory-only cover cache. `core/search/IndexCache.kt` caches workbook
  embeddings (`cacheDir/index`, keyed by workbook SHA-256 + range + model fingerprint, LRU 8). **No cache
  of online results exists, and no image fingerprint exists anywhere in the app.**

### 2.3 Screen structure today

One scrolling screen (`ui/MainScreen.kt` → `MainContent`): header (title + ⭐ wishlist button), **1 ·
Spreadsheet & range**, **2 · Results** (top-5 `ResultRow`s), **2.5 · Online search**, **3 · Wishlist**
(one "Add to Wishlist" button). Overlays are hosted in `ui/AppScreen.kt` (`WishlistOverlay`,
`WishlistPrompt`, `ToastHost`, `ConfirmDeleteDialog`, `RenameDialog`). Shared widgets:
`ui/components/Common.kt` (`Panel`, `FieldLabel`, `GradientButton`, `ButtonLabel`, `FileImage`,
`JpegImage`, `dashedBorder`); colours/gradients in `ui/theme/Theme.kt` (`Palette`: `Text`, `Muted`,
`Line`, `Accent`, `Accent2`, `Ok`, `Warn`, `Danger`, `Navy650`, `NavyGradient`, `AccentGradient`,
`YellowGradient`, …).

**There is no bottom navigation bar, no `More` screen, no profile screen and no genre grouping.**
Grepping the tree for `bookmark`, for `profile` as a domain word, or for `genre` returns nothing.

### 2.4 Documentation today

`README.md` (title `# BookCover Matcher — v2.1.0`; ToC at line 19; sections: What it does · Online
search (optional) · Honest status of this release · Quick start · How to use (incl. "Optional: online
search (panel 2.5)") · The Excel workflow (unchanged) · Why OpenCLIP + FAISS · Auto Scan · Reading a
match · Configuration · Project layout · Desktop tools · Tests · Limitations · Troubleshooting ·
Security and privacy · Credits). `CHANGELOG.md` is **stale in the history and is not Keep a Changelog**:
`git show HEAD:CHANGELOG.md` returns an **empty file**, and the only content anywhere is an uncommitted
49-line working copy titled `# Experimental Image Search Feature with Open Library` with
`## v2.1.0 (2026-10-21)` followed by `## Added` / `## Changed` / `## Fixed` / `## Tests` /
`## Notes and known issues`. Match *that* layout (confirm against the working copy you are given, not
against this sentence). `ONLINE_SEARCH.md` (the online feature's own
guide: what it does, the two halves table, why Open Library, the file map in §6, known limitations in
§7), `ONLINE_COVER_RECOGNITION_DIAGNOSTIC.md` (the two bridge defects and their §15 verification
status), and `CODING_AGENT_PROMPT.md` (this file, added in `cb8a253`). There is **no** user guide,
architecture guide, testing guide, provider-configuration guide or feature inventory — creating those is
part of this task (§12).

---

## 3. What the design assumes that the code does not have yet

Read this section twice. The feature request reads as if the app already had wishlist **profiles**,
**bookmarks** and a **session cache**. It has none of them:

| Design assumption | Reality in this repository |
| :--- | :--- |
| "Existing wishlist/profile system", "preserve the genre-based wishlist profiles" | No profiles, no genres. A wishlist record **is one scan** with its top-5 workbook matches, named `#1`, `#2`, … |
| "The reserved `Bookmarks` collection inside the existing wishlist infrastructure" | No bookmark code at all. No collection abstraction, only `List<WishlistRecord>`. |
| "Fix the online-search toggle if its state synchronisation is broken" | The toggle itself is wired correctly (`checked = ui.enabled`; `setEnabled` writes prefs and resets state). What *is* genuinely missing/fragile: results of a superseded capture are never invalidated by a fingerprint, cover patches are applied **by index** so a stale cover can land on the wrong card, and result items carry no per-item state. Verify first, and report what you actually found rather than "fixing" a phantom. |
| "Session cache per image, keyed by a reliable fingerprint" | Nothing like it exists. `IndexCache` caches workbook embeddings, not online results. |
| "Hybrid providers, shopping sites, visual match, price, availability" | One provider: Open Library — a library catalogue with no price, no stock, no buy link and no visual search. More providers are allowed only under §9 and the honesty rules of §14. |
| "Bottom navigation (Home / Search / Wishlist / More), profile grid, result-profile screens" | A single scrolling screen plus a full-screen wishlist overlay. |

The work therefore splits into three layers, and the maintainer has decided **which pass each layer
belongs to**:

* **Layer A — data & behaviour (testable on the JVM):** item/collection model, profiles, the
  reserved `Bookmarks` collection, bookmark ↔ wishlist independence, identity & dedup, migration, session
  cache, bulk add, the three outcomes.
* **Layer B — UI on top of the existing structure:** expandable result card with the three
  actions, profile-selection sheet, bookmarks view, custom-bookmark form, `Add result profile to
  wishlist`, indicators.
* **Layer C — navigation conformance to the image:** the image shows a
  bottom navigation bar (Home / Search / Wishlist / More), a Wishlist tab that is a grid of profile cards
  including the reserved `Bookmarks` card, and a Search tab holding the scan / workbook / online flow.

---

## 3A. Scope: two phases — the maintainer's decision (2026-10-22)

**Phase 1 = Layers A + B. Phase 2 = Layer C.** This is decided, not a default: do not re-litigate it, do
not "helpfully" start the navigation rework while writing Phase 1 code, and do not drop the parts of the
image this stack cannot back (that alternative was also rejected — see §10 and §16).

### Phase 1 — data, behaviour and integration (build first)

Keep the current screen shape exactly as it is today: **panels 1 / 2 / 2.5 / 3 in one scrolling screen
plus the wishlist overlay**. Phase 1 delivers, on that layout:

1. The persistent `Bookmarks` collection inside the existing wishlist store (§5, §6.1).
2. Backward-compatible v1 → v2 store and migration (§5).
3. Separate bookmark membership and wishlist membership, with the §8 removal semantics.
4. Individual `Bookmark` and `Add to wishlist` actions on the result card, and the bulk
   `Add result profile to wishlist` control (§6.2–§6.5).
5. Bulk addition of workbook matches **and** online results into a chosen wishlist profile.
6. All three outcomes of §7 — including adding a saved bookmark to a wishlist **after** the search
   session has expired or the app has restarted.
7. Manual custom-bookmark creation, and its later move into a profile (§6.6).
8. Genre/purpose-based wishlist profiles and collection management (§6.7) — reached through the existing
   overlay, not through new navigation.
9. Indicators, dedup, persistence, removal semantics, the session cache + fingerprint, the toggle and
   stale-response protection (§6.8, §6.9).
10. A dedicated **Bookmarks view** inside the existing wishlist overlay (a segmented control or a second
    list is fine — it must be a real, reachable view, not a TODO).

**Phase 1 acceptance gate** (all of these before Phase 2 starts):

* Every Phase 1 test in §11 passes; `./gradlew assembleDebug` succeeds, or the exact reason it cannot run
  here is reported.
* Outcomes 1, 2 and 3 each have a named passing test; the v1 → v2 migration has one.
* `git diff` shows **no** bottom navigation bar, no navigation host / tab scaffolding, no new screen
  owner, and the panel labels `1 · `, `2 · `, `2.5 · `, `3 · ` still render.
* Documentation describes Phase 1 as done and Phase 2 as not started (§12).

### Phase 2 — navigation and visual conformance (only after the gate)

Rebuild the presentation to match `workflow_image.png`, **reusing** the models, repositories, controller
and tested behaviour from Phase 1. Do not duplicate persistence and do not rewrite working business
logic to feed a new navigation graph — if Phase 1 logic does not fit the new screen, that is a Phase 1
bug to fix in place, with the tests as the safety net.

Before touching anything, write the mapping of what exists to where it goes, and keep it in the report:

| Today (verified) | Phase 2 destination |
| :--- | :--- |
| Header in `MainContent` (title + ⭐ `openWishlist`) | Top bar stays; ⭐ becomes the **Wishlist** tab |
| `1 · Spreadsheet & range` | **Search** tab: capture/upload + workbook + range |
| `2 · Top 5 matches` | **Search** tab, directly under the workbook picker |
| `2.5 · Online search` (panel + result cards) | **Search** tab (image screens 2–4) |
| `3 · Wishlist` (single "Add to Wishlist" button) | The bulk / per-item actions on **Search**; the saved list itself lives on **Wishlist** |
| `WishlistOverlay` (full screen, opened by ⭐) | The **Wishlist** tab: profile grid, `Bookmarks` card, `Create new profile`, bookmarks list |
| `WishlistPrompt` (5-second undo bar), `ToastHost`, `ConfirmDeleteDialog`, `RenameDialog` in `AppScreen` | Stay app-level, drawn **above** the nav bar |
| Nothing | **More** tab: `Create bookmark`, `Clear session cache`, online-search settings (toggle, OCR/target language), model setup, about/limitations |

What must still work at the end of Phase 2, unchanged: offline matching in airplane mode, workbook
`.xlsx`/`.xlsm` selection and the top-5 ranking, camera capture, Auto Scan, the 5-second undo prompt,
rename, delete-with-confirmation, the empty states, and the system back gesture (the overlay's
`BackHandler` behaviour has to be re-expressed as tab-level back, not silently deleted).

Phase 2 may add UI-level tests where the existing JUnit-4/JVM setup can reach the logic (state classes,
reducers, ordering), and must state plainly which Compose behaviour remains unverified because there is
no `androidTest`, Robolectric or Compose UI test harness in this project.


---

## 4. Four concepts, and the invariants between them

Keep these four separate in the model, the code and the UI naming.

**A. Online search result** — one discovered page/publication from a provider: title, URL, source domain,
thumbnail, description, author/artist/circle/publisher, product identifier, price, provider, match-type
info. An individual record; not a profile.

**B. Bookmark** — a persisted reference to an online result **or a manually entered URL**, kept so the
link can be found again without meaning to buy the item. Must survive session expiry, another cover being
chosen, and app restart.

**C. Wishlist item** — one item inside a wishlist profile; may come from a workbook match, an online
result, or a bookmark the user later promotes.

**D. Wishlist profile** — a user-named collection folder holding many items (the image groups by
genre/purpose: `My Wishlist`, `Favorites`, `Doujinshi`, …). Keep it a folder of items, never a flat list.

### Invariants (each one gets a test)

1. Never create a profile for a single result. A result belongs *inside* a profile.
2. Creating a bookmark never creates a profile and never adds anything to a regular profile.
3. An item may be bookmarked **and** wishlisted at once; the two states are independent.
4. Removing a bookmark never removes wishlist membership; removing an item from profile P never removes it
   from profile Q or from `Bookmarks`.
5. The same URL/product saved twice must not appear twice in one collection; bookmark and wishlist
   operations must be idempotent.
6. An individual card action must never add more than the one item it belongs to. Bulk add is a separate,
   clearly-labelled control.
7. Clearing the session cache must never delete a bookmark or a wishlist item.
8. A bookmarked result is promoted **within the online results of the profile it belongs to** (and in the
   live search list), not by globally reordering unrelated searches.

---

## 5. Data model and persistence (smallest backward-compatible step)

There is no database library, and §0 rule 3 forbids casually adding one, so extend the existing
`records.json` to **version 2** with `MiniJson`, keeping the current atomic write and the
`records.json.corrupt` quarantine. Suggested shape (adapt the names to the codebase; keep it flat and
readable; keep id generation injectable like today's `newId`):

```
{
  "version": 2,
  "collections": [
    { "id": "col_bookmarks", "name": "Bookmarks", "reserved": "BOOKMARKS",
      "kind": "RESERVED", "createdAt": 1760000000000 },
    { "id": "col_x1", "name": "Doujinshi", "kind": "PROFILE", "createdAt": 1760000000001 }
  ],
  "items": [
    { "id": "itm_…", "kind": "WORKBOOK_MATCH | ONLINE_RESULT | MANUAL_BOOKMARK",
      "title": "…", "url": "https://…", "sourceDomain": "openlibrary.org",
      "provider": "Open Library", "description": null, "authors": ["…"],
      "identifier": "OL27482W", "price": null, "availability": null,
      "matchType": "TEXT_QUERY | VISUAL | MANUAL | WORKBOOK", "matchTypeVerified": true,
      "cell": "C97", "cosine": 0.83, "percent": 91.4,       // workbook-only, null otherwise
      "thumb": "itm_….jpg", "createdAt": …, "fingerprint": "ol:OL27482W" }
  ],
  "memberships": [ { "itemId": "itm_…", "collectionId": "col_x1", "addedAt": …, "pinned": false } ],
  "legacy": [ /* the untouched v1 records, so nothing is lost */ ]
}
```

Rules:

* **Migration.** On `load()`, if `version` is missing or `1`, keep the old array verbatim (in `legacy`, or
  translated into `PROFILE` collections only where that does not lie about the data), create the reserved
  `Bookmarks` collection, and write `version: 2` only after a successful read. Never destroy the user's
  only copy. Add a test that loads a captured v1 payload and asserts every old field still round-trips.
* **Reserved collection.** `ensureReserved()` creates `Bookmarks` when absent, is idempotent, cannot be
  renamed away or duplicated, survives a corrupt-file quarantine, and its deletion (if offered at all)
  removes memberships only.
* **Identity / dedup** in a pure-JVM `ItemIdentity` helper. Prefer a provider identifier
  (`"ol:" + id` for Open Library); otherwise a normalised URL: force `https`, lowercase the host, drop the
  default port, trailing `/` and `#fragment`, and remove **only** well-known tracking parameters
  (`utm_*`, `fbclid`, `gclid`, `mc_eid`, …). Keep the original URL for opening. Never drop a parameter that
  can change the destination (`?pid=`, `?number=`, `?query=`, `?age=`). Do not merge distinct editions or
  variants because their titles match. Document the decision in code and in the docs.
* **Prune.** Delete an item only when it has no membership left anywhere; its thumbnail goes with it.
* Keep all repository I/O on the existing single-threaded `wishlistDispatcher` and publish derived UI state
  on the main thread, like `publishWishlist` does today.
* Do **not** invent workbook row ids, cosine values or similarity percentages for online or manual items —
  those fields stay null, and the UI shows `—` or nothing instead.

---

## 6. Feature requirements

### 6.1 Reserved `Bookmarks` collection

Create it when missing, never twice, reuse it across launches, persist across restarts. It holds every
saved link, from online results and from the manual form alike. It must stay a dedicated, clearly-labelled
view whatever its size — a long messy list is a reason for sorting/grouping, not for removing the view. It
works fully offline (opening a site needs a connection, reading the list does not). An item may exist only
in `Bookmarks`, or in `Bookmarks` **and** one or more profiles, and it is the *same* stored item, not a copy.

### 6.2 Result card: collapsed and expanded  *(Phase 1)*

Extend `ProductResultUi` with the fields the card needs (source domain, description, price, availability,
match type, `bookmarked`, `wishlisted`) instead of smuggling them through strings.

**Provider-capability rule — every field is optional, in both phases.** The card and the stored item are
deliberately wider than the one provider this app currently has: title, url, source domain, thumbnail,
description, creator (author/artist/circle/publisher), price, availability, provider and match type are
all nullable. Render only what a given result actually carries, and expect different providers to fill
different subsets — an Open Library record, a future marketplace item and a future visual-search hit are
not the same shape, and one shared card has to look right for all three. A value the provider did not
send is omitted or drawn as `—`; a chip appears only when the value behind it exists. Two failure modes
are equally wrong: **fabricating** price / availability / "Visual match" that no provider returned (§14),
and **deleting** the price / availability / visual-match part of the layout because Open Library has
nothing to put there — the model and the layout stay capable, Phase 2 reproduces the image's card, and a
missing value is a formatting question. Record in the report which fields no current provider can fill.

* **Collapsed:** thumbnail or placeholder, title, source domain, state indicators. Today's card is already
  a good collapsed state — keep it.
* **Expanded:** all available metadata plus the three actions, laid out so tapping an action can never
  toggle expansion: put the tap target on a dedicated chevron/header row (as `WishlistRecordCard` does),
  not on the whole card with children stacked on top of it.
* `Open website` → the existing `actions.onOpenUrl` (system browser; keep the `https`-only guard).
* `Bookmark` → toggles membership in `Bookmarks`, reflected immediately; label/icon show the state.
* `Add to wishlist` → opens the profile picker (§6.5) for **this one item**; make the single-item scope
  obvious, e.g. `Add “<title>” to wishlist`.
* Bookmarked / wishlisted indicators are independent and can both be on at once.
* Keep using the existing `Palette.Ok` / `Warn` / `Danger` / `Accent` conventions for status.

### 6.3 Bulk `Add result profile to wishlist`

One clearly-labelled control at the end of the online results (the image puts it where the panel's primary
button sits), visually distinct from the per-card action.

1. Opens the profile picker; the user picks an existing profile or creates a new folder (`Create new
   profile` in the image).
2. Saves eligible workbook matches **when a workbook was supplied and ranked** (`lastRanked`) and eligible
   online results **when online search produced them**, in one operation.
3. Preserves source-specific metadata (`WORKBOOK_MATCH` keeps cell/cosine/percent; `ONLINE_RESULT` keeps
   url/provider/identifier).
4. Skips duplicates rather than adding them twice, and reports how many were added versus already there.
5. Updates the in-memory results so their wishlisted flags turn "saved" without re-searching.
6. Never invents placeholder rows when a source produced nothing, and never claims a count it did not
   store.

### 6.4 Bookmarking an individual result

Saves the single result into `Bookmarks` only. It must not create a profile, must not add the item to a
regular profile, and must not imply purchase intent. Repeat taps are idempotent. Unbookmarking keeps any
wishlist membership. Works when the item was bookmarked from a live search or from the bookmarks list.

### 6.5 Profile-selection workflow (shared)

One reusable picker used by: bulk add, per-item `Add to wishlist`, the bookmark "Add to wishlist" action,
and the `+` on the profile list (the overlay's in Phase 1, the Wishlist tab's in Phase 2). Requirements:

* Lists existing **regular** profiles with item counts (never offers `Bookmarks` as a target for a regular
  wishlist add unless the item is a bookmark the user explicitly sends there).
* `Create new profile` creates a real named folder. Validate the name; blank keeps a sane default in the
  `#N` spirit of the current code. Reuses the existing rename dialog pattern (`RenameUi` /
  `ConfirmDeleteDialog` in `ui/Overlays.kt`).
* Shows which profiles already contain the item and marks it instead of silently skipping.
* Cancel leaves the store untouched. Confirm performs one idempotent write.
* Must work with **no** scan, **no** workbook and **no** live search result in memory (that is Outcome 3).

### 6.6 Bookmarks view + custom bookmark form

* A dedicated view of the `Bookmarks` collection — **inside the existing wishlist overlay in Phase 1**, as
  its own list or as a segmented control alongside the profile list; it becomes the dedicated Bookmarks
  view of the Wishlist tab in Phase 2 without changing where the data comes from. Contents:
  thumbnail or placeholder, title, source domain, provider,
  added date; per-row actions `Open website`, `Add to wishlist`, and remove-bookmark (with confirmation,
  like record deletion today).
* Long lists must stay usable (lazy list — `LazyColumn` is already used in `WishlistOverlay`), with
  sorting (added / title / domain) and a text filter if cheap. Do not paginate away the reserved view.
* `+ Create bookmark` (on the bookmarks view's own header in Phase 1, and from `More` once Phase 2
  exists): **Title** (required), **URL**
  (required, validated — the app only ever opens `https`, so reject anything else with a clear message),
  source domain (derived from the URL), description/notes, author/artist/circle/publisher, product
  identifier, price, thumbnail reference. All optional beyond title and URL.
* A manual bookmark stores exactly what the user typed. **No silent fetching** of metadata, thumbnails or
  prices; if you add an explicit "Fetch preview" affordance, it must fail visibly, keep what the user typed,
  and only mark fields it actually retrieved (`matchTypeVerified = false` until it succeeds).
* Creating a bookmark does not create a profile and does not touch the current search session.

### 6.7 Profile / genre organisation

* Phase 1 shows the profile cards in the existing overlay; Phase 2 moves them to the Wishlist tab
  (name, item count, representative thumbnail) plus `Create new
  profile`, matching the image's `All / Books / Doujinshi / Others` filter row if you implement filtering —
  otherwise state plainly that grouping is by profile and genre is just a profile name the user chooses.
  **Do not hardcode genre names.**
* Opening a profile lists its items; workbook items and online/bookmark items live side by side and are
  distinguishable. Online items inside a profile are ordered so bookmarked ones come first (Outcome 1).
* Existing behaviour to preserve: rename, delete-with-confirmation, the green 5-second undo prompt, and
  empty-state texts.

### 6.8 Session cache, fingerprint, toggle, stale responses

Temporary search state must be separate from saved data:

* Cache normalised results **per capture** in memory (a small map is enough; no need to hit disk), keyed by
  a real image fingerprint — reuse what the app already computes: the capture JPEG's SHA-256 (the same kind
  of hashing `IndexCache` uses for workbooks) — **not** the file name. Reuse the cache when the same image
  is searched again while it is valid.
* Invalidate the previous capture's cache when another image is selected (`onImagePicked` / a new capture),
  and clear the online panel's results at the same moment so results on screen can never belong to a
  different cover.
* Never delete bookmarks or wishlist items when the cache is invalidated; add a `Clear session cache`
  affordance only if you can prove it cannot touch saved data.
* Stale-response protection: give every search a generation token and drop any callback whose token is not
  current. In particular, `loadCovers` must patch **by item id, not index**, or the current cross-capture
  cover mix-up stays. When the toggle goes off, hide online results immediately and cancel Kotlin-side work
  (`job?.cancel()` already exists; make sure a late `Found` cannot re-populate).
* Be honest in the docs: a Lens request already inside Python **cannot** be preempted (a documented
  Chaquopy limitation, `ONLINE_SEARCH.md` §7). Say what you cancel and what you merely ignore.

### 6.9 Result count

Count only unique, valid, display-ready results. Deduplicate before counting; do not include failed
requests, placeholders or in-flight rows. Today's `"${outcome.results.size} result(s) from ${name}"` toast
and the `N of M record(s)` line must match the number of cards actually drawn after dedup — if they can
diverge, fix it and test it.

---

## 7. The three mandatory outcomes (implement and test separately)

Each one needs an automated test at the repository/manager level, not just a manual note.

### Outcome 1 — wishlist first, then bookmark (same session)

1. Bulk-add the current result profile to a regular profile; workbook and online items are saved.
2. The online cards show the wishlisted state (the "saved" blue indicator).
3. Bookmark one online result.
4. That item is in `Bookmarks`, and inside the applicable profile's online-result ordering it is promoted
   to the top (and it moves to the top of the live search list too).
5. Every other result and every other membership is unchanged.
6. Re-opening the profile shows the item once, with both states set.

### Outcome 2 — bookmark first, then wishlist while the session is alive

1. Bookmark a result before any bulk add. It appears in `Bookmarks`; **no** profile was created.
2. While the search session and its result cache are valid, invoke `Add result profile to wishlist`.
3. The app recognises the current result profile and opens the shared picker (existing profile or new
   folder).
4. After confirmation, the eligible results are added, indicators update, the bookmark stays in
   `Bookmarks`, and already-saved records are reused instead of duplicated.
5. The individual card action still added only one item at no point in this flow.

### Outcome 3 — bookmark first, wishlist after the session is gone

1. Bookmark a result, then expire the session: pick another cover / invalidate the cache / restart the app
   (test the restart path in code: state must come from disk, not memory).
2. From the Bookmarks view, `Add to wishlist` on the saved bookmark.
3. The app lists existing regular profiles and lets the user create one.
4. The saved bookmark becomes an item of that profile and **stays** in `Bookmarks`, keeping its title, URL,
   source, thumbnail reference and metadata.
5. The operation must not need: the original image, the OCR text, the search cache, or any network call.
6. No profile named after the individual result is created.

---

## 8. Removal semantics (table to implement and test)

| User action | Must happen | Must not happen |
| :--- | :--- | :--- |
| Remove a bookmark | membership in `Bookmarks` disappears | any wishlist membership changes; the item is deleted while a profile still references it |
| Remove item from profile P | membership in P disappears | membership in `Bookmarks` or any other profile changes |
| Delete profile P | the folder and its memberships disappear | shared items vanish from other profiles |
| Clear session cache | temporary results and fingerprints drop | any saved item, membership or thumbnail is deleted |
| Delete the last membership of an item | the item and its stored thumbnail are pruned | an item with remaining memberships is pruned |

Deletion confirmations follow the existing pattern (`pendingDeleteId` + `ConfirmDeleteDialog`), and any new
destructive control must be undo-cheap or ask first.

---

## 9. Providers, normalisation, ranking

Today there is exactly one provider (`OpenLibraryBooksSource`) and one OCR service that returns no products.
Design for more without pretending any exist:

* Keep `ProductSearchDataSource` as the provider contract; add a normalising aggregator
  (suggested `core/online/OnlineResultNormalizer.kt` + `online/OnlineSearchAggregator.kt`) that merges
  provider results into one model, dedupes by the §5 identity, and tags each item with provider and
  match type. Ranking preference: (1) relevant shopping/marketplace, (2) genuine exact/near-exact visual
  matches **only if a real provider supplies them**, (3) publisher/creator/circle/community pages,
  (4) catalogue and general web results, (5) related/uncertain. Bookmarked items are promoted within the
  profile-scoped online ordering of Outcome 1.
* If you add a provider that needs credentials, put them in `local.properties` / a build config **not**
  committed, document the requirement, and make the provider optional and switchable like `online_enabled`.
* Document per provider: what it really returns, whether it accepts an uploaded image, rate limits, terms
  concerns, cost, and what the app does when it is unreachable. **Never label a result "Visual match"
  unless a verified provider actually performed image similarity.** With the current stack, OCR + text
  query is what exists — the honest label is e.g. `Text match` / `Source: Open Library`.
* Do not scrape sites that prohibit it; if a specialist site has no permitted API, leave the integration
  out and say so in the docs rather than shipping a fragile scraper.
* `tools/online_search_check.py` is the precedent: a new provider gets a desktop check script that issues
  the exact request the app builds, and its output goes in the report.

---

## 10. What `workflow_image.png` shows (transcription — use this if you cannot open the image)

Five numbered screens across the top, **"Complete Workflow — from cover to wishlist and bookmark"**:
(1) *Scan or upload the cover* — camera viewfinder with a dashed guide and Camera / Auto Scan / Gallery
buttons; (2) *Upload file or use online matches* — captured image, **Use workbook (XLSM/XLSX)** with
`doujin_catalog.xlsx` selected, and an **Online search** switch; (3) *Add result profile to wishlist* —
`Search Results` with `Workbook (12) / Online (8)` tabs, `Found 8 results`, cards with source chips
(`Shopping`, `Community`) and bookmark/heart buttons, primary button
**`Add entire result profile to wishlist`** ("including workbook matches and online results");
(4) *Bookmark the online search* — `Search Results` with a **Bookmarks** tab and
`Results are saved to the Bookmarks collection (also linked to your wishlist profile if selected)`, plus
**`Bookmark all results`**; (5) *View in wishlist and bookmarks* — the Wishlist tab showing
`My Wishlist 12 items`, `Favorites 8 items`, `Doujinshi 5 items`, **`Bookmarks 8 items`** (highlighted) and
`+ Create new profile`.

Below, **"Three Possible Outcomes"**: *Outcome 1 — Wishlist first, then bookmark (same session)* (after the
bulk add all rows show a blue wishlisted icon; bookmarking promotes the row and it appears in the Bookmarks
section); *Outcome 2 — Bookmark first (no wishlist yet)* (`Bookmark added — the result has been saved to
your Bookmarks collection`, a default profile is created only when the user later chooses to, and a note
says that while the session is active `Tap Wishlist to create a profile and add all current results to it`);
*Outcome 3 — Session expired (or different image)* (`Tap Wishlist on a bookmarked result` →
`Add to wishlist — choose an existing profile or create a new one` → the item is added to the chosen profile
and linked correctly, and the note adds that a custom bookmark can be created directly from the same dialog).

Bottom row: **Wishlist Profile Design (with genres)** (profile cards `My Wishlist / Favorites / Doujinshi /
Bookmarks / Create new profile` under an `All · Books · Doujinshi · Others` filter); **Bookmark Collection
View** (a persistent, searchable, rename/delete-capable list that shares the same UI as profiles; it states
that the Bookmarks collection is part of the wishlist system, not a separate database); **Result Card
(Expanded view)** (thumbnail, title, `Shopping` + `Visual match` chips, Description / Author / Price
`¥1,650 (tax incl.)` / Availability `In stock`, source line, and the actions **Open website**, **Bookmark**,
**Add to wishlist**).

Treat the image as the target for **structure and flow**, and this prompt as the authority on **what is
truthful**. Where the picture shows something this stack cannot back up — `Visual match`, `Price`,
`Availability`, `Shopping`, DLsite/MelonbookBook sources — implement the fields and the honest empty state
(`—`, or the chip omitted) and record it under limitations. Do not fill them with invented values, and do
not quietly drop the layout either.

---

## 11. Tests to add

Plain JUnit 4, on the desktop JVM, `core/**` logic only (the existing 110 tests are the pattern; several
test classes may live in one file). Aim for the cases below and keep names as readable sentences:

* `BookmarksCollectionTest` — reserved collection created when missing; idempotent; not duplicated on
  reload; reused after restart (reload from the same temp dir); survives corrupt-file quarantine.
* `ItemIdentityTest` — provider-id wins; URL normalisation (case, trailing slash, fragment, default port,
  tracking params stripped, destination params kept); editions are not merged; identical items dedupe.
* `WishlistStoreMigrationTest` — captured v1 payload → v2, every legacy field round-trips; `Bookmarks`
  exists after migration; a second load does not re-migrate; unknown/future version is quarantined, not
  overwritten.
* `CollectionMembershipTest` — bookmark ≠ wishlist; removal table of §8 in all five rows; prune only at
  zero memberships; membership in P never implies membership in Q.
* `BookmarkOutcomesTest` — Outcomes 1, 2 and 3 from §7 as three tests (Outcome 3 writes, then reads through
  a fresh repository instance to prove it came from disk).
* `CustomBookmarkTest` — required-field validation; non-`https` rejected; source domain derived; typed
  metadata preserved verbatim; `matchTypeVerified` false for manual fields; a manual bookmark can later be
  added to a profile without a session.
* `BulkAddTest` — workbook-only, online-only, both; duplicates skipped; counts reported; no placeholder
  items; per-item add adds exactly one.
* `SessionCacheTest` — same fingerprint reuses; new image invalidates; bookmarks/wishlist untouched by
  invalidation; stale generation token is dropped; cover patch matches by id after the list reorders.
* `OnlineAggregatorTest` — merge + dedupe + ranking preference; count equals displayable items.

Then run everything:

```bash
./gradlew testDebugUnitTest      # existing 110 + all new tests must pass
./gradlew assembleDebug          # compile the Android/Compose layer (Chaquopy needs CPython 3.12)
```

If `assembleDebug` cannot run in your environment (no CPython 3.12, no network to Maven), **say exactly
that** and list the classes you could not compile; do not report a green build you did not see. Compose UI
cannot be unit-tested here (no Robolectric/androidTest) — for UI-only behaviour, state it as unverified
instead of implying a test covered it.

---

## 12. Documentation is a deliverable, not an afterthought

Read each existing document before editing it. Update in place; never create a second guide that
contradicts the first. Every claim must match code you actually wrote and ran.

* **`README.md`** — bump the title/`versionName`/`versionCode` if you release; add bookmarks, profiles, the
  bulk add and the three outcomes to *What it does*; rewrite *Optional: online search (panel 2.5)* for the
  new result card and actions; extend *How to use*; update the **Project layout** tree and the v1→v2 file
  map; update the **Tests** count and what it covers; add the persistence/migration note and the new
  limitations; keep the *Honest status of this release* table truthful about what is tested, what is
  compiled-only and what has never run on a device.
* **`CHANGELOG.md`** — one dated entry in the existing Keep-a-Changelog style (Added / Changed / Fixed /
  Notes), covering bookmarking, the reserved collection, independent membership, bulk add, the three
  outcomes, custom bookmarks, session/cache handling, UI changes, the v1→v2 migration, tests, and remaining
  limitations. Follow the repo's versioning note (a user-visible feature set ⇒ minor version bump, e.g.
  `2.2.0`). **Do not list anything you did not implement.**
* **`ONLINE_SEARCH.md`** — revise, do not append: the two-halves table, the file map (§6), the result model,
  the aggregator/ranking, the identity rules, the cache and stale-response rules, and §7 limitations.
* **New user guide** (e.g. `USER_GUIDE.md`) — step-by-step for capture → workbook and/or online → result
  card actions → bulk add → the three outcomes → custom bookmarks → bookmarks → profiles/genres → removal
  semantics → what happens offline. Add it to the README table of contents.
* **New developer/architecture guide** (e.g. `ARCHITECTURE.md` or a section in `ONLINE_SEARCH.md` if you can
  keep it readable) — the v2 store shape, collections vs items vs memberships, the reserved collection,
  migration, identity, the session cache, provider integration, and a file-by-file map of the new code.
* Review and update anything else the change touches: `README.md` *Security and privacy* (what the manual
  bookmark form sends and stores, `allowBackup="false"`), *Configuration*, *Troubleshooting* (lost
  bookmark, `records.json.corrupt`, empty profile), and `ONLINE_COVER_RECOGNITION_DIAGNOSTIC.md` only if
  your change affects the bridge.
* Terminology must be identical across `README.md`, `CHANGELOG.md`, the user guide and the dev guide:
  *result*, *bookmark*, *wishlist item*, *wishlist profile*, *`Bookmarks` collection*, *result profile*
  (the current session's combined result set). Say explicitly in the user guide that the `Bookmarks`
  collection lives in the same store as the profiles and is not a separate database.

---

## 13. Order of work

1. Read this file, `README.md`, `ONLINE_SEARCH.md`, `CHANGELOG.md`, then the code: `UiState.kt`,
   `OnlineSearchUi.kt`, `MainViewModel.kt`, `core/wishlist/*`, `online/OnlineSearchController.kt`,
   `core/online/*`, `ui/MainScreen.kt`, `ui/OnlineSearchPanel.kt`, `ui/ProductResultCard.kt`,
   `ui/WishlistOverlay.kt`, `ui/Overlays.kt`, `ui/AppScreen.kt`, `app/build.gradle.kts`, the tests.
   Open `workflow_image.png` if it is available; note in the report if it was not.
2. Run `./gradlew testDebugUnitTest` **before** changing anything and record the baseline.
3. Write down the plan: files to add/change, the v2 store shape, the migration rule, the identity rule, and
   which of Layers A/B/C you can finish here. Fix any misunderstanding now rather than in code.
4. Layer A in `core/**` with tests: store v2 + migration, collections, items, memberships, reserved
   `Bookmarks`, `ItemIdentity`, bulk/per-item add, removal rules, session cache, aggregator.
5. Wire it through `MainViewModel` (single-threaded dispatcher) and the state classes.
6. Layer B UI: expandable card, three actions, picker, bookmarks view, custom bookmark form, profile grid.
7. Layer C navigation, if you are doing it.
8. Re-run tests and `assembleDebug`; then the desktop check scripts.
9. Documentation (§12).
10. Self-review against §4 invariants, §7 outcomes, §8 removal table, and this file's §10; then write §15.

Small, compiling, tested steps. Do not rewrite the offline matcher, and do not drop the existing
behaviours (top-5 workbook results, Auto Scan, the 5-second undo prompt, rename, delete-with-confirmation,
airplane-mode operation) on the way.

---

## 14. Honesty rules (the maintainer checks these personally)

This project documents its own limitations in detail — see the *Honest status of this release* table and
`ONLINE_COVER_RECOGNITION_DIAGNOSTIC.md`. Keep that culture:

* Never present OCR + a text query as visual product search. Never show a `Visual match` chip, a price, an
  availability status or a `Shopping` source unless a real provider returned that value in this run.
* Never invent a URL. Every link must come from a provider response or from the user's own typing.
* Never claim a metadata or thumbnail fetch succeeded without checking the response; never store a
  placeholder image and label it as the item's cover.
* Never report counts you did not compute, tests you did not run, or device behaviour you did not observe.
* If you add no new provider, say so plainly; "the design mentions marketplaces; none were added because
  …" is an acceptable and expected sentence.
* If you could not verify something because it needs a device, write that in the docs **and** in the report.

---

## 15. Final report (produce it in this exact order)

1. **Implementation summary** — what now works, in the user's words.
2. **Conformance to `workflow_image.png`** — screen by screen: implemented / partially implemented /
   deliberately different (say why). Say whether you could open the image at all.
3. **Outcome verification** — Outcomes 1, 2 and 3: the test names that cover them and their actual result.
4. **Custom bookmarks** — the form, its validation, and how a manual bookmark reaches a profile after the
   session is gone.
5. **Data model and migration** — the v2 shape as implemented, the migration rule, identity rule, prune
   rule, and what happens to a corrupt store.
6. **Files** — every file added and modified, one line each on what changed.
7. **Documentation** — each document updated or created, with the section names.
8. **Tests and build** — exact commands and their real output summaries (counts, failures). Name the
   baseline count you measured in step 2 of §13 and the new count.
9. **Known limitations and blocked verification** — including anything device-dependent, every provider
   field you could not back with data, and every place the UI intentionally shows less than the image shows.
10. **Acceptance checklist** — one line per requirement below, marked `completed` / `incomplete` / `blocked`
    with a short reason: reserved `Bookmarks` collection · persistent bookmarks across restart · bookmark ≠
    wishlist · independent removal · per-item add only · bulk result-profile add · Outcome 1 · Outcome 2 ·
    Outcome 3 after restart · custom bookmark creation · manual bookmark → profile after expiry · identity &
    dedup · URL normalisation · session cache keyed by image fingerprint · cache invalidation preserves
    saved data · stale-response protection · expandable result card with three actions and no tap conflicts ·
    bookmarked-first ordering in the owning profile · genre/profile organisation preserved · v1 data
    migration · `README.md` updated · `CHANGELOG.md` updated · user guide · developer/architecture guide ·
    `testDebugUnitTest` green · `assembleDebug` green.

Do not mark an item `completed` that you did not verify. An honest `blocked` is worth far more here than an
optimistic `completed`.

---

## 16. Defaults for the open questions

If the maintainer has not answered these by the time you are ready to code, proceed with these defaults,
state them at the top of the report, and structure the code so any of them can be changed cheaply:

1. **Navigation.** Do Layer C: bottom navigation Home / Search / Wishlist / More, with the existing panels
   moved under Search, the profile grid + `Bookmarks` card under Wishlist, and bookmark creation and
   app-level settings under More. If it turns out to threaten the offline flow, ship A + B first and explain.
2. **Store.** Extend `records.json` to v2 in the existing file, no new dependency, legacy records kept
   verbatim under `legacy` and still visible in the UI.
3. **Profiles.** User-created folders only; "genre" is a profile name the user chooses. No built-in genre
   taxonomy, no hardcoded `Books`/`Doujinshi` names in code.
4. **Providers.** Stay with Open Library plus the aggregator seams. No scraping, no keys, no marketplace
   claims. Match type shown for the current stack: `Text match` (OCR → catalogue query), never `Visual`.
5. **Manual bookmark URLs.** `https` only, matching `AppScreen.openExternal`.
6. **Thumbnails.** Store what a provider already delivered (the cover bytes the app fetched) under the same
   `thumbs/` discipline as today; never render a remote URL directly and never block the list on a fetch.
7. **Release.** Treat this as `2.2.0`: bump `versionName` / `versionCode` and follow the CHANGELOG format.
8. **Bookmarks-first ordering.** Promote within the owning profile's online items and the live result list.
   Do not reorder the workbook top-5, which is ranked by cosine.

**Begin by restating your understanding in 5–10 lines plus the list of files you intend to touch, then
proceed.** Ask only one consolidated question, at the very start, and only if something below truly blocks
you; otherwise take the §16 defaults and get on with the work.










