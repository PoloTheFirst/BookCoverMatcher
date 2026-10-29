# Architecture and developer notes (v2.4.0-dev.1)

## Layers

```
UI (Compose)                MainViewModel (AndroidViewModel)          Services
────────────────            ─────────────────────────────────         ───────────────────────────
AppScreen / bottom nav  ──▶ UiState (StateFlow)                   ┌─ CameraController (CameraX)
HomeScreen / SearchScreen    camera, workbook, results,            ├─ OrtClipEmbedder (ONNX)
WishlistScreen               folders/sessions/bookmarks,            ├─ OnlineSearchController
MoreScreen                   dialogs, prompt/toast                  │   ├─ OCR bridge (Chaquopy)
WorkbookCommentsScreen       │                                      │   └─ catalogues (JVM HTTP)
ProductResultCard            │                                      ├─ WorkbookCommentsController
OnlineSearchPanel            ├─ offline: CatalogBuilder +           │   (legacy note images, cache)
dialogs                      │            CatalogIndex              └─ WishlistRepository (file store)
                             ├─ library: WishlistRepository
                             └─ online: OnlineSearchController.ui (mirrored)
```

* `core/` is plain Kotlin/JVM: no `android.*` imports. It is covered by the desktop JVM suite.
* `core/excel/comments/` (v2.3.0) reads the legacy note images of a package — relationship discovery,
  comments XML, VML note shapes, media validation, diagnostics (`CommentImageScanner`) — and
  `data/CommentImageCache` stores the result per workbook hash. `WorkbookCommentsController` runs it off
  the main thread; `ui/WorkbookCommentsScreen` is the read-only surface.
* `core/excel/comments/CatalogueTable` + `SheetCells` discover a catalogue table from its **own header
  row** (no sheet name or column index is hardcoded) and `ImageIndex`/`WorkbookAnalyzer` join it with the
  verified comment→media associations; `ui/WorkbookBrowserPanels` renders the search/filter/sort/export
  UI and `core/export/ImageManifest` writes the CSV.
* `data/WorkbookLibrary` owns the **Saved workbooks** copies (`files/workbooks/<id>.<ext>`) and their
  `library.json` (display name, pin, recency, last parsed counts). Identity is the id/hash, never the
  display name; `ui/WorkbookLibraryScreen` is its surface.
* `core/excel/comments/WorkbookComparison` (v2.3.0) diffs two `WorkbookMetrics` by **worksheet name**
  (never by position): presence, workbook order, visibility, declared used range (`SheetCells.dimension`
  reads `<dimension>` without loading cells), comment/link/unresolved/media counts, and catalogue
  record/header differences. Counts that cannot be established stay `null` and render as *unknown*.
  `MainViewModel.runComparison` builds both sides on `Dispatchers.IO` reusing `CommentImageCache`, and
  `ui/WorkbookComparisonScreen` shows the result. It is metadata-level only — no cell diff, no macros.
* `MainViewModel` owns the single `UiState`; tab selection and dialog state live there. It never
  stores authoritative saved data in memory.
* `SearchScreen` (in `MainScreen.kt`) is the workflow image's **Search Results** screen: three
  `SearchResultsTab` values (`WORKBOOK`, `ONLINE`, `BOOKMARKS`) with live counts, switched through
  `MainViewModel.selectSearchTab`. The selected tab is navigation state only, and each tab renders
  the same state holders and repositories as before, so no persistence path exists in the UI layer.
  The tab track itself is `ui/components/SegmentedTabRow` + `SegmentedTab`, shared with the Wishlist
  sections.
* The two result cards (**v2.3.1**) are one implementation. `ui/ResultCardParts`
  (`ExpandChevronButton`, `DescriptionBlock`, `ResultDetails`, `DetailLine`, `ResultActionChip`) is
  shared by the Search card (`ProductResultCard` over `ProductResultUi`) and the Wishlist bookmark card
  (`SavedItemRow` over `SavedItemUi`), and `core/wishlist/BookmarkPresentation` derives the author line,
  the price/identifier caption, the source chip and the match-type wording from the stored fields — the
  circle is only a display fallback and never becomes an artist. `SavedItemRow` adds only what a saved
  record has: selection, the tags row, edit/remove and the added-at caption. A new field therefore has
  to be added to both mappings; the layout cannot drift again.
* The source-location feature (**v2.4.0-dev.1**) is pure core plus a thin overlay:
  `core/excel/comments/SourceLocation` turns the models the parser already produced (an `ImageRow`'s
  verified `MediaAssociation`s, a `LegacyCommentEntry`'s own `ref`, a detected `CatalogueRecord`) into a
  `Candidate(sheetName, sheetPath, cell, row, column)` — or into `Unreferenced`, `Unresolved` or `Unknown`
  when nothing is proven. `SheetWindow`/`SheetWindows` turn the cells of one sheet into a small window
  around a target row (nearest columns, the catalogue header carried separately). The controller reads a
  sheet once into `sheetCells` (cleared on open/rescan/close) and guards the result with the workbook
  hash, so a late read for another workbook is dropped. `WorkbookCommentsUi.source` holds the overlay and
  its transitions are pure functions (`openSource`, `sourceLoading`, `sourceWindow`, `sourceFailed`,
  `closeSource`), which is what the unit tests drive.
* `OnlineSearchController` owns its own `StateFlow`; `MainViewModel.init` collects it into
  `UiState.online`. That mirror is required for the panel and the persisted switch to agree.
* `WishlistRepository` is single-threaded via a dedicated executor and writes `records.json` with a
  temp file + rename.

## Online providers

```
ProviderCatalog (core/online)      eleven ProviderSpec rows: id, host, access, note, search + landing URL
        |
        +-- AUTOMATED + SELECTABLE -- selected by the user (SearchRouting) -- OnlineSearchController.catalogues
        |       +-- OpenLibraryBooksSource -- OpenLibraryResponseParser      (JSON)
        |       +-- GoogleBooksSource      -- GoogleBooksResponseParser      (JSON)
        |       +-- DlsiteSource           -- DlsiteResponseParser + HtmlText (HTML)
        +-- BROWSER_ONLY -- never selected, never queried; each card opens ProviderLinks.searchUrl:
                Melonbooks        (product decision; page answers, adapter unregistered)
                Suruga-ya         (robots: Disallow /search/)
                Doujin no Mori    (robots: Disallow /comics/free-search)
                E-Hentai          (adult; robots: /s/, /codegen.php)
                Doujinshi.info    (HTTP 403 to automated clients)
                MyAnimeList       (no unauthenticated API; /topmanga is 404)
                nhentai           (HTTP 403, adult)
                Toranoana         (undocumented storefront page; no documented API; 18+)
        MelonbooksSource -- MelonbooksResponseParser stays in the tree, unregistered, fixture-tested only
```

* `ProductSearchDataSource` is the seam: synchronous, returning `Found` / `Empty` /
  `Failed(OnlineFailure)`, plus a `maxResults` capability the controller caps against. Network code
  lives in `online/`; parsing lives in `core/online` and is pure Kotlin, so every provider's markup
  handling is unit-tested without a socket.
* **Selection** is user intent, kept in `OnlineSearchPrefs` (`online_providers`) and routed by
  `SearchRouting` (`automated`, `shortcuts`, `hasAutomated`, `key`). Only a `selectable` provider (an
  automated one) can enter the selection: `sanitize` drops browser-only ids, so a stored selection
  from an older build cannot resurrect an in-app search. An empty selection performs no request
  (`CatalogueUi.BrowserOnly`) and says which shortcuts keep their link.
* **Limits** live in `SearchLimits` (options 5/10/20/30/50, default 5, validated on read). The
  controller asks each adapter for `min(chosen, adapter.maxResults)` and applies the chosen maximum to
  the merged list *after* `OnlineAggregator` deduplicates. `Config.TOP_MATCHES = 5` is the offline
  comparison and is not reachable from this control.
* Adapters run **concurrently** (`async` + `awaitAll`) with a 20-second `withTimeout` each, so a slow
  shop cannot delay a fast catalogue and a hung provider cannot cancel the others.
* `OnlineFailure.BLOCKED` is the honest answer to HTTP 401/403/451. The app never retries with a
  different identity, never solves a challenge and never paginates a disallowed URL.
* `ProviderStatusUi` (in `OnlineSearchUi.kt`) is built from the measured outcomes and carries
  `selected` (only ever true for an automated provider) and a nullable `searchUrl`, so the card shows
  results / no-match / failure / blocked / searching / browser-only / not-selected and cannot claim
  that a provider answered when it did not.
* Identity: `OnlineAggregator.itemKey` → `ItemIdentity` with `providerId:id` keys (`ol:…`, `gb:…`,
  `mb:…`, `dl:rj…`). Different providers never merge into one card; `rankOf` prefers
  `RANK_MARKETPLACE` for a shop listing and `RANK_CATALOGUE` for a bibliographic record.
* The **session cache entry** carries the query, the requested maximum and the selection key, so a
  cached answer is reused only for the same cover *and* the same request.
* `ProviderLinks` builds and validates every browser link (https, own host, no credentials, the
  platform's own query form — `%20`, or `+` for DLsite — landing page for a blank query) outside
  Compose, so link behaviour is unit-tested.
* `ui/PlatformPicker.kt` draws the cards: a checkbox-role toggle row plus a **sibling** link button,
  so the two click targets can never overlap. A browser-only card has no toggle node at all, a
  `Browser only` chip, a warm border and either a link button or `No link`.
* Fixtures under `app/src/test/resources/fixtures` are captured provider pages (DLsite and
  Melonbooks search/empty results), so the parser tests assert against real markup, not mocks.

## Saved-data invariants

* One reserved `Bookmarks` folder; idempotent creation.
* `SearchSession.folderId` identifies the owning folder; `SessionEntry` identifies a result inside a
  session; `Membership` identifies a direct item-in-folder relation.
* Workbook matches (`ItemKind.WORKBOOK_MATCH`) exist only as session entries. The repository rejects
  them from `addDrafts` and from `bookmark`.
* Removing a bookmark removes only the `Bookmarks` membership. Removing a folder membership removes
  only that membership. `prune()` deletes an item (and its thumbnail) only when no membership or
  session entry references it.
* Session result ordering is computed: bookmarked items first, then entry position. No write
  reorders other sessions.
* A result profile is renamed **in place** (`renameSession`): same id, same folder, same
  `SessionEntry` list. That is the profile counterpart of `renameFolder`, and the UI reuses the folder
  pencil, dialog and `commitRename` path (`RenameUi.kind` only chooses which repository call runs).
* `bookmarkExisting(itemId)` adds only the `Bookmarks` membership of an item that already exists, so a
  result inside a profile can be bookmarked with **no new item record and no changed profile entry**:
  the entry (`SessionEntry`) and the bookmark (`Membership`) are two references to one item, and
  `prune()` keeps the item while either exists.
* `records.json` version 3; v1/v2 migrations run once and retain the old v1 payload under `legacy`.
  Future versions are quarantined rather than overwritten.
## Batch operations

`BatchReport(requested, changed, skipped, failed, message)` is what a batch actually did; every
batch method in `WishlistRepository` reads the store once, mutates an in-memory `State`, then writes
**once** through the existing temp-file + rename path. A write failure returns `changed = 0, failed =
requested` and leaves the previous file untouched; scan thumbnails are deleted only after the new
file is in place, so a failed batch cannot orphan data.

| Method | Scope |
|---|---|
| `copyItemsToFolder` | adds a `Membership`; skips workbook rows, an existing membership and a reserved target |
| `moveItemsToFolder` | removes the source `Membership` and adds the target one; bookmarks and third folders untouched |
| `removeItemsFromFolder`, `deleteBookmarks` | remove exactly one membership each, then `prune()` |
| `copySessionEntries`, `moveSessionEntries`, `removeSessionEntries` | work on one session; workbook rows are never promoted or removed |
| `moveSessionsToFolder`, `copySessionsToFolder`, `deleteSessions` | a session keeps its entries; a copy shares the same items and does not copy the scan thumbnail |
| `copyFolders`, `deleteFolders` | a folder copy duplicates sessions (same items) and direct memberships; the reserved folder is refused |

Selection is UI state (`SelectionUi` in `BatchModels.kt`), never persisted. The rules live in
`SelectionLogic` (plain Kotlin, unit-tested): `toggle`, `selectAll`, `clear`, `reconcile`,
`canMove`/`canCopy`/`canDelete`, `noun`, `hint`. `MainViewModel` owns the state and reconciles it in
`publish()` against the rows that are actually visible, so a delete can never leave an action pointing
at an invisible row.

The UI entry points are explicit and repeated, not hidden: `SelectEntry` (a labelled button with a
checkbox glyph) sits in the header of every selectable list, and `onLongClick` on a row calls
`startSelectionWith(kind, id, container)` as a shortcut. `SelectionToolbar` shows the count, a one-line
explanation and Select all / Clear / Done; `BatchActionBar` repeats the count and offers Copy / Move /
Delete plus Done, and only draws the actions `SelectionLogic` allows. `runBatch` re-reads the library
after every batch so the screen cannot be ahead of the file.

`BatchOperationsTest` additionally pins the failure path: with `records.json` marked read-only, a batch
returns `changed = 0, failed = n` and a reload still shows the old data.

A selection only ever holds one kind, so the action bar can be derived from `selection.kind` with no
mixed-selection logic. Session results move **out** into a folder rather than into another session: a
`SearchSession` is the immutable record of one operation, and re-homing a result would rewrite that
record.

## Image status and OCR text

* `data/ImageFiles` reads `OpenableColumns.DISPLAY_NAME` and exposes the pure `labelFor` mapping
  (provider name, then URI last segment, then a MIME-based fallback). `MainViewModel.onImagePicked`
  resolves it on `Dispatchers.IO` and applies it only after a successful decode, so a failed pick
  leaves the previous cover and its name alone. A camera capture passes no name, clearing it.
* The Read the Cover field is a `Text` inside `SelectionContainer`: read-only, no `BasicTextField`,
  so the text cannot be edited but can be selected and copied by the system toolbar.


## Offline matching

`CatalogBuilder` extracts and embeds workbook pictures inside the range; `CatalogIndex.rank` computes
cosine similarity against the captured embedding and returns `Config.TOP_MATCHES = 5`. The original
algorithm, model, range parsing and `.xlsm` support are unchanged from v2.1.0. Session saving is a
separate step in `WishlistRepository`; it cannot influence the ranking.

## Online pipeline

1. `ChromeLensPyBridge` → `PythonRuntime` → `lens_bridge.py` → `chrome_lens_py.LensAPI`.
2. `OcrResultParser` translates the flat dict and failure codes.
3. `OcrQueryExtractor` pairs block heights with source lines before filtering, then suggests a
   title/author.
4. `OpenLibraryBooksSource` and `GoogleBooksSource` run on `Dispatchers.IO`; each returns
   `Found`/`Empty`/`Failed`.
5. `OnlineAggregator` de-duplicates by provider key/URL, promotes bookmarked items and caps the list.
6. `SessionCache` is keyed by SHA-256 of the JPEG and guarded by a generation token. Disabling the
   feature or selecting a new image invalidates it; saved data is never touched.

Google Books may return 429 without a key; that is `OnlineFailure.RATE_LIMITED`. A partial failure
becomes a visible note on `CatalogueUi.Found`; only a total failure becomes `CatalogueUi.Failed`.

## Navigation and state

`AppTab { HOME, SEARCH, WISHLIST, MORE }` is held in `UiState`; `WishlistSection { FOLDERS, BOOKMARKS }`
and optional `folderDetail`/`sessionDetail` model the drill-down. Back handling closes the detail or
the bookmark form; it never deletes data. `Scaffold` + custom bottom bar avoids a navigation
dependency while keeping proper selected state and back behaviour.

Tab content is wrapped in `rememberSaveableStateHolder().SaveableStateProvider(ui.tab)` (**v2.3.1**).
A tab that leaves composition would otherwise discard its `rememberSaveable` values; the holder keeps
each tab's transient state (the `Bookmarks` filter text and sort, expanded cards, scroll position)
without moving any of it into `UiState`. Persisted state still belongs to the ViewModel only — the
`Bookmarks` drawer's open flag is `UiState.bookmarkPanelOpen` because it must survive a tab switch too.

`WorkbookCommentsScreen` (v2.3.0) is deliberately **not** an `AppTab`: it is a full-surface preview owned
by `UiState.comments.open`, opened from More with the staged workbook's hash, and its Back action closes
it. It therefore cannot change the selected destination, the camera or the matching state.

The source view (**v2.4.0-dev.1**) is an overlay inside that preview, not a screen of its own:
`WorkbookCommentsUi.source` is opened from an image, a comment or a catalogue row and closed with Back or
its own button, and it never touches `section`, `selectedSheet`, `imageSearch`, `imageFilter`,
`catalogueFilters`, `imageSort` or `selectedImages`. Because it is not an `AppTab` and not a dialog stack,
closing it returns to exactly the list the user left, and a workbook change (`open`/`reload`/`close`)
always resets it — the hash guard in `sourceBelongsTo` drops a late read.

## Testing

* `app/src/test`: **487** JVM tests. Repository/model/migration/workflow tests are in `LibraryTest.kt`;
  parsers/aggregator/cache in `OnlineSearchTest.kt`; legacy-comment parsing and its cache in
  `LegacyCommentScanTest.kt`, `LegacyCommentEdgeCaseTest.kt`, `CommentImageCacheTest.kt` and
  `WorkbookCommentsUiTest.kt`; the source-location mapping, worksheet identity and worksheet window in
  `SourceNavigationTest.kt`; the shared card text in `BookmarkPresentationTest.kt` and the bookmark form
  rules in `BookmarkFormTest.kt`; the existing offline pipeline tests remain.
  Four live catalogue tests are gated by `-DonlineCheck=1`.
* `app/src/androidTest`: three tests on a device/emulator:
  * real OCR through Chaquopy on a bundled cover (`OcrDeviceTest`);
  * real catalogue sources;
  * `OnlineSearchController` search → merged `CatalogueUi.Found`.
* `tools/lens_bridge_check.py` tests the Python envelope and input conversion without network.

## Known architectural limits

* The Python interpreter is process-wide and a running Lens call cannot be preempted (only its
  answer can be discarded).
* Workbooks sharing a cell share a workbook-item identity; documented limitation of `wb:<cell>`.
* Online results are catalogue records, not shopping listings; no price/stock provider exists.
* The source view is a window (six rows of context, the sixteen columns nearest the verified cell), not a
  spreadsheet renderer: a location outside it is reached by opening that location, not by scrolling.
* No database was introduced: the single JSON store keeps backup/migration simple and is adequate
  for the app's data volume; it is not intended for concurrent writers.
