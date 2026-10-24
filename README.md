# BookCover Matcher

**Version 2.2.3** · Android 8.0+ (API 26) · Kotlin + Jetpack Compose · CameraX · ONNX Runtime (OpenCLIP) · Chaquopy / chrome-lens-py

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
> of an invented value. **2.2.1** corrected the provider layer. **2.2.2** adds the platform picker:
> tap a card to include or exclude that platform, `Open ↗` on every card opens its own search page,
> and the online result maximum is now chosen (5–50) instead of fixed at five. Open Library, Google
> Books and DLsite are searched in the app; Melonbooks, Suruga-ya, Doujin no Mori, E-Hentai,
> Doujinshi.info, MyAnimeList and nhentai are browser-only shortcuts with a card and a link, never
> part of the in-app selection - see section 6. **2.2.3** fixes multi-word DLsite queries (its keyword
> path needs `+`, not `%20`), lets result profiles be renamed like folders, and lets a result inside a
> profile be bookmarked without leaving the profile.
> Step-by-step usage is in `USER_GUIDE.md`; layering and invariants are in `ARCHITECTURE.md`.
---

## 1. What the app does

| Capability | What actually happens | Needs network? |
|---|---|---|
| **Offline matching** | CameraX capture (or a picked photo) → OpenCLIP embedding → cosine comparison against the pictures in the chosen workbook range → top 5 matches with cell, cosine and percent | No |
| **OCR** | The captured cover is sent to Google Lens through the embedded `chrome-lens-py` package; text, language and glyph heights come back | Yes |
| **Online platform search** | The OCR text (or text you type) is searched in the platforms you select. **Open Library**, **Google Books** and **DLsite** are queried in the app. **Melonbooks**, **Suruga-ya**, **Doujin no Mori**, **E-Hentai**, **Doujinshi.info**, **MyAnimeList** and **nhentai** are browser-only cards with their own link and can never be selected: Melonbooks is a product decision, Suruga-ya and Doujin no Mori disallow their search route in `robots.txt`, E-Hentai/nhentai are adult sites that block automated access, Doujinshi.info answers HTTP 403 and MyAnimeList has no unauthenticated API. Every card has its own `Open` link, and the result maximum is configurable (5-50) | Yes |
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
| **Search** | **Search Results** with `Workbook (n) / Online (n) / Bookmarks (n)` tabs and live counts: the offline controls plus the top-5 comparison, the online panel (OCR status, query field, **platform picker with per-platform `Open ↗` links**, **result maximum 5–50**, provider results), and this search's bookmarked results with `Bookmark all results`. Offline and online results are never one mixed list |
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
        → OpenLibraryBooksSource + GoogleBooksSource + DlsiteSource (concurrent, own deadline each)
        → per-provider parsers → ProductSearchResult → OnlineAggregator (key, dedupe, promote, limit)
        → CatalogueUi.Found + per-provider status rows → cards → Bookmark / + Wishlist / Open website
```

### Providers actually implemented

| Provider | Access method | Queried by the app | Verified |
|---|---|---|---|
| **Open Library** | Public Books API (JSON), no key | Yes | Yes - real records returned |
| **Google Books** | Public Volumes API (JSON), no key | Yes | Yes, or correctly reported `RATE_LIMITED` |
| **DLsite** | Public books search page (HTML), read by `DlsiteResponseParser` | Yes | Yes - real works, prices and covers |
| **Melonbooks** | Public search page (HTML); parser kept but **not registered** | **No** - browser-only by product decision | Card + `Open` link only; adapter covered by offline fixture tests |
| **Suruga-ya** | Public search page only | **No** - its readable `robots.txt` disallows `/search/` | Not queried; `Open` opens it |
| **Doujin no Mori** | Public search page (HTML, robots disallows `/comics/free-search`) | **No** - its own crawl rules exclude the search route | `Open` opens `?keyword=` search; page was verified 200 but is never parsed |
| **E-Hentai** | Public search page; adult content behind the site's age gate | **No** - `robots.txt` disallows `/s/` and `/codegen.php` | `Open` opens the site's own search page |
| **Doujinshi.info** | Public search page (`/works?query=`) | **No** - HTTP 403 to automated clients | `Open` carries the query; the user handles the site's adult toggle |
| **MyAnimeList** | Public manga search (`/manga.php?q=&cat=manga`); `/topmanga` is 404 | **No** - no unauthenticated API; terms forbid scraping | `Open` opens the manga search |
| **nhentai** | Public search page | **No** - HTTP 403 (Cloudflare), adult catalogue | `Open` opens the site's own search page |

* **Open Library** — detail links are built from the validated work/edition key; covers come from
  `covers.openlibrary.org`.
* **Google Books** — `https` detail page and cover (an `http` thumbnail is upgraded to the same host
  over `https`). One request returns at most 40 volumes.
* **Melonbooks** - **not queried in this build** (browser-only by product decision). Its adapter and
  parser remain in the tree for reference, unregistered, and are covered by offline fixture tests.
  `robots.txt` answers **HTTP 404** (no rules published); the card keeps its `Open` link.
* **DLsite** — one request per search to `/books/fsr/=/keyword/<query>`. The query is form-encoded
  (`Blue+Archive`), because that route answers **HTTP 403** to `%20` — verified 2026-10-23. The adapter
  reads the work id (`RJ…`), the title, the circle, the **current** price and the 240×240 cover. The
  listing carries no stock status, description or rating, so those stay `—`. `robots.txt` asks for a
  10-second crawl delay and disallows the paged result URLs, so the app never paginates and never
  retries.
* **Suruga-ya** — `robots.txt` is readable (HTTP 200) and contains `User-agent: *` / `Disallow:
  /search/` with `Crawl-delay: 30`. The search page would answer, but its own rules exclude that
  endpoint, so `ProviderCatalog` marks it `BROWSER_ONLY`: the app sends it no request and the card's
  `Open ↗` control opens the search in the user's browser.
* No visual-similarity provider is implemented or claimed.

> The earlier 403s recorded for Melonbooks/Suruga-ya came from a different HTTP client, not from the
> sites: with the app's own user agent the pages answer 200. What decides automated access is each
> site's published rules, as above.

### Choosing platforms

The panel shows all ten platform cards, in `ProviderCatalog` order: three selectable in-app providers
and seven browser-only shortcuts. This is the only place a
platform is selected; no other screen keeps its own list.

* **Tap the card body** to select or deselect it. Nothing is searched until **Search selected
  platforms** is pressed (or auto-search runs after an OCR pass).
* **Selected** → blue accent border, a soft accent glow, a check mark and a `Selected`/state chip.
  The glow is a 10 dp shadow plus a 10 % accent fill: visible, not neon.
* **Unselected** → subdued surface, muted text, thin neutral border. Still fully usable: tapping it
  selects it, and its link still opens.
* **`Open ↗` on every card** opens that platform's search page for the query currently in the box. It
  is a separate click target, so it never toggles the selection and works whether the platform is
  selected or not.
* **Selection does not change capability.** Open Library, Google Books and DLsite are queried in the
  app. The other seven cards are browser-only: they cannot be selected, and they are never counted as
  in-app providers.
* The selection is persisted (`bookcover_online` → `online_providers`) and restored after a restart.
  Unknown ids are dropped on read, and so is every browser-only id, so a provider that became
  browser-only in a later build cannot linger in the selection.

### Choosing how many online results

`ONLINE RESULTS` offers **5 / 10 / 20 / 30 / 50** (default **5**, persisted as `online_limit`; an
unknown stored value falls back to 5). It is a **maximum**, not a promise:

| Provider | Its own cap | Why |
|---|---|---|
| Open Library | 50 | the app's own ceiling; the API allows more |
| Google Books | 40 | Google's `maxResults` limit |
| Melonbooks | 50 | 100 per page, first page only |
| DLsite | 30 | one page of 30 works; paged URLs disallowed |

The controller asks each selected provider for `min(chosen, provider cap)`, merges and **deduplicates**
what came back, then applies the chosen maximum to the merged list. The header reports the number of
cards actually drawn plus the maximum, and says when fewer arrived. This control has nothing to do
with the offline comparison, which keeps its fixed five results (`Config.TOP_MATCHES`).

### Toggle and request lifecycle
* The switch is persisted in `bookcover_online`; the controller's state is mirrored into `UiState`
  — this was the actual defect that made online search unreachable (see §10).
* Turning it off hides results at once, cancels the Kotlin job where possible, bumps the session
  generation so late callbacks are dropped, clears the temporary cache and starts no new request.
  A Lens request already inside Python cannot be preempted; its answer is ignored.
* Selecting a different image invalidates the previous capture's results; a cache entry is keyed by
  the SHA-256 of the JPEG, never by file name. Clearing the cache cannot delete saved data.
* A cache entry also carries the requested maximum and the selected platforms, so a five-result answer
  can never satisfy a twenty-result request or a different selection. Changing either cancels an
  in-flight search and drops the entry.
* A total failure shows a named error. If one provider fails and another answers, the results are
  shown with a visible note naming the failed provider.

### Honest limitations
* OCR is text recognition. The library does not return products, prices or visual matches.
* Google Books can answer **HTTP 429** without an API key on shared networks. The app classifies it
  as `RATE_LIMITED`; it does not retry behind your back or fabricate results. The other platforms
  remain usable. An API key/billing configuration is not shipped.
* Results can contain editions/works with similar titles; they are not merged by title, and the same
  title from two platforms stays two cards.
* Prices: a catalogue record (Open Library, Google Books) has none, so the field shows `—`. A shop
  listing (Melonbooks, DLsite) shows the shop's own **current** price; a struck-through old price is
  not used. Availability/stock is never shown, because no provider in this build sends it.
* DLsite results come from its first page only: one request per search, no pagination, no retry.
  Google Books stops at 40 records per request.
* Seven providers are **not** searched by the app; their cards are labelled *browser only* and never
  count as answered providers: Melonbooks (product decision), Suruga-ya (`robots.txt` disallows
  `/search/`), Doujin no Mori (`robots.txt` disallows `/comics/free-search`), E-Hentai and nhentai
  (adult sites that block automated access), Doujinshi.info (HTTP 403) and MyAnimeList (no
  unauthenticated API).

---

## 7. Build and run

Requirements: Android Studio with SDK 35, JDK 17, a CPython **3.12** for Chaquopy, and network access
for the first Python dependency install. The build runs on **Android Gradle Plugin 9.4.1**, **Gradle
9.8.0** via the wrapper, and **Kotlin 2.2.10** (`gradle/libs.versions.toml`, `gradle-wrapper.properties`).

`local.properties`:
```properties
sdk.dir=C:\\path\\to\\Android\\Sdk
chaquopy.buildPython=C:\\path\\to\\Python\\3.12\\python.exe
```

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest                 # JVM suite, live checks skipped
.\gradlew.bat testDebugUnitTest -DonlineCheck=1 # adds the four live provider checks
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
* **Google Books 429** — expected on some networks without a key; the other platforms still answer.
* **Suruga-ya shows "Browser only"** — expected: its `robots.txt` disallows `/search/`, so the app
  never queries it. Tap **Open ↗** on its card to search in your browser.
* **A platform shows "Not selected"** — tap its card to include it in the next search; `Open ↗`
  works regardless.
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
│                                 OpenLibraryResponseParser, DlsiteResponseParser,
│                                 MelonbooksResponseParser, HtmlText, ProviderCatalog,
│                                 ProviderLinks, SearchRouting, SearchLimits,
│                                 OnlineAggregator, SessionCache
├── data/                         Prefs, OnlineSearchPrefs, ModelStore, WorkbookFiles
├── vision/                       AndroidImageCodec, OrtClipEmbedder
├── camera/                       CameraController (CameraX)
├── online/                       OnlineSearchController, ChromeLensPyBridge, PythonRuntime,
│                                 OcrTextSource/Parser, OpenLibraryBooksSource,
│                                 GoogleBooksSource, MelonbooksSource, DlsiteSource,
│                                 HttpJsonClient, CoverImageLoader
└── ui/                           AppScreen (bottom nav), MainScreen (Home/Search),
                                  WishlistScreen, MoreScreen, OnlineSearchPanel, PlatformPicker,
                                  ProductResultCard, BookmarkDialogs, Overlays, CameraCard,
                                  components/, theme/
app/src/main/python/lens_bridge.py   the only Python; wraps chrome-lens-py
app/src/test/                        285 JVM tests (4 opt-in live provider checks)
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

## 10. What was broken and what changed (2.2.0 → 2.2.3)

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
8. **Only two providers, and no way to choose them.** 2.2.1 added **Melonbooks** and **DLsite** as
   real providers; 2.2.2 adds the platform picker (select/deselect per platform, `Open ↗` on every
   card), a configurable online result maximum, and a selection-aware search route. **Suruga-ya**
   stays browser-only because its `robots.txt` disallows `/search/`. **2.2.3** fixes DLsite queries
   with spaces (`+`, not `%20`), adds the folder-style pencil to result profiles, and lets a profile
   result be bookmarked into `Bookmarks` while staying in its profile.

---

## 11. Verification performed

| Check | Command | Result |
|---|---|---|
| JVM unit tests | `.\gradlew.bat testDebugUnitTest` | **285 tests**, 0 failures (4 live checks skipped by design) |
| JVM + live providers | `.\gradlew.bat testDebugUnitTest -DonlineCheck=1` | **284 tests**, 0 failures; all three live checks ran: Open Library and **DLsite** returned real records, Google Books was found or correctly `RATE_LIMITED`. Melonbooks left the live suite with its registration, so the run makes no request to it |
| DLsite live query | `DlsiteSource().search("東方", 3)` inside the live suite | **real works returned**: `RJ…` ids, Japanese titles, circle names, prices (`924円`-style), `img.dlsite.jp` covers, `dlsite.com` product URLs |
| Provider access probe | `GET robots.txt` and the search URL with the app's own UA (2026-10-23) | Melonbooks: robots **404** (no rules), search **200**; Suruga-ya: robots **200** with `Disallow: /search/`; DLsite: robots **200**, paged URLs disallowed |
| Compose UI tests | none | **Not run**: the project has no `compose.ui:ui-test-junit4` dependency and no emulator/device was attached; platform selection, link independence, the count cap and the batch repository operations are covered by `SearchRoutingTest`, `ProviderLinksTest`, `OnlineResultLimitTest` and `BatchOperationsTest` |
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
* **Suruga-ya as an in-app search** — deliberately not implemented: its `robots.txt` disallows
  `/search/`. Only its generated browser link is verified.
* **Melonbooks / DLsite on a physical phone** — the live checks ran on the desktop JVM through the
  same adapters, not on a device.
* **Compose UI interactions** (card toggle, glow, link independence) — no Compose UI-test dependency
  and no emulator/device was available; the underlying state transitions are unit-tested instead.
* The supplied workflow image guided the Search screen (result tabs, bookmark tab, bulk actions).
  Pixel-level parity is not claimed; the information structure is what was matched.

---

## 12. Privacy and provenance

* Offline matching sends nothing anywhere.
* With online search on, the captured JPEG is sent to Google Lens via `chrome-lens-py`; the OCR text
  is sent to `openlibrary.org`, `www.googleapis.com/books`, `www.melonbooks.co.jp` and
  `www.dlsite.com` — and only to the platforms you selected. No account, analytics or ads.
  Suruga-ya is never contacted by the app: its link opens in your browser.
* Bookmarks store only the URL/title/optional fields you provide; the app never fetches the page to
  enrich them.
* The app refuses non-HTTPS links and never renders third-party pages in a WebView.
* Open Library data is used under its published Open Data terms; `chrome-lens-py` is MIT licensed
  and used unmodified, pinned in `app/build.gradle.kts`.
* Version 2.1.0 is the behavioural reference for offline matching; the historical diagnostic is in
  `ONLINE_COVER_RECOGNITION_DIAGNOSTIC.md`.
