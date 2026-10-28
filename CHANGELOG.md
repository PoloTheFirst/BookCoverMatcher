# Offline legacy-comment image preview

**v2.3.0 adds one offline feature**: the note text and the pictures stored in legacy Excel comments of the
chosen workbook can now be browsed, viewed and exported in the app. Nothing is sent anywhere, no macro is
run, and the workbook is never written to.

## v2.3.0 (2026-10-28)

`records.json` is untouched, the comparison pipeline is unchanged, and the feature is read-only: it opens
the staged copy of the workbook through the existing `ZipSource` and never writes to it.

### Added

* **Workbook comments screen** (More -> *Workbook comments & images*). Lists every commented cell of the
  chosen `.xlsx`/`.xlsm` with a marker that distinguishes **text only** (`T`), **has images** (`▣`) and
  **image reference unresolved** (`!`), plus a sheet filter. Tapping a row opens the note's author, full
  text (rich-text runs joined, line breaks kept), its thumbnails and any unresolved-reference detail.
* **All images in this workbook**: every file under `xl/media`, with its format, its comment associations
  (sheet, cell, text preview) or the honest label *No association found by this previewer*. Files that
  cannot be decoded show a placeholder instead of crashing the browser.
* **Full-screen viewer** for one image, with its sheet/cell associations, close/back handling and the
  same export action.
* **Export** through the Storage Access Framework: the original package bytes are copied unchanged (no
  re-encoding of a thumbnail), and success is reported only after the copy completed.
* **Cache** (`files/comments/comments-<sha256>.json`, schema version 1): reopening an unchanged workbook
  reuses the parsed result and shows *cached* in the header; **Rescan** forces a fresh parse.
* Version code/name: **207 / 2.3.0**.

### Added — saved workbooks, worksheet picker, catalogue-aware browsing

* **Saved workbooks** (More → *Saved workbooks*): an import is copied byte-for-byte into
  `files/workbooks/<id>.<xlsx|xlsm>` with a JSON index, so a saved workbook keeps working when the
  original file moves. Display names are metadata only (renaming never touches the file), pinning and
  recency survive restarts, importing the same bytes twice is recognised instead of duplicated, and a
  second file with the same name gets its own id instead of overwriting the first. Removal deletes the
  copy plus that workbook's cached parse after confirming exactly that.
* **Searchable worksheet picker** (header → *Sheets*): every worksheet in workbook order with a search
  field, the current selection marked, and hidden/very-hidden sheets labelled from the workbook's own
  `state` attribute. The tab strip is no longer the only way to reach a sheet.
* **All Images browser** upgraded: free-text search (filename, worksheet, cell, comment preview and every
  catalogue value), filters for *Linked / Unreferenced / Unresolved* with counts, sorting by name, size,
  cell or title in both directions (missing values always last, deterministic), grid and list modes, and
  multi-select with **Export…** into a folder chosen through the Storage Access Framework. Original bytes
  are copied, names are sanitised and de-duplicated, and a UTF-8 `manifest.csv` (RFC 4180 escaping) is
  written beside them with the status, worksheet, cell and catalogue fields.
  `Linked`, `Unreferenced` and `Unresolved` stay distinct: a reference that failed to resolve is never
  folded into "unreferenced".
* **Catalogue tab**: the workbook's tables are discovered from their own header row (structural detection,
  the header cells become the field names; the alias list only chooses which columns a card shows first,
  and no sheet name or column number is hardcoded). Each record is searchable across all of its columns
  and says whether a **verified** image is linked to it. A coverage line reports records, records with a
  verified image, unresolved references and unreferenced media.
* **Storage panel** on the library screen: saved copies and regenerable caches are reported separately,
  and *Clear parsed caches* deletes only the caches (the comment parse cache and the index cache).
* **Workbook comparison** (library → select two workbooks → *Compare*): a read-only **metadata-level**
  comparison of two saved workbooks. It reuses the saved parse when one exists, compares worksheets by
  **name** (never by position) and reports sheets added, removed and changed, plus workbook order,
  visibility (`visible`/`hidden`/`veryhidden`), each sheet's declared used range (`<dimension>`), legacy
  comment counts, verified image links, unresolved references, media files, unreferenced media and
  catalogue record/header differences. A side that cannot be read is reported as failed and its counts as
  *unknown*; unknown is never rendered as `0`, and equal totals are explicitly not treated as proof that
  the two workbooks are equivalent. This is **not** a cell-by-cell spreadsheet diff, and no macro is read.
  Worksheets are also labelled with their declared used range in the picker.
* **Thumbnail size** (S/M/L) in the All Images grid, and a *Clear* action that removes only the catalogue
  filters while the search text and association chips stay as typed.
* **Catalogue discovery skips pivot-table sheets**: a worksheet that hosts a pivot table (the
  `pivotTable` relationship Excel writes) is excluded, so a pivot's `Row Labels` / `Grand Total` band is
  never offered as a catalogue or as 35 filter fields. This was found with the real workbook in
  `test_excel/DoujinNew.xlsx`, whose `PIVOT - Area` sheet previously appeared as a table.
* **Real-workbook validation**: `test_excel/DoujinNew.xlsx` and `DoujinOld.xlsx` (36–40 MB, 999 legacy
  comments each, 124/103 VML image links, 165/139 media parts, 8/7 sheets, 4 hidden pivot sheets) are
  parsed through the production entry points (`JavaZipSource` → `CommentImageScanner` →
  `WorkbookAnalyzer`) by `RealWorkbookIntegrationTest`. The test skips itself when `test_excel/` is absent,
  so the suite stays reproducible. Package facts are asserted independently (zip inventory: one
  `comments1.xml` with 999 `<comment>` elements, one VML drawing with 999 shapes, 124/103 image
  relationships), and the two workbooks are compared as a real version pair (C109 added, `PIVOT - BAR `
  hidden, `LIST` widened `A1:T1000` → `A1:X1000`, media 139 → 165).
* **Saved-workbook Open fixed**: tapping *Open* used to leave the library on screen (the library was
  rendered before the preview), so the preview only appeared after *Back*. The full-screen precedence is
  now a pure `routeFor` decision (comparison → comments → library → main) with tests: the preview shows
  immediately, *Back* returns to the library, an already-loaded workbook is not rescanned, and a second
  tap on *Open* does not start a duplicate load.
* **Worksheet selector fixed**: *Sheets* keeps its label on one line (minimum width, no fixed 64 dp), and
  the worksheet strip is a horizontally scrolling `LazyRow` over **every** worksheet — previously only the
  first four chips were rendered and the rest could not be reached. Labels are ellipsised (full name kept
  as the accessibility description and as the selection identity, trailing spaces included).
* **Dynamic catalogue filters**: the first-two-fields / six-values cap is gone. *Filter…* in the Images tab
  lists every column of the selected catalogue (searchable, lazily rendered), then every value of the
  chosen column (searchable, never truncated). Field identity is **(worksheet, column)** — two columns both
  titled `%` stay independent (`% (F)`, `% (H)`), a blank header becomes `Column D` — and filters combine
  with AND. Active filters are removable chips; *Clear* removes the catalogue filters without touching the
  image search.
* **Worksheet scoping for Images and Catalogue**: the same selector now drives both views. Images are
  scoped by their parsed associations (media with no association never appears under a worksheet), and the
  Catalogue tab shows only the catalogues of the selected worksheet (with an explicit empty state when it
  has none). Switching worksheets drops filters that no longer belong to the new scope.
* **Image association filter fixed**: `Linked` / `Unreferenced` / `Unresolved` are now one typed value
  (`ImageState?`, null = `All`) instead of a set. The old toggle could produce `{Linked, Unreferenced}`,
  which highlighted **no** chip and showed both states. Tapping a chip now updates the highlight and the
  list from the same value; `All` clears it, and search, sorting and worksheet scope are preserved.
* **Scan from a saved workbook**: Home and the Search workbook tab keep the existing *Choose file* flow and
  gain *Saved*, which opens the app-managed library (same ids and hashes as More → Saved workbooks). The
  chosen copy becomes the scan source through the existing staged-workbook path — no second scanner, no
  re-import, no macro execution, and the original file is untouched. A workbook that fails to open clears
  the source instead of silently scanning the previous one, and the original filename is always shown so
  duplicate display names stay distinguishable.
* **Natural filename sorting**: `image2.jpg` now sorts before `image10.jpg` (Windows Explorer / Finder
  style) wherever `Name` sorting is used. Digit runs compare numerically without parsing them into an
  integer (no overflow on long runs), other text compares case-insensitively, and equal names fall back to
  the exact text then the row key, so the order is deterministic across devices and locales.* **`+ New folder` no longer creates a folder before you name it**: the button used to persist an
  untitled folder and then open the dialog, so cancelling left an orphan behind. It now only opens the
  dialog; *Save* validates the name (`Type a name for the folder.` when blank, dialog stays open), creates
  the folder exactly once through the single `FolderCreation` path, refreshes the library and closes the
  dialog. Cancel, dismiss and Back create nothing, and a repeated *Save* tap cannot create a second folder.* **Bookmark form: placeholder, field errors, editing and a local image**: the name field shows a gray
  sample (`ブルアカ`) that is never saved as a value; the empty-name/URL warnings now sit directly under
  their fields and the form scrolls the topmost invalid field into view (also with the keyboard open).
  Each bookmark row gains *Edit bookmark*: the same form opens prefilled and updates the existing record
  (same id, memberships and creation time). An optional image can be chosen from the system picker, is
  copied into the app's own `thumbs/` storage immediately (no storage permission), shown as a preview,
  and attached only on Save; replacing or removing it deletes the old file only after the update
  succeeded and only when no other bookmark uses it, and cancelling deletes nothing but the staged file.
* **Bookmark form fields corrected**: placeholders are now the intended samples only - folder name
  `ブルアカ`, title `みつ`, URL `https://www.dlsite.com/maniax-touch/work/=/product_id/RJ01266914.html`,
  artist `eno`, circle `榎屋`, price `¥770`, notes `先生がナギサ&サクラコとセックスします。` - and none of them is ever stored.
  Artist is its own section with a `+` button that adds independent rows (each removable; at least one row
  stays) and the values are stored in the existing `authors` list, so older single-artist records load
  unchanged. Circle is its own single field (new optional `circle` value, absent in older records = none).
  Author, Publisher and Product Identifier leave the form; the stored identifier is preserved when an
  existing bookmark is edited. The image action is a real button (choose/replace + remove) instead of
  link-styled text.
* **Bookmark form layout and validation polish**: the Artist `+` is now a circular 48 dp button with its own
  layout region beside the input (the field keeps the rest of the width and nothing overlaps), extra rows
  use the same circular remove control, and the image action is a normal `Choose image` button with a photo
  icon instead of link-styled text. The URL label reads `URL (REQUIRED)` (the validation itself is
  unchanged: https + parseable host, as before), and submitting an invalid form now reports **every**
  invalid field in one pass, each message under its own field, scrolling to the topmost one while the
  others stay visible and clear individually as they are fixed.
  The Artist `+` is fixed to the first row (it never moves to the newest row) and every additional row has
  its own `×` that removes only that value, preserving the order of the rest.
* **First-click covers, image label, prompt colours and the bookmark card actions**: a search now attaches
  its cover downloads to the cache generation **after** the results were stored, so the first click already
  fills the covers instead of needing a second one (the old token was stale the moment the new capture was
  selected); the `Choose image` button sizes to its label so the final letter is no longer clipped while
  `Remove image` is untouched; the folder-name prompt reuses the shared field colours (`Navy900` + `Line`,
  matching every other prompt) instead of its own black/accent styling; and the Wishlist bookmark card now
  carries an *Edit* icon immediately left of the red delete icon, both inside the card with independent
  actions.
* **Scan source from the saved library (Home and Search)**: the shared spreadsheet panel keeps *Choose file*
  and adds *Saved*, listing the app-managed library with display name **and** original filename; the chosen
  copy becomes the scan source through the existing `StagedWorkbook` path (no second scanner, no re-import,
  nothing written to the original file). A copy that fails to open clears the source instead of silently
  scanning the previous workbook.
* **Wishlist bookmark cards grow, grey prompt surfaces**: tags now live on their own full-width wrapping
  row, so several tags expand the card instead of running under the pen/bin buttons; *Edit* uses the folder
  card's white pen, the bookmark filter and *Select bookmarks* have a clear gap, and the shared picker behind
  *Add to wishlist* uses the prompt surface colour (`Navy900`) instead of a black overlay.
* **DLsite circle, Circle label, `Choose image` and Wishlist counters**: the DLsite parser used to store the
  shop's circle in `authors` ("the closest thing to an author a listing has"), so it surfaced as Author after
  bookmarking. The circle is now its own field end to end (`ProductSearchResult.circle` → `ProductResultUi`
  → `ItemDraft.circle` → `SavedItem.circle`), authors stay empty for shop listings (nothing is invented), the
  card line and the bookmark metadata show the circle as a circle, and editing round-trips it without moving
  it back to authors. Older bookmarks whose circle was saved as an author cannot be told apart from real
  author data, so they are left untouched (no speculative migration). The form label is now
  `Circle (Optional)`, the `Choose image` button sizes to its intrinsic width so its final glyph is never
  clipped, and the Wishlist ribbon shows `Folders (n)` / `Bookmarks (n)` like the online ribbon.
* **DLsite author, bookmark descriptions, collapsible bookmark controls**: the shop's `作者` is now read into
  `authors` **only when the page states it** (the circle is never borrowed as an author, so a listing without
  an author stays empty); bookmark cards in Wishlist gain the same chevron expansion as the search-result
  cards (left of the pen, hidden when a bookmark has no description), and the bookmark control area collapses
  behind a full-width blue handle bar — with the filter, selection and sort values preserved — so the card
  list can use the freed height.
* Version code/name unchanged (`207 / 2.3.0`): this revision is part of the same release.

### How the package is read (and what is not guessed)

* Worksheets, comments parts and VML drawings are found through **relationships**
  (`xl/_rels/workbook.xml.rels`, `xl/worksheets/_rels/sheetN.xml.rels`, `xl/drawings/_rels/*.vml.rels`),
  never by assuming `comments1.xml` or `vmlDrawing1.vml` belong to a given sheet.
* The **comments XML is authoritative** for the cell: `ref="C5"` is parsed with the same column maths as
  `CellRange` (multi-letter columns included). VML `Row`/`Column` are zero-based and only supply images;
  a shape that points at another cell is not attached to it, and the mismatch is reported as a diagnostic.
* Image references: a VML `src` (resolved relative to the drawing part) is preferred; `o:relid` / `r:id`
  are resolved through the drawing's own `.rels` when no `src` resolves. Every path is validated against
  the package; a missing part stays *unresolved* with a detail message instead of being guessed.
* Duplicate references to one media part collapse to one image; genuinely different parts stay separate.
* XML is read with a streaming SAX parser that compares **local names only**, so `v:`/`o:`/`x:` prefixes
  (or different ones, or undeclared ones in VML) all work. DOCTYPE and external entities are disabled.
* Threaded comments (`xl/threadedComments/`) are detected and reported as *not legacy notes*; they are
  never parsed as note pictures, because they do not contain them.

### Read-only and offline guarantees

* No network code on this path, no backend, no OCR, no cloud.
* `.xlsm` macro projects (`xl/vbaProject.bin`) are ignored completely: the file is never read, parsed or
  executed. Only statically saved package contents are previewed.
* The workbook is opened read-only through `ZipFile`; export writes to the user-chosen destination only.
* Malformed comments/VML, missing parts, broken relationships, corrupt images and non-workbook files all
  end in diagnostics or a single readable error, never a crash.

### Verified

* `.\gradlew.bat :app:testDebugUnitTest` — **352 tests, 0 failures** (4 opt-in live checks skipped by
  design): 34 tests from the first v2.3.0 pass (`LegacyCommentScanTest` 8, `LegacyCommentEdgeCaseTest` 14,
  `CommentImageCacheTest` 8, `WorkbookCommentsUiTest` 4), plus this revision's `WorkbookLibraryTest` (9),
  `CatalogueImageIndexTest` (12), `WorkbookAnalysisTest` (1) and a per-sheet summary test in `WorkbookCommentsUiTest` (5 in that suite).
* `.\gradlew.bat :app:assembleDebug` — BUILD SUCCESSFUL (APK reports 207 / 2.3.0).
* Parsing was exercised on synthetic packages built in the tests (real ZIP parts: workbook, worksheet
  rels, comments XML, VML, VML rels, media bytes), not on mocks and not on a bundled third-party file.

### Known limitations

* **The two-workbook comparison overview is not implemented** in this build. Reusing the parsed model
  made a metadata comparison look cheap, but doing it honestly (per-sheet dimensions, ordering,
  visibility, comment/media/coverage counts for both sides) needs its own screen and its own test pass;
  it is deferred rather than shipped half-verified.
* **No device or emulator was available**: the new screens compile and their state/logic is unit-tested,
  but tapping through them on hardware is still to be done. The project has no Compose UI-test dependency,
  so there are no instrumentation tests.
* The catalogue tab detects a table by structure (a header row with ≥3 short text cells and rows under it)
  and uses the workbook's own header names. A workbook whose catalogue has fewer than three columns, or
  whose header is not near the top, is reported as "no catalogue table" instead of being guessed.
  Image ↔ record association uses the **same worksheet row** as a verified legacy comment.
* The All Images grid/list render every visible row through `LazyColumn`/`LazyVerticalGrid`, which is
  fine for the hundreds of media parts a workbook normally has; a workbook with tens of thousands would
  need paging.
* **No thumbnail-size control and no sort by pixel dimensions**: image width/height are only measured when
  an image is decoded (thumbnail or viewer), never stored, so they cannot order the list yet.
* **The parsed analysis (catalogue tables, image inventory) is recomputed on each open** from the cached
  comment/media scan; only the scan itself is cached. A large catalogue therefore costs a few extra
  seconds when reopening, not a stale screen.
* An image that fails to decode shows a placeholder in the grid/list, but the inventory has no separate
  "decode failed" state: the failure is visible, not counted.
* **Threaded comments are not shown** (they are reported as present, nothing more). Only legacy VML notes
  carry pictures.
* The media list is capped at 1500 files and each image at 32 MB; when a cap is hit the screen says so.
* Associations come from legacy note shapes; a picture anchored elsewhere in a drawing (a floating image)
  is listed as media but is not associated with a cell by this previewer.
* Only `.xlsx`/`.xlsm` are supported. Encrypted/password-protected workbooks cannot be opened and report
  a readable error.
