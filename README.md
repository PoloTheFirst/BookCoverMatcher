# BookCover Matcher

**Version 2.2.0** · Android 8.0+ (API 26) · Kotlin + Jetpack Compose · CameraX · ONNX Runtime (OpenCLIP) · Chaquopy / chrome-lens-py

Find a book cover inside an Excel workbook, save the comparison as a session, discover the same
title in online catalogues, and keep bookmarks that survive restarts. Offline matching never needs
a connection; every online feature is opt-in.

> This release is a corrective implementation. It restores the v2.1.0 offline workflow, replaces the
> broken "folder of five items" model with **folder → session → results**, fixes the online-search
> state bug that made the feature unreachable, and adds the bottom navigation the workflow called
> for. The UI follows the supplied workflow image, which lives outside the repository
> (`.../Downloads/workflow_image.png`): the Search screen has that image's `Workbook (n) / Online (n)`
> result tabs, a `Bookmarks` tab with `Bookmark all results`, and the `Add entire result profile to
> wishlist` button. Where the picture shows data this stack cannot supply (`Visual match`, price,
> availability, marketplace sources) the field stays in the layout with an honest empty state instead
> of an invented value. Step-by-step usage is in `USER_GUIDE.md`; layering and invariants are in
> `ARCHITECTURE.md`.

---

## 1. What the app does

| Capability | What actually happens | Needs network? |
|---|---|---|
| **Offline matching** | CameraX capture (or a picked photo) → OpenCLIP embedding → cosine comparison against the pictures in the chosen workbook range → top 5 matches with cell, cosine and percent | No |
| **OCR** | The captured cover is sent to Google Lens through the embedded `chrome-lens-py` package; text, language and glyph heights come back | Yes |
| **Online catalogue search** | The OCR text (or text you type) is searched in **Open Library** and **Google Books**; results are normalised, de-duplicated and ranked | Yes |
| **Visual / image-similarity search** | **Not implemented.** It needs a third-party image-similarity service and credentials this app does not ship. OCR + text search is not visual matching and is never labelled as such | — |
| **Bookmarks** | Individual online results or hand-typed links, stored in the reserved `Bookmarks` folder, independent of scans, sessions and wishlist folders | No (creation) |

The offline matcher is the original one: `Config.TOP_MATCHES = 5`, the same OpenCLIP model, the same
workbook extraction (`.xlsx`/`.xlsm`, cell-note pictures and floating pictures) and the same score
scale as v2.1.0. See §9 for the algorithm summary and §11 for the regression evidence.

---

## 2. The screen structure

Bottom navigation has four destinations:

| Tab | Contents |
|---|---|
| **Home** | Cover capture (CameraX), Auto Scan toggle, Scan / restart controls, workbook picker, cell range, image picker, "Link & Find Top 5", and a shortcut to the comparison results |
| **Search** | **Search Results** with `Workbook (n) / Online (n) / Bookmarks (n)` tabs and live counts: the offline controls plus the top-5 comparison, the online panel (OCR status, query field, catalogue results), and this search's bookmarked results with `Bookmark all results`. Offline and online results are never one mixed list |
| **Wishlist** | **Folders** and **Bookmarks** sections. A folder opens to its sessions; a session opens to its results. Bookmarks are also reachable in their own section |
| **More** | Online-search settings (toggle, auto-search, OCR/translation languages), a plain-language explanation of each capability, storage counts and "clear online session cache" |

The wishlist is no longer a full-screen overlay and the header no longer has a star button; the
Wishlist tab owns that data. Navigation is Compose state (`AppTab`) held by the ViewModel, so a tab
switch or rotation never touches persisted data.

---

## 3. Saved-data model: folder → session → results

```
Wishlist folder (user-created, persistent)
├── Session 1 (one matching/search operation)
│   ├── result 1
│   ├── result 2  ← bookmarked results are shown first
│   └── …
├── Session 2 …
└── directly saved items (individual result/bookmark added to this folder)
```

* A **folder** is created only by the user (`Create folder`) or by explicitly typing a new folder
  name in the destination picker. A scan, a search, a bookmark or an OCR pass never creates one.
* A **session** is the record of one operation: an offline comparison, an online search, or an
  explicit bulk "result profile" add. It has a stable id, a time, the results in order, and (for
  offline sessions) the captured-cover thumbnail.
* **Workbook matches are comparison output.** They live inside their session and have no bookmark,
  add-to-wishlist or remove control. A folder is not limited to five results; it can contain many
  sessions and many results.
* **Bookmarks are saved links**, not sessions and not folders. A bookmark can exist with no folder,
  can be in `Bookmarks` and several folders at once, and removing one membership never removes the
  others.
* **Bookmarked results are ordered first inside their session** (and in a folder's direct items).
  No global reordering is done, and unrelated sessions are never reordered.

`records.json` is the single source of truth (under the app's private `files/wishlist/` directory,
thumbnails in `thumbs/`). Version 3 shape:

```json
{
  "version": 3,
  "folders":   [{"id":"col_bookmarks","name":"Bookmarks","kind":"RESERVED","reserved":"BOOKMARKS","createdAt":0}],
  "sessions":  [{"id":"ses_…","folderId":"col_…","kind":"OFFLINE_MATCH","title":"Scan #1",
                 "query":null,"createdAt":0,"scanThumb":"ses_…_scan.jpg"}],
  "items":     [{"id":"itm_…","kind":"WORKBOOK_MATCH","title":"C97","cell":"C97","cosine":0.91,
                 "percent":93.0,"matchType":"WORKBOOK","fingerprint":"wb:…","thumb":"itm_….jpg"}],
  "entries":   [{"sessionId":"ses_…","itemId":"itm_…","position":0,"addedAt":0}],
  "memberships":[{"itemId":"itm_…","folderId":"col_…","addedAt":0}],
  "legacy":    null
}
```

* Writes are one temp file + rename per operation, so a bulk add cannot leave half a list.
* An unreadable or future-version file is renamed `records.json.corrupt`, never overwritten.
* Initialisation of `Bookmarks` is idempotent: it is created at most once.
* v1 records (a scan + its matches) migrate to **unfiled sessions** (no folder is invented).
* v2 folders that were really one scan per folder are repaired: their workbook items become one
  session inside that folder; remaining direct workbook items are grouped into an `Imported scan`
  session. Online/manual items keep their direct membership. The v1 payload is retained under
  `legacy` for recovery. No fabricated metadata is added during migration.

---

## 4. Using the app

### Home
1. Choose a workbook and a cell range, or pick an image.
2. Scan a cover (or use Auto Scan / the gallery).
3. Press **Link & Find Top 5**.
4. **Search** shows the comparison; **Wishlist** holds what you save.

### Search
1. The offline top-5 appears under **2 · Top 5 matches**. These rows are comparison-only.
2. **Save this scan as a session** → choose an existing folder or type a new folder name → one
   session is written. It is not five loose items, and the button never creates a folder by itself.
3. Turn on **Use online services** (or use the switch in **More**).
   * If OCR succeeds, the query is filled from the cover text; edit it if needed.
   * If OCR fails, type a title and press **Search catalogues** — OCR is not required for the
     catalogue step.
4. Online results are cards. Tap the card body to expand; **Open website**, **Bookmark** and
   **+ Wishlist** are separate actions. **Bookmark all results** writes all of them to `Bookmarks`
   without creating a folder. **Add entire result profile to wishlist** writes the offline top-5 and
   the online results as **one session** in the folder you choose.

### Wishlist
* **Folders** → **Create folder** → name it. Open a folder to see its sessions and any directly
  saved items. Open a session to see its results.
* **Bookmarks** → **Create bookmark** for a hand-typed link, or use **Bookmark** on a result.
  Filter by title/domain and sort by Added, Title or Domain. Filter/sort are local UI state only.
* Every saved item row can open its original `https://` page and can be added to a folder.
* Empty/loading/error states are explicit; no placeholder card pretends to be a result.

### More
Settings and an honest capability description. Clearing the online session cache removes temporary
results only — never folders, sessions, items, thumbnails or bookmarks.

---

## 5. Bookmarks and the three workflows

**A · Wishlist first, bookmark second**
1. Save results (offline session, online session or individual item) into a folder.
2. Bookmark an individual online result.
3. The item is in `Bookmarks` and in its folder; the session keeps its results, with bookmarked
   results first. No unrelated session/folder is reordered.

**B · Bookmark first, wishlist second**
1. Bookmark an online result. No folder and no session is created.
2. The bookmark is persisted immediately, independent of the current session.
3. Later, choose **+ Add to wishlist** on the bookmark and pick an existing folder or create one.
4. The bookmark remains in `Bookmarks` whether or not it ever joins a folder.

**C · Bookmark first, add after the session is gone**
1. Bookmark a result.
2. Change the image, end the search or restart the app.
3. Open **Wishlist → Bookmarks**, choose the bookmark and **+ Add to wishlist**.
4. Pick an existing folder or type a new name. No image, OCR output, cache, workbook or open session
   is required. The bookmark stays saved.

**Individual vs bulk:** `+ Wishlist` on one card adds exactly that item. **Add entire result profile
to wishlist** is a separate, explicit bulk action that creates one session. Neither silently does
the other. Undo on the green bar removes exactly the session/memberships that the last add created.


---

## 6. Online search in detail

### Pipeline
```
capture → AndroidImageCodec JPEG → ChromeLensPyBridge (Chaquopy) → lens_bridge.py → chrome-lens-py
        → OCR text + block heights → OcrQueryExtractor → query
        → OpenLibraryBooksSource + GoogleBooksSource (HttpURLConnection, HTTPS only)
        → parsers → ProductSearchResult → OnlineAggregator (key, dedupe, promote, limit)
        → CatalogueUi.Found → cards → Bookmark / + Wishlist / Open website
```

### Providers actually implemented
* **Open Library Books API** — free, no key, library catalogue. Detail links are built from the
  validated work/edition key; covers from `covers.openlibrary.org`.
* **Google Books Volumes API** — free for search, no key, catalogue. `https` detail page and cover
  (an `http` thumbnail is upgraded to the same host over `https`). No price or stock is invented.
* No marketplace, shopping or visual-similarity provider is implemented or claimed.

### Toggle and request lifecycle
* The switch is persisted in `bookcover_online`; the controller's state is mirrored into `UiState`
  — this was the actual defect that made online search unreachable (see §10).
* Turning it off hides results at once, cancels the Kotlin job where possible, bumps the session
  generation so late callbacks are dropped, clears the temporary cache and starts no new request.
  A Lens request already inside Python cannot be preempted; its answer is ignored.
* Selecting a different image invalidates the previous capture's results; a cache entry is keyed by
  the SHA-256 of the JPEG, never by file name. Clearing the cache cannot delete saved data.
* A total failure shows a named error. If one catalogue fails and another answers, the results are
  shown with a visible note naming the failed catalogue.

### Honest limitations
* OCR is text recognition. The library does not return products, prices or visual matches.
* Google Books can answer **HTTP 429** without an API key on shared networks. The app classifies it
  as `RATE_LIMITED`; it does not retry behind your back or fabricate results. Open Library remains
  usable. An API key/billing configuration is not shipped.
* Catalogue results can contain editions/works with similar titles; they are not merged by title.
* Prices and availability are absent because these catalogues do not provide them; the card shows
  `—`, never a guess.

---

## 7. Build and run

Requirements: Android Studio with SDK 35, JDK 17, a CPython **3.12** for Chaquopy, and network access
for the first Python dependency install.

`local.properties`:
```properties
sdk.dir=C:\\path\\to\\Android\\Sdk
chaquopy.buildPython=C:\\path\\to\\Python\\3.12\\python.exe
```

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest                 # JVM suite, live checks skipped
.\gradlew.bat testDebugUnitTest -DonlineCheck=1 # adds the two live catalogue checks
.\gradlew.bat connectedDebugAndroidTest         # 3 on-device OCR/catalogue/controller tests
```

The build uses `androidx.compose:compose-bom:2024.10.01`, CameraX 1.3.4, ONNX Runtime 1.20.0,
Chaquopy 17.1.0 and `chrome-lens-py==3.8.2` (protobuf pinned to 6.33.6). Shrinking is intentionally
off; the release build uses the debug signing config so `assembleRelease` is installable, not
publishable.

### Useful troubleshooting
* **"chrome-lens-py could not be loaded"** — the embedded Python runtime or its wheels failed. Check
  `adb logcat -s OnlineSearch:V python.stderr:V`; the panel shows the categorized reason.
* **"no connection"** — catalogue requests never leave the device without `INTERNET`; offline
  matching still works.
* **Google Books 429** — expected on some networks without a key; use Open Library results.
* **Workbook empty** — verify the range and that pictures are anchored or stored as cell notes in
  the first sheet.

---

## 8. Project layout (main files)

```
app/src/main/java/com/bookcovermatcher/
├── MainActivity.kt, MainViewModel.kt, UiState.kt, OnlineSearchUi.kt
├── core/                         plain Kotlin/JVM, unit-tested
│   ├── excel/                    workbook and picture extraction
│   ├── search/                   OpenCLIP index, ranking, cache
│   ├── vision/                   image ops, preprocessing, interfaces
│   ├── autoscan/                 auto-scan engine
│   ├── wishlist/                 LibraryModels (folders/sessions/items), repository,
│   │                             MiniJson, identity and bookmark rules
│   └── online/                   OcrQueryExtractor, GoogleBooksResponseParser,
│                                 OpenLibraryResponseParser, OnlineAggregator, SessionCache
├── data/                         Prefs, OnlineSearchPrefs, ModelStore, WorkbookFiles
├── vision/                       AndroidImageCodec, OrtClipEmbedder
├── camera/                       CameraController (CameraX)
├── online/                       OnlineSearchController, ChromeLensPyBridge, PythonRuntime,
│                                 OcrTextSource/Parser, OpenLibraryBooksSource,
│                                 GoogleBooksSource, HttpJsonClient, CoverImageLoader
└── ui/                           AppScreen (bottom nav), MainScreen (Home/Search),
                                  WishlistScreen, MoreScreen, OnlineSearchPanel,
                                  ProductResultCard, BookmarkDialogs, Overlays, CameraCard,
                                  components/, theme/
app/src/main/python/lens_bridge.py   the only Python; wraps chrome-lens-py
app/src/test/                        165 JVM tests (2 opt-in live checks)
app/src/androidTest/                 3 instrumented tests (OCR, catalogues, controller)
```


---

## 9. Offline matching (unchanged from v2.1.0)

1. Workbook images are extracted from the first sheet, inside the selected range, from cell-note
   pictures and (when `EXTRACT_FLOATING_IMAGES` is true) anchored pictures.
2. Each image is decoded, centre-cropped/preprocessed and embedded with the bundled OpenCLIP image
   encoder.
3. The captured cover is embedded the same way.
4. Cosine similarity is computed against every workbook image; the score is scaled against the
   catalogue baseline and clamped to 0–100 % for display.
5. The top `Config.TOP_MATCHES` (5) are returned in descending order, with cell, source, cosine and
   percent.

No step of this pipeline is online, and the online feature is never consulted by it.

---

## 10. What was broken and what changed in 2.2.0

1. **Online search was unreachable.** `OnlineSearchController` kept its state in its own
   `StateFlow`, but `MainViewModel` never mirrored it into `UiState.online`. The panel therefore
   always rendered the default off state and returned early, so even after the preference was
   switched on the OCR/catalogue UI never appeared. Fixed with a collected mirror in `init`.
2. **Offline saves had the wrong shape.** The v2 "compatibility" path turned each scan into a
   folder containing five independently removable workbook items, with no session entity. Folders
   were created by scans, could not accumulate sessions, and offline rows gained item semantics.
   Replaced by the v3 folder → session → results model, with a migration for v1/v2 data.
3. **Bookmarks were not tied to a session.** There was no session, so "bookmarks first in their
   session" was impossible. The v3 session view now orders bookmarked results first; the store and
   UI keep bookmark and folder membership independent.
4. **Only one catalogue.** Added Google Books as a second real source, with per-source failure
   reporting. Neither is a shop, so no price/stock is invented.
5. **OCR geometry could be misaligned.** A Lens block can contain an internal newline; the bridge
   joined blocks with newlines, so line count and `block_heights` diverged. The bridge now emits one
   line per block, and `OcrQueryExtractor` pairs each height with its source line before filtering.
6. **The instrumentation context could be null.** `PythonRuntime` now falls back to the supplied
   context if `applicationContext` is null (seen in the Android test runner), instead of failing
   every OCR call with `RUNTIME_MISSING`.
7. **UI rebuilt** around Home/Search/Wishlist/More, with real empty/loading/error states and
   separate offline/online result areas.

---

## 11. Verification performed

| Check | Command | Result |
|---|---|---|
| JVM unit tests | `.\gradlew.bat testDebugUnitTest` | **165 tests**, 0 failures (2 live checks skipped by design) |
| JVM + live catalogues | `.\gradlew.bat testDebugUnitTest -DonlineCheck=1` | **165 tests**, 0 failures; Open Library live query returned real records; Google Books was either found or correctly reported `RATE_LIMITED` |
| Debug build | `.\gradlew.bat assembleDebug` | **BUILD SUCCESSFUL** |
| Python bridge contract | `python tools/lens_bridge_check.py` (with chrome-lens-py installed) | **ALL BRIDGE CHECKS PASSED** |
| Real OCR on desktop | chrome-lens-py 3.8.2 + the Hobbit cover | `THE HOBBIT` / `JRR TOLKIEN` read; heights returned |
| Device tests | `.\gradlew.bat connectedDebugAndroidTest` on `Medium_Phone_API_37.0` (x86_64) | **3/3 pass**: real OCR reads the bundled Hobbit cover; both catalogue sources answer or are correctly classified; the controller publishes merged results |
| UI smoke test | emulator, manual taps | Bottom navigation, folder creation/rename, bookmark form, bookmark → folder picker, restart persistence (`records.json` v3 verified with `run-as`) |

### Not verified
* Camera capture and Auto Scan (the emulator had no camera; permission was denied during the smoke
  test). Capture code is unchanged from v2.1.0, but this build did not exercise it.
* A full workbook match through the UI (no workbook was loaded in the emulator).
* Offline session save via the UI (the repository and its persistence are covered by tests).
* Online result card expansion/bookmark actions via the UI (the catalogue/controller path is covered
  by device tests; the card itself was not driven end-to-end).
* Google Books with an API key (none is configured).
* The supplied workflow image (not present). If it specifies different labels/arrangement, this
  implementation may need visual adjustment.

---

## 12. Privacy and provenance

* Offline matching sends nothing anywhere.
* With online search on, the captured JPEG is sent to Google Lens via `chrome-lens-py`; the OCR text
  is sent to `openlibrary.org` and `www.googleapis.com/books`. No account, analytics or ads.
* Bookmarks store only the URL/title/optional fields you provide; the app never fetches the page to
  enrich them.
* The app refuses non-HTTPS links and never renders third-party pages in a WebView.
* Open Library data is used under its published Open Data terms; `chrome-lens-py` is MIT licensed
  and used unmodified, pinned in `app/build.gradle.kts`.
* Version 2.1.0 is the behavioural reference for offline matching; the historical diagnostic is in
  `ONLINE_COVER_RECOGNITION_DIAGNOSTIC.md`.
