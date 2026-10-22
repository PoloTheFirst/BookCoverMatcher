# Architecture and developer notes (v2.2.0)

## Layers

```
UI (Compose)                MainViewModel (AndroidViewModel)          Services
────────────────            ─────────────────────────────────         ───────────────────────────
AppScreen / bottom nav  ──▶ UiState (StateFlow)                   ┌─ CameraController (CameraX)
HomeScreen / SearchScreen    camera, workbook, results,            ├─ OrtClipEmbedder (ONNX)
WishlistScreen               folders/sessions/bookmarks,            ├─ OnlineSearchController
MoreScreen                   dialogs, prompt/toast                  │   ├─ OCR bridge (Chaquopy)
ProductResultCard            │                                      │   └─ catalogues (JVM HTTP)
OnlineSearchPanel            ├─ offline: CatalogBuilder +           └─ WishlistRepository (file store)
dialogs                      │            CatalogIndex
                             ├─ library: WishlistRepository
                             └─ online: OnlineSearchController.ui (mirrored)
```

* `core/` is plain Kotlin/JVM: no `android.*` imports. It is covered by the desktop JVM suite.
* `MainViewModel` owns the single `UiState`; tab selection and dialog state live there. It never
  stores authoritative saved data in memory.
* `SearchScreen` (in `MainScreen.kt`) is the workflow image's **Search Results** screen: three
  `SearchResultsTab` values (`WORKBOOK`, `ONLINE`, `BOOKMARKS`) with live counts, switched through
  `MainViewModel.selectSearchTab`. The selected tab is navigation state only, and each tab renders
  the same state holders and repositories as before, so no persistence path exists in the UI layer.
  The tab track itself is `ui/components/SegmentedTabRow` + `SegmentedTab`, shared with the Wishlist
  sections.
* `OnlineSearchController` owns its own `StateFlow`; `MainViewModel.init` collects it into
  `UiState.online`. That mirror is required for the panel and the persisted switch to agree.
* `WishlistRepository` is single-threaded via a dedicated executor and writes `records.json` with a
  temp file + rename.

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
* `records.json` version 3; v1/v2 migrations run once and retain the old v1 payload under `legacy`.
  Future versions are quarantined rather than overwritten.

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

## Testing

* `app/src/test`: 165 JVM tests. Repository/model/migration/workflow tests are in `LibraryTest.kt`;
  parsers/aggregator/cache in `OnlineSearchTest.kt`; the existing offline pipeline tests remain.
  Two live catalogue tests are gated by `-DonlineCheck=1`.
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
* No database was introduced: the single JSON store keeps backup/migration simple and is adequate
  for the app's data volume; it is not intended for concurrent writers.
