# Architecture and developer notes (v2.4.0)

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
  around a target row (every column the worksheet uses, the catalogue header carried separately, each cell with
  its own format and hyperlink — see the v2.4.0 note below). The controller reads a
  sheet once into `sheetCells` (cleared on open/rescan/close) and guards the result with the workbook
  hash, so a late read for another workbook is dropped. `WorkbookCommentsUi.source` holds the overlay and
  its transitions are pure functions (`openSource`, `sourceLoading`, `sourceWindow`, `sourceFailed`,
  `closeSource`), which is what the unit tests drive.
* **v2.4.0-dev.2** adds one shared layer, `core/catalog/`, instead of five feature-specific pipelines:
  * `CatalogField` is the canonical field list (Title, Circle, Artist, Product ID, URL, Price, Description,
    Publication date, Tags). Circle and Artist are two fields and nothing merges them.
  * `ColumnMappingProfile` maps a header **name** to a field — one column set per worksheet identity, plus the
    optional workbook-wide set a schema-1 profile carries; `ColumnMappings.resolve` turns it into a
    `ResolvedMapping` per worksheet and reports `HEADER_MISSING`, `HEADER_AMBIGUOUS`, `SHARED_HEADER` and
    `REQUIRED_UNMAPPED`. A field is resolved only when exactly one column carries the mapped header, so an
    ambiguous mapping leaves the field **unmapped** rather than picking a column. `ColumnMappingCodec` is a
    pure JSON codec over the existing `MiniJson` (schema 2, both forms read); `data/ColumnMappingStore` writes
    `files/mappings/profiles.json` (temp file + rename, never overwritten on a read failure).
  * `WorkbookCatalogBuilder.build(sha, sheets, tables, images, profile)` produces `CatalogEntry`s keyed by
    **worksheet part path + row** (the same identity v2.4.0-dev.1 used for a source location) and attaches
    the images the comment scanner already verified. It reads no file and infers nothing: a value is
    `MAPPED`, `MISSING`, `UNMAPPED` or `AMBIGUOUS`, and a cover exists only when an association is verified.
  * Four pure consumers read that catalog: `HealthReportBuilder` (findings with a stable id, severity,
    evidence and a resolution class), `CollectionAnalyticsBuilder`, `CollectionTimelineBuilder` and
    `GalleryView` (filtering/sorting). `CatalogDates` parses the date formats the app actually sees and
    returns null for anything ambiguous, so a date is never guessed.
  * `WorkbookCommentsController.refreshInsights` builds the catalog and all four results in one
    `Dispatchers.IO` pass, guarded by the workbook hash (`insightsBelongTo`), and stores them together on
    `WorkbookCommentsUi`. Applying a profile, saving one or rescanning rebuilds them; nothing is recomputed
    per recomposition, and the UI converts nothing itself.
* **v2.4.0-dev.3** completes the column and date model instead of patching the screens:
  * `CatalogueReader.fromCells` now keeps every column between the first and the last header, including one
    whose header cell is blank when it carries data, so a blank header can no longer shift every value to its
    right by one. `CatalogueField` keeps `index` (position in a record's `values`) and `column` (source
    column) apart, and `label` names an unnamed column by its letter.
  * `SheetWindows.MAX_COLUMNS` went from 16 to 96: the source view draws every column a real worksheet uses
    instead of the sixteen nearest the target, and `SheetWindow.hiddenColumns` reports the remainder when a
    sheet is wider than the bound, so a wide sheet is never silently shortened.
  * `TimelineDates` holds the date-column model: `SheetDateChoice` (published/acquired header per worksheet),
    `TimelineDateSettings` (per workbook SHA-256, so a shared mapping profile cannot carry a date choice into
    another workbook), and `DateColumnReport`/`DateColumnState` (READY, NOT_SET, HEADER_MISSING,
    HEADER_AMBIGUOUS, NO_READABLE_DATES) computed by a pure `inspect`. `TimelineDateCodec` +
    `data/TimelineDateStore` persist it as header names in `files/mappings/dates.json`.
  * `CollectionTimelineBuilder.published`/`acquired` read **only** the chosen column (never the mapped
    `PUBLISHED` field, never each other's column), and `CatalogDates.parse(raw, allowSerial = true)` reads an
    Excel serial in exactly those columns. `CollectionTimeline.dateColumns` carries the per-worksheet evidence
    into the UI.
  * The gallery grid owns a `LazyGridState` and resets it from `WorkbookCommentsUi.galleryOrderKey`
    (workbook + sort + direction + filters), so a dataset/order change returns to the first card while a
    thumbnail decode or a dialog does not move the user.
  * `CatalogueView.CARDS`/`TABLE` on `WorkbookCommentsUi` picks the record cards or the worksheet table; the
    table shares one `ScrollState` between its header row and every data row and keeps its rows lazy.
* **v2.4.0-dev.4** makes the two reports scopeable and the timeline filterable, without duplicating a
  pipeline:
  * `SheetScope` is the selection model: a set of worksheet **identities** (`SheetScope.keyOf(path, name)` —
    the package part path, or `name:<display>` when the analysis could not read the path) where an empty set
    means every worksheet. `WorkbookCatalog.sheetChoices`/`sheetKeys` expose the selectable worksheets and
    the identities to reconcile against; `SheetScope.reconciled` drops identities the workbook no longer
    has, and falls back to *all* when nothing survives.
  * `HealthReportBuilder.build(catalog, analysis, model, scope)` and
    `CollectionAnalyticsBuilder.build(catalog, scope)` **calculate** for the scope: scoped entries/tables/
    mappings/issues feed the same code paths, so every summary count, chip, breakdown and cover total comes
    from the scoped record list. Worksheet-specific findings are filtered by scope; package-wide ones (scan
    limit, parsing diagnostics, unreferenced media) are reported for the whole-workbook scope only.
  * `WorkbookCommentsUi.healthScope`/`analyticsScope` are independent, edited through a draft
    (`ScopeDialogUi`) so a mis-tap costs nothing until it is applied. `WorkbookCommentsController`
    recalculates both reports off the main thread from the catalog already in memory
    (`rebuildScopedReports`, guarded by a monotonic `scopeRevision` plus the workbook hash and catalog
    identity), and reconciles the selection before every full rebuild.
  * `TimelineFilter` (query + from/to year) with `TimelineFiltering.apply` producing a `TimelineView`
    (filtered timeline + the count of undated records the range could not judge). Filters are stored per
    `TimelineMode` (`WorkbookCommentsUi.timelineFilters`), so Published and Acquired never share one.
  * `WorkbookCommentsUi.timelineOrderKey` (workbook + mode + direction + filter) drives the timeline's owned
    `LazyListState`, exactly as `galleryOrderKey` drives the grid: the mode switch, an ordering change and a
    filter change return to the top, and nothing else moves the list.
  * `InsightsTab` order is HEALTH, ANALYTICS, GALLERY, TIMELINE, FIELDS — the displayed order **is** the
    enum order, so there is no second list to keep in sync (pinned by `InsightsOrderTest`).
* **v2.4.0** adds the number-format layer and the gallery's own worksheet scope, and removes a mode:
  * `core/excel/comments/NumberFormats` reads what a numeric cell **is** from the workbook rather than from
    its value: `WorkbookStyles.read(zip)` parses `xl/styles.xml` (`numFmts/numFmt` + `cellXfs/xf@numFmtId`,
    resolved through the ECMA-376 built-in table) and `workbookPr@date1904` once per package;
    `NumberFormatCodes` classifies a custom code by its own tokens (`y`/`d`/`h`/`s`/`m`, `%`) after removing
    quoted literals, escapes and colour brackets; `CellNumberFormat.display` renders a serial as
    `2024-01-01` / `2024-01-01 14:30` (honouring the 1900/1904 epoch, and refusing serials 0 and 60) or a
    fraction as `12.35%` (scaled once, capped at two decimals).
  * `SheetCells` now keeps each cell's `s` index and whether it is numeric (`CellValue.styleIndex/numeric`);
    `CatalogueReader.fromCells` fills `CatalogueRecord.displayValues` beside the untouched `values`, and the
    Catalogue table and cards draw `display`, while filters, search, the canonical catalog, the timelines and
    the export keep the stored text. `CatalogueTable.valuesOf`/`matches` and
    `ImageIndex.matchesCatalogueFilters` read the display text, so the filter picker and its matching agree.
  * `WorkbookAnalysis.date1904` carries the workbook's own date system into `CatalogDates.parse`, so the
    table, the date-column preview, both timelines and the analytics' year buckets read a serial the same way.
  * `GalleryFilter.sheets` is a `SheetScope`, so the Gallery selects all/one/several worksheets by
    **identity**; `GalleryView.rows` filters on it before any other filter, `WorkbookCommentsUi.galleryEligible`
    is the count line's denominator, and `galleryOrderKey` includes `SheetScope.key` so a scope change resets
    the grid while an unrelated recomposition does not. `ScopeTarget.GALLERY` reuses the one worksheet picker;
    the gallery's selection lives in its filter (`WorkbookCommentsUi.scopeOf`/`scopeCommitted` read and write
    it there), so the bar and the dialog cannot disagree.
  * `TimelineMode` has exactly `PUBLICATION` and `ACQUISITION`: the `IMPORT` mode and
    `CollectionTimelineBuilder.imported` are gone. Import timestamps remain workbook metadata
    (`WorkbookLibraryEntry.importedAt`, `WorkbookCommentsUi.importedAt`); nothing turns them into a timeline.
  * `CatalogField.DEFAULT_ORDER` + `core/catalog/DefaultOrder` give the Timeline its tie-break: the date stays
    the primary key, and records that share one are ordered by the user's chosen column (`DefaultOrderValue`:
    numbers as numbers, text after numbers, blanks last), then by the verified source identity.
    `CollectionTimelineBuilder.entriesInOrder`/`ordered` apply that in either direction, so flipping
    newest/oldest never reverses the tie-break; `TimelineEntry.defaultOrder` carries the workbook's own text.
  * `core/catalog/InsightsView` + `data/InsightsViewStore` persist the three worksheet scopes and each
    timeline's year range per workbook (SHA-256) in `files/mappings/insights-views.json`. `WorkbookCommentsUi`
    maps them in both directions (`persistableView`/`restoredView`), and the controller writes only from the
    actions that change them — never from a rebuild — so opening, scrolling or restoring cannot rewrite it.
  * Numeric display is one rule: `Formatting.decimal` (≤ 2 decimals, trailing zeros dropped) is used by
    `NumberFormats` for a cell whose format declares decimals (`CellFormatKind.NUMBER`) and by the similarity
    score, while `General`/integer cells and identifiers are never reformatted. `GallerySort` lost its
    `COVER` entry: cover state is a filter over the verified association, not an ordering.
  * `core/excel/comments/SheetLinks` reads a worksheet's `<hyperlinks>` with its `.rels`: an external target
    comes from the relationship (`TargetMode="External"`), an internal one from `location`, a `ref` range is
    expanded (bounded by `CellLink.MAX_RANGE_CELLS`) and anything unresolvable yields no link.
    `CatalogueRecord.links` holds them **by source column**, `CellPeekUi` carries one cell's value and link
    (identified by worksheet + row + column, never by screen position), and `WorkbookCommentsController`
    copies the displayed text or opens the destination (browser for `http(s)`/`mailto`, the source overlay for
    `Sheet!Cell`). `ui/WorkbookBrowserPanels.TableCell` is the tappable, underlined cell.
  * A rescan rebuilds in place: `reloadingForRescan()` drops everything derived from the old bytes and keeps
    every user choice, `analysisLoaded()`/`reloadFailed()` settle the busy state, and the controller calls
    `refreshInsights()` after a successful scan when `wantsInsights` — the report screens no longer depend on
    a re-entry into the directory. `analyticsLoaded`/`reloaded` deliberately do not touch profiles, scopes,
    filters or the mapping draft.
  * `WorkbookCommentsUi.mappingFor(table)` is the mapping one worksheet is read with — what was applied to it, else
    the selected profile's entry for it, else its detected headers — so a manual choice (including a different
    Default order column) survives a rebuild or a rescan; `isDetectedMapping` and `mappingSourceLabel` are what the
    Fields screen shows. The shared header no longer has a "Sheets" button: the worksheet picker belongs to the
    section that filters by it.
  * **v2.4.0 final round — the source preview reads through the same layer, and the mapping is persisted:**
    * `SheetWindowCell(text, display, link)` is one cell of the source window: the **stored** text (never
      rewritten), the same cell as the workbook **displays** it, and the workbook's own hyperlink. `display`
      comes from `WorkbookStyles.display(styleIndex, text, numeric)` — the identical call
      `CatalogueReader.fromCells` makes — so the two views cannot disagree; `SheetWindowRow.values`/`displays`
      stay aligned with `SheetWindow.columns`, and `SheetWindow.headerCells` carries the header row's cells
      (with their links) when it sits outside the window, with `headerValues` derived from them.
    * `SheetWindows.build(…, styles, links)` is the only builder; omitting both parameters (a fixture, or a
      package without `xl/styles.xml`) leaves every cell showing exactly the stored text. `SheetWindow.cellAt(row,
      column)` looks a cell up by **source identity** — the header row included — and returns null for anything
      the window does not draw, which is what keeps a tap from ever describing a different cell.
    * `WorkbookCommentsUi.peekSourceCell(window, row, column)` opens the **existing** `CellPeekUi`, so one
      dialog serves both previews (`CellPeekDialog` is rendered once at the root of the screen). The value is
      the displayed text (Copy puts what the user sees), the link is that cell's own, and the label is the
      worksheet's header or `Cell <letter>`.
    * The controller caches what it reads for the preview — `styles: WorkbookStyles?` and
      `sheetLinks: HashMap<sheetPath, Map<Long, CellLink>>` — beside the existing `sheetCells`, and
      `clearCellMetadata()` drops them on open, rescan and close, so cells from one workbook can never be
      formatted or linked with another's metadata. `ui/WorkbookCommentsScreen.WindowCell` is the tappable,
      underlined cell.
    * `core/catalog/WorkbookMapping` + `data/WorkbookMappingStore` persist the mapping **in effect** per
      workbook (SHA-256) in `files/mappings/workbook-mappings.json`, beside the profiles and the date columns:
      a stored profile name is applied as that profile, a manual mapping (or a deleted profile) is restored as
      a manual one, and *Detected* removes the entry. `WorkbookCommentsUi.persistableMapping`/`restoredMapping`
      map it in both directions; the controller writes it only from `applyMapping`, `saveProfile`,
      `selectProfile` and `deleteProfile`, never from a rebuild.
    * `Map<CatalogField, String?>.withField(field, header)` is the picker's single edit rule
      (`WorkbookCommentsController.mapField` uses it), so the Default order column is changed by exactly the
      same code as every other field. The Fields screen gives that column its own card with a bordered
      `MappingButton` (**Change**/**Choose column**, **Clear**, **Use detected**), driven by
      `WorkbookCommentsUi.detectedHeader`/`defaultOrderIsDetected`, and `HeaderPickerDialog` keys its rows by
      position so two columns sharing a header cannot break it.
  * **v2.4.0 final (rc.6) — Detected and a profile are views, and a profile is a per-worksheet snapshot:**
    * Three separate things, never one mutable mapping: **Detected** (`ColumnMappings.detectedProfile(worksheet)`,
      computed whenever it is needed and never stored), each **saved profile**
      (`ColumnMappingProfile.sheets: Map<worksheetKey, columns>` in `files/mappings/profiles.json`), and the
      **per-worksheet edits** applied on top of the selected view (`WorkbookCommentsUi.sheetMappings`, keyed by
      `SheetScope.keyOf(partPath, name)`).
    * `WorkbookCommentsUi.activeProfile` is which view is selected (null = Detected); `selectProfileView(name)` is
      the whole switch, setting the view and dropping the edits that belonged to the previous one, and it copies,
      merges and rewrites no profile. `profileOf`/`selectedProfile`/`isDetectedView` read it.
    * Resolution is one path: `activeColumnsFor(sheet)` = the edit applied to it → the selected profile's entry for
      **that** worksheet (`ColumnMappingProfile.columnsFor(key)`) → its detected headers; `baselineColumnsFor` is
      the same without the edit. `WorkbookCatalogBuilder.build(…, profile, sheetMappings)` applies exactly this per
      table. A profile with no entry for a worksheet (added later, or another workbook's) is not applied to it; a
      schema-1 profile (no `sheets` at all) still is, per worksheet, narrowed to the headers it really has.
    * `MappingEquality` stays the one rule for "do these mean the same thing" (blank = unassigned, headers
      normalized as `ColumnMappings.resolve` does, a field on one side only is a difference), and
      `isDetectedMapping = sameColumns(effectiveMappingColumns, detectedMappingColumns)` stays a statement about
      the mapping, not about a click. `applySheetMapping` compares the draft with `baselineColumnsFor(mappingSheet)`
      and stores an edit **only** when it differs: an unchanged mapping writes nothing and keeps the chip blue,
      while a real edit changes one worksheet.
    * `profileSnapshot(name)` builds what **Save profile** writes: one entry per worksheet — the inspected one from
      the draft, every other from the edit applied to it, the selected profile's entry, or its own detection. That
      is the inheritance rule, and it is why the worksheet on screen is never a template for the others. The new
      profile then becomes the selected view, so nothing is shown twice and no other profile is touched.
    * `ColumnMappingProfile` is schema **2** (`sheets` plus the optional workbook-wide `columns` that schema-1
      files carry); `ColumnMappingCodec` reads and writes both. `WorkbookMapping`/`WorkbookMappingCodec` are schema
      2 as well, storing the selected view (`profile`) beside the per-worksheet edits; a schema-1 file still
      decodes into `legacy` and is migrated by `restoredMapping(mapping, tables)` **per worksheet** (only the
      columns that worksheet really has). The controller restores it *after* the analysis is known
      (`restoreMapping()`), and a view whose profile has been deleted falls back to Detected.
  * **v2.4.0-rc.7 — the blank-row stop rule is reported, and the default range is honest.** The reader
    still ends a table at the first all-empty row (`CatalogueReader.fromCells`), because skipping blank rows
    could merge two unrelated blocks that share a sheet; what changed is that the consequence is now data:
    `CatalogueTable.truncation` (`CatalogueTruncation(blankRow, skippedRows, firstSkippedRow)`) counts the
    rows below the gap that carry data **in the table's own columns** (`truncationBelow`, bounded by the
    same `maxRecords` the reader uses), so a note in a far column is not a record. It is surfaced three
    ways: `ui/WorkbookBrowserPanels.CatalogueBrowser` prints one warning line for the scoped tables and
    `TableSheetHeader` repeats it under that sheet's header, and `HealthReportBuilder.structure` adds
    `structure:blank-row:<sheet>` (STRUCTURE / WARNING / MANUAL) naming the rows and the fix. `Config.DEFAULT_RANGE`
    is `A1:Z5000` (was `A1:B20`): `WorkbookImageExtractor.plan` filters the workbook's own anchors, so width
    costs nothing per empty cell, and `UiState.rangeText`/`Prefs.range` start there while a stored or typed
    range is never overwritten. `MainViewModel.findTopMatches` names the range in its empty-result message.
    Pinned by `CatalogueTruncationTest` (6) and `DefaultRangeTest` (3) and re-verified on the Pixel 8 AVD
    (see `BookCoverMatcher-QA/reports/v2.4.0-rc.7/emulator-test-report.md`).
* The five features are sub-tabs of `CommentsSection.INSIGHTS` (`InsightsTab.HEALTH`/`ANALYTICS`/`GALLERY`/
  `TIMELINE`/`FIELDS`) rendered by `ui/WorkbookInsightsPanels`, not new top-level destinations.
  A gallery card, a timeline entry and a finding with a record or an image open the row/cell through the
  existing `openSource`/`openSourceCell` path, so there is one source-navigation implementation.
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

* `app/src/test`: **721** JVM tests. Repository/model/migration/workflow tests are in `LibraryTest.kt`;
  parsers/aggregator/cache in `OnlineSearchTest.kt`; legacy-comment parsing and its cache in
  `LegacyCommentScanTest.kt`, `LegacyCommentEdgeCaseTest.kt`, `CommentImageCacheTest.kt` and
  `WorkbookCommentsUiTest.kt`; the source-location mapping, worksheet identity and worksheet window in
  `SourceNavigationTest.kt`; the shared card text in `BookmarkPresentationTest.kt` and the bookmark form
  rules in `BookmarkFormTest.kt`; the v2.4.0-dev.2 catalog layer in `ColumnMappingTest.kt`,
  `ColumnMappingStoreTest.kt`, `WorkbookCatalogTest.kt`, `HealthReportTest.kt`,
  `CollectionAnalyticsTest.kt`, `CollectionTimelineTest.kt`, `GalleryViewTest.kt` and
  `WorkbookInsightsUiTest.kt` (87 tests, all against `CatalogFixtures` — no workbook file is staged);
  the v2.4.0-dev.3 column and date work in `WorksheetColumnsTest.kt` (7), `TimelineDatesTest.kt` (18),
  `TimelineDateUiTest.kt` (9) and `GalleryScrollTest.kt` (6), plus `SampleWorkbookColumnsTest.kt` (8), which
  runs through the production reader on `test_excel/doujin.xlsx` and is skipped when that 40 MB file is
  absent, exactly like `RealWorkbookIntegrationTest.kt`;
  the v2.4.0-dev.4 filters and scopes in `TimelineFilterTest.kt` (10), `SheetScopeTest.kt` (5),
  `ScopedReportsTest.kt` (8), `InsightsFilterUiTest.kt` (11) and `InsightsOrderTest.kt` (3);
  the v2.4.0 work in `GallerySheetFilterTest.kt` (11: the four selection modes, the composition with every
  gallery filter and the search, sorting, stale keys, workbook replacement and the scroll key),
  `CatalogueNumberFormatTest.kt` (11: both date systems, date-time, percentages, decimal measurements,
  ordinary numbers and the code classifier) and `CatalogueFormattingWorkbookTest.kt` (4: a real OOXML package
  with a `styles.xml`, read end to end), and `CollectionTimelineTest.kt` (15, the 1904 date system included);
  `DefaultOrderTest.kt` (10: the mapping, numeric/text/blank ordering, both date meanings, both directions,
  the fallback without a mapping and the value type) and `InsightsViewPersistenceTest.kt` (8: codec, store and
  the state in both directions, stale keys included);
  the latest round in `SheetLinksTest.kt` (7: external/internal links, ranges, missing relationships,
  odd destinations, and a real package end to end), `RescanStateTest.kt` (4: a rescan keeps every choice,
  rebuilds in place, and settles its busy state) and `CellPeekAndMappingTest.kt` (5: the cell dialog's
  identity, value and link, and the precedence of a manual mapping);
  the final round in `SourceWindowFormattingTest.kt` (14: a real package with its own `styles.xml` and
  hyperlinks — the same cell reading identically in the table and the window, dates, percentages, currency and
  `General` cells, links attached to their own cells, blank cells, the original row/column identity of a
  nearby row, a header carried outside the window, and the dialog's value, label, link and silence for a cell
  the window does not draw), `MappingPersistenceTest.kt` (13: both codecs' tolerance, the selected view beside
  the per-worksheet edits, the store, `persistableMapping`/`restoredMapping` for an edit and for a deleted
  profile, an entry that means the selected view, both migrations, the `withField` edit rule and
  `detectedHeader`/`defaultOrderIsDetected`), plus the real workbook's table-versus-window check in
  `SampleWorkbookColumnsTest.kt`;
  the mapping and profile-view fixes in `DetectedStatusTest.kt` (14: the whole status matrix — applied unchanged,
  equivalent objects, changed assignments, restored assignments, cleared fields, blanks, per-worksheet status,
  navigation, restored state, and semantic equality including empty values), `WorksheetMappingIsolationTest.kt`
  (11: three worksheets with different header rows, saving while each of them is inspected, the inheritance of
  untouched worksheets, an empty value that must not unmap a sibling, deriving a profile from a profile,
  isolation through the catalog and a profile that knows none of these worksheets) and
  `ProfileViewSwitchingTest.kt` (7: Detected → profile → Detected → profile, two profiles in turn, the draft
  following the view, an unknown profile name, no profile changed by any switch, an applied edit not leaking into
  a profile and being dropped with the view, and an unsaved draft never written into a profile);
  the existing offline pipeline tests remain.
  Seven tests are opt-in: four live catalogue checks (`-DonlineCheck=1`) and the three real-workbook tests.
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
* The source view is a window (six rows of context, every column the worksheet uses up to a hard bound), not
  a spreadsheet renderer: a location outside it is reached by opening that location, not by scrolling.
* The health report, analytics and timelines analyse **one workbook** at a time and only what the parser can
  see. There is no cross-workbook aggregate and no month-level timeline grouping; the timeline groups by year,
  and Published/Acquired exist only when the user assigned a column to each of them.
* Formatted dates and percentages are a **display** layer: `CatalogueRecord.values` keeps the stored text, and
  the CSV export and the canonical catalog read that. The source window shows the stored cell, because it is
  a view of the worksheet part rather than of the display layer.
* A mapping profile stores header names, so a *renamed* column is reported as `HEADER_MISSING` rather than
  re-matched by position. That is deliberate: a wrong guess is worse than an unmapped field.
* No database was introduced: the single JSON store keeps backup/migration simple and is adequate
  for the app's data volume; it is not intended for concurrent writers.
