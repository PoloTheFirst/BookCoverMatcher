# BookCoverMatcher

**Version 2.5.0-dev.5** (development; versionCode 220. The 2.4.0 stable release is versionCode 215) · Android 8.0+ (API 26) · Kotlin + Jetpack Compose · CameraX · ONNX Runtime (OpenCLIP) · Chaquopy / chrome-lens-py · native PaddleOCR (PP-OCRv6 Medium, models bundled in the APK) in `native/ocr`

Find a book cover inside an Excel workbook, save the comparison as a session, discover the same
title in online catalogues, and keep bookmarks that survive restarts. Offline matching never needs
a connection; every online feature is opt-in.

> This release is a corrective implementation. It restores the v2.1.0 offline workflow, replaces the
> broken "folder of five items" model with **folder → session → results**, fixes the online-search
> state bug that made the feature unreachable, and adds the bottom navigation the workflow called
> for. The UI follows the supplied workflow image, which lives outside the repository
> (`.../Downloads/workflow_image.png`): the Search screen has that image's `Workbook (n) / Online (n)`
> result tabs, a `Bookmarks` tab with `Bookmark all platform results`, and the `Add entire result profile to
> wishlist` button. Where the picture shows data this stack cannot supply (`Visual match`, price,
> availability, marketplace sources) the field stays in the layout with an honest empty state instead
> of an invented value. **2.2.1** corrected the provider layer. **2.2.2** adds the platform picker:
> tap a card to include or exclude that platform, `Open ↗` on every card opens its own search page,
> and the online result maximum is now chosen (5–50) instead of fixed at five. Open Library, Google
> Books and DLsite are searched in the app; Melonbooks, Suruga-ya, Doujin no Mori, E-Hentai,
> Doujinshi.info, MyAnimeList and nhentai are browser-only shortcuts with a card and a link, never
> part of the in-app selection - see section 6. **2.2.3** fixes multi-word DLsite queries (its keyword
> path needs `+`, not `%20`), lets result profiles be renamed like folders, and lets a result inside a
> profile be bookmarked without leaving the profile. **2.2.4** adds **Toranoana** as an eighth
> browser-only card (no documented public API; 18+ catalogue) and makes every card's `Open ↗` link
> follow the query field immediately, so a browser-only shop can be opened without running a dummy
> in-app search first. **2.3.0** adds an offline preview of the workbook's legacy Excel comments: note
> text, author, the pictures stored with each note, every file under `xl/media`, a full-screen viewer and
> byte-for-byte export, all read-only. **2.3.1** is a fix patch: the Wishlist `Bookmarks` list now draws
> the Search result card, its control drawer has a transparent handle and animates, a tab switch keeps
> the list state, and the bookmark form's price sample reads `770円`. **2.4.0-dev.1** is the first
> development build of 2.4.0: an image or a catalogue record whose source the workbook proves can be
> opened at its worksheet cell, with the surrounding cells shown and every unproven case explained
> instead of guessed (see §6). **2.4.0** is the stable release: the Insights gallery filters by worksheet
> (all, one or several), Timeline keeps Published and Acquired only, and the Catalogue table shows
> date-formatted cells as dates and percentage-formatted cells rounded to two decimals — all read from the
> cell's own number format, with the stored values untouched.
> Step-by-step usage is in `USER_GUIDE.md`; layering and invariants are in `ARCHITECTURE.md`.
---

## 1. What the app does

| Capability | What actually happens | Needs network? |
|---|---|---|
| **Offline matching** | CameraX capture (or a picked photo) → OpenCLIP embedding → cosine comparison against the pictures in the chosen workbook range → top 5 matches with cell, cosine and percent | No |
| **OCR** | The captured cover is read by the engine selected in the Online panel: **Chrome Lens** (default, the embedded `chrome-lens-py` package) or **PaddleOCR** (native PP-OCRv6 Medium in Rust, with the models packaged in the APK and extracted on first use — nothing to install). Neither engine stands in for the other: a failure is reported for the engine you chose (see `NATIVE_OCR_ANDROID.md`) | Yes |
| **Online platform search** | The OCR text (or text you type) is searched in the platforms you select. **Open Library**, **Google Books** and **DLsite** are queried in the app. **Melonbooks**, **Suruga-ya**, **Doujin no Mori**, **E-Hentai**, **Doujinshi.info**, **MyAnimeList**, **nhentai** and **Toranoana** are browser-only cards with their own link and can never be selected: Melonbooks is a product decision, Suruga-ya and Doujin no Mori disallow their search route in `robots.txt`, E-Hentai/nhentai are adult sites that block automated access, Doujinshi.info answers HTTP 403, MyAnimeList has no unauthenticated API and Toranoana publishes no documented public API (its page is an undocumented storefront, 18+). Every card has its own `Open` link, the link always uses the text currently in the query field, and the result maximum is configurable (5-50) | Yes |
| **Visual / image-similarity search** | **Not implemented.** It needs a third-party image-similarity service and credentials this app does not ship. OCR + text search is not visual matching and is never labelled as such | — |
| **Bookmarks** | Individual online results or hand-typed links, stored in the reserved `Bookmarks` folder, independent of scans, sessions and wishlist folders | No (creation) |
| **Workbook comments & images** | The legacy Excel notes of the chosen `.xlsx`/`.xlsm`: note text, author and the pictures stored with each note, plus every file under `xl/media`. Text-only, image-bearing and unresolved-image notes are marked differently; an image opens full-screen and exports byte-for-byte. Offline and read-only; `.xlsm` macros are never run | No |
| **Source location** | An image or a catalogue record opens the worksheet cell (or row) the workbook itself proves it belongs to, with the neighbouring cells shown. Unreferenced, unresolved and stale sources are explained instead of guessed | No |

The offline matcher is the original one: `Config.TOP_MATCHES = 5`, the same OpenCLIP model, the same
workbook extraction (`.xlsx`/`.xlsm`, cell-note pictures and floating pictures) and the same score
scale as v2.1.0. See §9 for the algorithm summary and §11 for the regression evidence.

---

## 2. The screen structure

Bottom navigation has four destinations:

| Tab | Contents |
|---|---|
| **Home** | Cover capture (CameraX), Auto Scan toggle, Scan / restart controls, workbook picker, cell range, image picker, "Link & Find Top 5", and a shortcut to the comparison results |
| **Search** | **Search Results** with `Workbook (n) / Online (n) / Bookmarks (n)` tabs and live counts: the offline controls plus the top-5 comparison, the online panel (OCR status, query field, **platform picker with per-platform `Open ↗` links**, **result maximum 5–50**, provider results), the reverse-image discovery section (SauceNAO first, IQDB manual, candidate cards with picture, bookmark/wishlist actions and dismissal, `Bookmark all discovery results`), and this search's bookmarked results with `Bookmark all platform results`. Offline and online results are never one mixed list |
| **Wishlist** | **Folders** and **Bookmarks** sections. A folder opens to its sessions; a session opens to its results. Bookmarks have the same cards as the Search results (expand for the full record) and a filter/sort drawer with its own handle |
| **More** | Online-search settings (toggle, auto-search, OCR/translation languages), a plain-language explanation of each capability, the **workbook comments & images** preview, storage counts and "clear online session cache" |

The wishlist is no longer a full-screen overlay and the header no longer has a star button; the
Wishlist tab owns that data. Navigation is Compose state (`AppTab`) held by the ViewModel, so a tab
switch or rotation never touches persisted data.

### Workbook comments & images (v2.3.0)

More → **Open workbook comments** shows the legacy notes of the staged workbook on their own surface, so
nothing else on screen changes while it is open.

* **Comments** lists every commented cell with a marker that does not rely on colour: `T` text only,
  `▣` has images, `!` an image reference that could not be resolved. A sheet filter narrows the list.
  Tapping a row shows the note's author, its full text (rich-text runs joined, line breaks kept), its
  thumbnails and the exact reason for any unresolved reference.
* **All images** lists every file under `xl/media` with its format and its associations
  (`Sheet!Cell — comment preview`), or the honest label *No association found by this previewer* for a
  file nothing in this previewer points at. Undecodable files keep a placeholder and a diagnostic.
* An image opens full-screen with its associations and closes with Back. **Export** writes the
  **original** package bytes to a destination you choose (Storage Access Framework); a thumbnail is never
  exported, and success is reported only after the copy finished.
* The result is cached per workbook hash in `files/comments/`, so reopening an unchanged workbook is
  instant and shows *cached*; **Rescan** forces a fresh parse. Replacing the workbook produces a
  different hash, so stale comments can never be shown.
* Read-only: the workbook is opened through `ZipSource`, never written to; `.xlsm` macro projects
  (`xl/vbaProject.bin`) are ignored completely and never executed. Threaded comments are detected and
  reported, but are not legacy notes and are not shown as note images.

### Source location (v2.4.0-dev.1)

The comments preview also answers "where does this picture come from?". The answer comes from the
associations the parser already verified — a legacy note's own cell, or the worksheet row of a record the
structural catalogue reader detected — never from image order, file names or list position.

* **Entry points.** *Images* list rows and grid tiles, the full-screen image view and a comment's detail
  offer **Go to source**; a *Catalogue* record offers **Go to row**; a comment offers **Go to this cell**.
* **The view is real cells, not a label.** It reads the worksheet and shows the verified row highlighted,
  the verified cell marked, Excel's column letters, the catalogue header when the sheet is a table, and six
  rows of context either side. Selecting a cell is therefore something the screen actually does.
* **Read, drawn and tapped the same way as the table.** A date-formatted cell shows its date (`2024-01-01`),
  a percentage its percentage (`12.35%`) — the *same* number-format rule the Catalogue table uses, so one cell
  cannot read two ways — and a cell the workbook links is drawn in the accent colour and underlined. Tapping
  any cell (header cells included) opens the shared **Copy / Open Link / Back** dialog for **that worksheet
  cell**: the dialog's identity is the source row and column, never the drawn position, so the thirteen-row
  window cannot make it describe a neighbour. The stored value is never replaced.
* **Proven only.** An image nothing references, a note whose image is missing, a worksheet that is no longer
  in the workbook, and a cell reference that does not parse all end in a readable explanation. A note that
  states several locations lists all of them and the user chooses; the app never picks silently.
* **Identity.** A location is resolved through the worksheet's package part (`xl/worksheets/sheetN.xml`);
  the display name is matched exactly as the workbook spells it, so spaces, punctuation, Unicode and
  trailing spaces stay intact.
* **State.** The source view is an overlay: the section, worksheet filter, image search, association and
  catalogue filters, sort order and selection are untouched, and closing it returns to the same list. Cells
  read for one location are reused for the next in the same sheet; a read that finishes after switching
  workbooks is discarded.

### Workbook insights (v2.4.0-dev.2)

A fourth tab, **Insights**, checks and browses the records of the open workbook. It is not a new screen:
the workbook, the parsed analysis and the verified image associations are the ones the preview already
has, and the five sub-tabs read **one** canonical catalog.

* **Health.** Worksheets that could not be read, records with no title, missing optional values, URLs and
  dates that cannot be read, duplicate product IDs, rows that look duplicated, note references that point
  at an image the package does not contain, media nothing points at, and records without a verified cover.
  Each finding names the worksheet row or image, states the evidence, suggests a next step, and is either a
  confirmed problem or information. Filterable by severity and category; nothing is repaired.
* **Gallery.** One card per record with its cover, title, Circle, Artist, product ID and source row;
  filters for cover state, missing title, missing metadata and free text; sorting by title, Circle, Artist
  or source order. A record without a cover shows a placeholder, never a padded card.
* **Analytics.** Records, worksheets, verified covers, missing metadata, duplicate IDs and possible
  duplicates, grouped by Circle, Artist, tag, worksheet and publication year. The rules are on the screen:
  one record is one source row, a blank cell is *missing* not zero, and potential duplicates are reported
  rather than subtracted.
* **Timeline.** Publication year groups, newest or oldest first, with a separate *No date known* group.
  Since v2.4.0-dev.3 **you choose** the column each timeline reads (see below), and v2.4.0 keeps the two
  meanings *Published* and *Acquired* only — the rarely used *Imported* mode was removed.
* **Fields.** Maps a worksheet's own headers to the app's fields, previews the records before applying,
  reports headers that are missing, duplicated or shared, and saves the configuration as **header names** (not
  column positions) in `files/mappings/profiles.json`. A profile holds one mapping per worksheet, and the
  **Detected** profile the screen starts on is computed from the workbook, never stored.

Source navigation from v2.4.0-dev.1 is reused rather than re-implemented: a gallery card, a timeline entry
and a finding with a record or image all open the verified worksheet row through the same overlay.

### Every worksheet column, and dates you choose (v2.4.0-dev.3)

* **No column is cut off.** The worksheet used to be drawn as the sixteen columns nearest the verified
  cell, so a wide sheet (the sample `LIST` has 24: A–X) silently ended at P and `ツイッター`,
  `ツイッター (ユーザー名)`, `ミスキー` and `ミスキー (ユーザー名)` could not be reached. Every column the
  worksheet uses is now drawn, the header scrolls together with its own values, and a sheet wider than the
  app's hard bound says how many columns it did not draw instead of pretending it ended.
* **A blank header is still a column.** The reader used to take its column list from the *named* header
  cells only, so a blank header cell silently shifted every value to its right one column to the left. The
  columns between the first and last header are kept, and one with no header is labelled by its letter
  (`Column C`) rather than printed as an empty name.
* **Cards or the whole worksheet.** The Catalogue tab has a **Cards / Table** switch. *Table* draws the
  worksheet itself — every column, Excel's letter above each header, the row number frozen on the left,
  one horizontal scroll shared by the header row and every data row — and lazy rows, so a 5 000-record
  sheet renders only what is on screen. Tapping a row number opens that row in the source view.
* **Published and Acquired columns, chosen by you.** *Insights → Timeline → Choose date columns…* assigns
  two independent columns, per worksheet: the one that holds the publication date and the one that holds
  the acquisition date. The dialog lists the worksheet's own columns with their letters, previews a few
  real values (`43465 → 2018-12-31`), counts how many of them can be read, and refuses to read a
  duplicated or vanished header. Nothing is assigned from a header name: `出版日付` is not picked because it
  *looks* like a date, and a workbook with no assignment shows a setup state instead of an empty timeline.
  The choice is stored per workbook **and** worksheet (`files/mappings/dates.json`, header names, not
  positions), so it survives a restart, a rescan and a different mapping profile, and cannot leak into
  another workbook.
* **Excel date serials are read where you say the column holds dates.** A bare number is never a date on
  its own; in the one column you chose it is an Excel serial (1927 onwards), so a workbook that stores
  `43465` instead of `2018/12/31` still gets a real timeline. A four-digit value stays a year.
* **The four section labels are readable in full.** *Comments / Images / Catalogue / Insights* wrap onto
  a second line inside their own tab at the same font size, instead of being truncated to `Catalogue (5…)`.
* **The gallery starts at the top of a new order.** Changing the sort, the direction or a filter moves the
  Insights grid back to the first card of the new order; opening a card and coming back, or a thumbnail
  finishing its decode, leaves the scroll exactly where it was.

### Timeline filters, worksheet scopes and a stable directory (v2.4.0-dev.4)

* **The directory order is Health, Analytics, Gallery, Timeline, Fields.** The two screens that analyse the
  collection sit together, the two that browse individual records sit together, and Fields stays last because
  it configures the data the others read.
* **Each directory entry is two lines: the name, then its count.** The count used to be part of the name
  (`Insights (3)`) and disappeared at zero and while a report was being built. Now the count is its own line
  and always exists — `0 errors`, `2 errors`, `reading…` while a build runs, `—` when no report exists yet —
  so opening or reloading a workbook cannot make the entry look count-less.
* **The timeline can be searched and narrowed to a year range.** *Timeline → Search* matches the record's own
  title, circle, artist, product ID and worksheet row; *Years: …* sets a from/to range using only years the
  timeline actually has, and **Clear filters** restores the whole eligible set. The count line reads *x of y
  records*, and the two date meanings keep separate filters — a search typed for Published does not narrow
  Acquired. A record whose own date is unknown is **not** given a year to fit the range: it is excluded and
  counted in an explicit line.
* **Switching Published ↔ Acquired starts at the top.** The list owns its scroll state and resets it when the
  meaning (or the ordering, or the filter) changes — never for an unrelated recomposition.
* **Health and Analytics can be limited to any set of worksheets.** A single bar reports the current scope
  (*All worksheets*, *LIST*, *2 of 3 worksheets*) and opens a picker of the workbook's own worksheets with
  their record counts. The scope changes the **calculation**: the report and the analytics are recomputed from
  the canonical catalog for exactly those records, so the tiles, the severity/category counts, the duplicate
  groups, distributions, the year breakdown and the cover totals all come from the same scoped list. The two
  screens keep independent scopes, a selection that no longer matches anything falls back to *All worksheets*,
  and another workbook starts from *All worksheets*.
* **The gallery's search bar and filters are separated.** The bar has its own margins and a hairline in the
  palette's line colour above the chip rows; no font size changed and no filter behaves differently.

### QA remediation: a silent truncation and a too-narrow default range (v2.4.0-rc.7)

* **A catalogue that stops at an empty row now says so.** The reader ends a table at the first all-empty
  row — deliberately, because skipping blank rows could merge two unrelated blocks that share one sheet —
  so a sheet with data below that row was reported with fewer records than it holds and nothing explained
  the difference (`layout_blank_rows.xlsx`: 8 comments, **3** records, no warning). The rows below the
  blank row that carry data in the table's own columns are now counted, and the count is shown in
  *Catalogue* under the record total and again under each worksheet's header in the Table view
  ("5 rows below empty row 5 were not read — a blank row ends a table"), and as a warning in
  *Insights → Health* that names the row numbers and how to fix the sheet. The stop rule, every fixture's
  record count and every worksheet mapping are unchanged.
* **The default range covers the layout the app documents.** A fresh install started on `A1:B20` while
  the doujin catalogue keeps its 24 columns in `A:X` with the covers in the title column `D`, so a first
  run against a real workbook found no images at all. The default is now `A1:Z5000` — planning an
  extraction reads the workbook's drawing and comment anchors, so a wide range costs nothing per empty
  cell — and the empty-result message names the range and says where covers usually sit. A range the user
  has already typed is never overwritten.

### Gallery worksheet filter, dates and percentages (v2.4.0)

* **The gallery can be limited to any set of worksheets.** *Insights → Gallery* now starts its filter area
  with **Worksheets: All worksheets / LIST / 2 of 3 worksheets**, which opens the same picker the Health and
  Analytics reports use: all worksheets, one, or any combination, each with its record count, and
  **Use these worksheets** to commit. The selection decides which records the grid even holds, so the search,
  *With cover*, *No cover*, *Missing title*, *Missing metadata*, every sort order, the count line and the
  scroll reset all compose with it — picking one worksheet and *No cover* shows exactly that worksheet's
  coverless records. A worksheet is identified by its place in the package, not by its display name, so
  renaming it in Excel keeps the selection, a worksheet that has since been deleted is dropped instead of
  emptying the grid, and another workbook always starts from *All worksheets*.
* **Every selection is remembered per workbook.** The worksheet scope of Health, Analytics and Gallery (each
  its own) and the timeline's year range for Published and Acquired are saved in
  `files/mappings/insights-views.json`, keyed by the workbook's hash, and come back when you reopen the
  workbook or restart the app. Nothing is shared between the three screens or between two workbooks.
* **A Default order field, with a control that changes it.** *Insights → Fields* has an optional **Default
  order** row for the workbook's own ordering column (a serial number, a shelf position — your choice, any
  header, any language). It carries the same bordered **Change** button as every other field, plus **Clear**
  and **Use detected** on its own card, and its preview says how many of the values are numbers, how many are
  text and how many are blank, with examples. When two records share a Published or Acquired date the timeline
  orders them by that column, ascending and numerically (`2` before `10`), and a record without a value comes
  last instead of being given a position. The column is detected from the workbook's own headers like every
  other field, and a mapping you apply yourself — Default order included — keeps precedence over the detected
  one for **that worksheet** until you pick **Detected** or a saved profile; the Fields screen says which of
  the three is in effect, and each worksheet's mapping is remembered separately
  (`files/mappings/workbook-mappings.json`), so it is still there after a restart.
* **One mapping per worksheet, and Detected is a view you can switch away from.** Applying a mapping, saving a
  profile or picking a profile changes the worksheet on screen only: every other worksheet keeps its own mapping
  (or its own detected headers), so a profile saved while `LIST` was open cannot unmap `DATA`'s fields. The
  **Detected** chip stays blue whenever the mapping in effect equals what detection would choose — including
  right after applying an unchanged mapping. Picking **Detected** or a saved profile **switches the configuration
  the screen and the reports use**; it does not exchange, merge or rewrite either side, so Detected → profile →
  Detected → profile shows each configuration exactly as it was.
* **Saving a profile carries the other worksheets forward.** A profile is a snapshot of several worksheets, keyed
  by worksheet identity, so the header row of one worksheet is never applied to another. Saving while one
  worksheet is open records that worksheet's edits and keeps every other worksheet's mapping (the selected
  profile's, or its own detected headers) — blanks stay where they were cleared, and a worksheet the profile
  knows nothing about (one added later, or another workbook's) is read with its own detected headers.
  `files/mappings/profiles.json` is schema 2 (per worksheet; a schema-1 profile still loads as the single column
  set it was), and the view a workbook was left on travels with its per-worksheet edits in
  `files/mappings/workbook-mappings.json`.
* **Dates are shown as dates** in *Catalogue → Table* (and on the record cards). A cell formatted as a date
  used to print the serial Excel stores — `43465` — and now reads `2018-12-31`, or `2018-12-31 14:30` when the
  cell carries a time. The workbook's own date system (1900 or 1904) is used, and a value that is not a real
  date keeps its stored text instead of being shifted to a neighbouring day.
* **Percentages are rounded to two decimals.** A cell formatted as a percentage used to print its stored
  fraction (`0.123456`) and now reads `12.35%`; `0.5` is `50%`, `1` is `100%`, a tiny value is `0%`, and the
  fraction is scaled exactly once. A cell that is *not* percentage-formatted never gains a `%`.
* **Numbers are shown with at most two decimals.** One rule formats every measurement: a similarity score
  reads `0.81` (not `0.812`), and a cell whose number format declares decimals reads `1234.57`. It is a
  display rule only — nothing is rounded before it is stored, sorted, filtered or exported — and `General`
  or integer cells (identifiers, serial numbers, row numbers) keep every digit they had.
* **Cover first is gone as an order.** Cover state is still a filter (*With cover* / *No cover*), driven by
  the verified image association; the four orders that remain describe the workbook.
* **Only the cell's own format decides.** A price, a page count, a rating or a product number keeps its
  digits: `45292` is a date only inside a date-formatted cell. Text cells are never converted, and the stored
  value is never rewritten — filters, search, the canonical catalog and the export keep reading exactly what
  the workbook contains.
* **Timeline keeps Published and Acquired.** The rarely used **Imported** mode is gone, because it repeated
  every record under the app's own import date rather than a date the collection has. The saved-workbook
  library still records its import time; it is no longer a timeline.
* **Rescan refreshes everything, where you are.** The header's **Rescan** button re-reads the workbook and now
  rebuilds Health, Analytics, Gallery, Timeline and Fields immediately; the reports used to come back only
  after leaving the Insights directory and returning. The profiles, worksheet scopes, filters and chosen date
  columns survive it, the panels show a reading state while it runs, and a failed scan says why instead of
  leaving them empty.
* **No global Sheets button.** The worksheet picker is opened from the section that filters by worksheet
  (Comments, Images, Catalogue); the one shared header button was removed, so nothing implies a filter that
  applies to directories that keep their own scope.
* **A cell dialog with Copy and Open Link.** Tapping a cell in *Catalogue → Table* opens a centred dialog with
  the cell's value and three stacked actions: **Copy** (the displayed text), **Open Link** (only when the
  workbook stores a link the app can follow) and **Back**; a tap outside the card also closes it. Linked cells
  are drawn in the accent colour and underlined — the workbook's own `http(s)`/`mailto` destinations go to the
  browser, an in-workbook `Sheet!Cell` location opens in the app's source view, and a cell whose text merely
  looks like a URL is not a link.

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
   **+ Wishlist** are separate actions. **Bookmark all platform results** writes every catalogue result to
   `Bookmarks` without creating a folder; **Bookmark all discovery results** (inside the reverse-image section)
   does the same for the discovery results only. **Add entire result profile to wishlist** writes the offline top-5,
   the online results and the eligible discovery results as **one session** in the folder you choose.

### Wishlist
* **Folders** → **Create folder** → name it. Open a folder to see its sessions and any directly
  saved items. Open a session to see its results.
* **Bookmarks** → **Create bookmark** for a hand-typed link, or use **Bookmark** on a result.
* The filter field, the selection entry and the sort choice (Added, Title or Domain) live in a drawer
  under the list header: the strip itself is transparent, a blue grip marks where to pull it, and the
  panel opens and closes with an animation. Filter and sort are UI state only, and they survive a tab
  switch, as do the expanded cards and the scroll position.
* Every bookmark card is the Search result card: cover, title, author line, price/identifier caption,
  source and match-type chips, and a chevron that expands the full record (description, price,
  availability, identifier, artist, circle, source and the original URL) with **Open website**,
  **★ Bookmark** and **+ Add to wishlist**. The **pencil** (edit) and the red bin sit at the end of the
  tag row, so a long tag list never runs under them.
* Every saved item card can open its original `https://` page and can be added to a folder; both
  actions appear when the card is expanded.
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
capture → AndroidImageCodec JPEG → the OCR engine selected in the Online panel
        ├── Chrome Lens: ChromeLensPyBridge (Chaquopy) → lens_bridge.py → chrome-lens-py
        └── PaddleOCR:   RustOcrTextSource → JNI → libbc_ocr.so (PP-OCRv6 Medium, ONNX Runtime)
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
| **Toranoana** | Public storefront search page (`/tora_r/ec/app/catalog/list?searchWord=`), server-rendered and parseable; `robots.txt` does **not** cover that route | **No** - undocumented HTML page, not a public API; 18+ catalogue behind its own age gate | `Open` carries the current query; the app sends no request |

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
* **Toranoana** — browser-only by decision, not by a robots rule: `robots.txt` (HTTP 200, `text/plain`,
  283 B, no redirect) lists `Disallow: /api/`, `/app/`, `/ec/app/`, `/cms/`, `/cems/`, `/log/`, `/pc/`
  and no `Crawl-delay`, and **none of those prefixes matches the storefront route**
  `/tora_r/ec/app/catalog/list`; the search page itself answers **200** with 50 server-rendered result
  blocks. The app still sends no request, because there is **no documented public API** (an undocumented
  storefront page with no stability contract, session cookies on every response and a 5.4 MB
  unpaginated answer for a blank parameter) and the catalogue is **18+** behind the site's own age
  acknowledgement, which the app keeps browser-side (the E-Hentai/nhentai policy). `Open` builds
  `?searchWord=<percent-encoded query>`; a blank query opens `https://ec.toranoana.jp/tora_r/ec/`.
* No visual-similarity provider is implemented or claimed.

> The earlier 403s recorded for Melonbooks/Suruga-ya came from a different HTTP client, not from the
> sites: with the app's own user agent the pages answer 200. What decides automated access is each
> site's published rules, as above.

### Choosing platforms

The panel shows all eleven platform cards, in `ProviderCatalog` order: three selectable in-app providers
and eight browser-only shortcuts. This is the only place a
platform is selected; no other screen keeps its own list.

* **Tap the card body** to select or deselect it. Nothing is searched until **Search selected
  platforms** is pressed (or auto-search runs after an OCR pass).
* **Selected** → blue accent border, a soft accent glow, a check mark and a `Selected`/state chip.
  The glow is a 10 dp shadow plus a 10 % accent fill: visible, not neon.
* **Unselected** → subdued surface, muted text, thin neutral border. Still fully usable: tapping it
  selects it, and its link still opens.
* **`Open ↗` on every card** opens that platform's search page for the text currently in the query
  box — rebuilt on every keystroke since v2.2.4, so no in-app search is needed first, and a search that
  finishes later cannot revert it. It is a separate click target, so it never toggles the selection and
  works whether the platform is selected or not.

### Saved workbooks, worksheet picker and catalogue browsing (v2.3.0)

* **More → Saved workbooks** imports a workbook by copying it into app storage (`files/workbooks/<id>`),
  keeping the original filename as display metadata. Rename, pin, open and remove are all library actions;
  renaming never touches the file, pinned books sort first, and removal deletes the private copy plus that
  workbook's parsed cache (the original file you imported is untouched). The storage panel reports saved
  copies and regenerable caches separately, and *Clear parsed caches* only clears caches.
* Inside the preview, **Sheets** opens a searchable worksheet picker with hidden sheets labelled, so a
  workbook with many tabs is fully reachable without swiping the strip.
* **Images** now has a search field, association filters with counts (linked / unreferenced / unresolved),
  sorting (name, size, cell, title; either direction, missing values last), grid and list modes with a
  thumbnail size (S/M/L), and
  multi-select → **Export…** into a folder you choose, with a UTF-8 `manifest.csv` beside the copies.
* **Catalogue** discovers the workbook's own table from its header row, offers value filters built from its own categorical columns, searches every column, and says
  for each record whether a verified image is linked to it. A coverage line reports records, records with
  a verified image, unresolved references and unreferenced media.
* **Source navigation (v2.4.0-dev.1)**: images and catalogue records carry a verified destination (see §2);
  a record's **Go to row** opens the worksheet at that row, and an image's **Go to source** opens the cell
  its note is attached to.
* **Insights (v2.4.0-dev.2)**: the same records drive a health report, a visual gallery, analytics, a
  publication timeline and the column mapping, all built from one catalog (see §2); a finding, a card and a
  timeline entry open the verified worksheet row through the source overlay.
* **Every column, and dates you choose (v2.4.0-dev.3)**: the Catalogue tab switches between cards and the
  whole worksheet table (all 24 columns of the sample `LIST`, one shared horizontal scroll, lazy rows); the
  Published and Acquired timelines read the two columns you assigned in *Choose date columns…*, stored per
  workbook and worksheet.
* **Selection does not change capability.** Open Library, Google Books and DLsite are queried in the
  app. The other eight cards are browser-only: they cannot be selected, and they are never counted as
  in-app providers.
* **Compare two saved workbooks** (library → *Compare* on two rows → *Compare*): a read-only,
  **metadata-level** comparison. Worksheets are matched by **name**, so a renamed sheet shows up as one
  removal plus one addition instead of being silently paired. The screen lists workbook order changes,
  per-sheet visibility (`visible` / `hidden` / `veryhidden`), each sheet's **declared** used range
  (`<dimension>`, read from the worksheet part without loading cells), legacy comment counts, verified
  image links, unresolved references, media files, unreferenced media and catalogue record/header
  differences. Zero and *unknown* are kept apart: a side that cannot be read is marked failed and its
  values stay unknown, and the screen states that matching totals do not prove two workbooks are
  equivalent. It is **not** a cell-by-cell spreadsheet diff; no macro is read and neither saved copy is
  written to.
* **Catalogue filters and worksheet scoping (v2.3.0 fixes)**: *Filter…* lists every column of the selected
  catalogue and every value of the chosen column (both searchable, nothing truncated), keyed by
  (worksheet, column) so duplicate headers such as `数` / `%` stay independent; the worksheet selector
  scrolls and reaches every sheet, and drives both the Images and the Catalogue view.
* **Scan source (v2.3.0 fixes)**: Home and the Search workbook tab keep *Choose file* and add *Saved*, which
  lists the app-managed library (display name **and** original filename, so duplicate names stay
  distinguishable); the chosen copy becomes the scan source through the existing staged-workbook path, and
  a copy that fails to open clears the source instead of silently scanning the previous workbook. Filename
  sorting is natural — `image2.jpg` before `image10.jpg` — and the association chips are a single value, so
  exactly one is highlighted and it always matches the list.
* **Wishlist bookmarks (v2.3.0 fixes)**: each bookmark card carries *Edit* (white pen, same as the folder card)
  next to the red delete icon; tags keep their own row, so the card grows instead of hiding them behind
  the actions. **v2.3.1** moved both actions to the end of that row and replaced the card body with the
  Search result card (§4).
* **Known limitations (v2.3.0)**: image pixel size is not measured, so the gallery cannot sort by
  dimensions; a corrupt image shows a placeholder without a distinct decode-failure count; catalogue
  tables and the image inventory are re-derived on each open (only the comment scan is cached); a summary
  sheet that carries a real header row (the `DATA` sheet of the reference workbook) is still listed as a
  table; if one worksheet ever contained two catalogue tables they would be shown together. Pivot-table
  sheets are excluded structurally.
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
* Eight providers are **not** searched by the app; their cards are labelled *browser only* and never
  count as answered providers: Melonbooks (product decision), Suruga-ya (`robots.txt` disallows
  `/search/`), Doujin no Mori (`robots.txt` disallows `/comics/free-search`), E-Hentai and nhentai
  (adult sites that block automated access), Doujinshi.info (HTTP 403), MyAnimeList (no
  unauthenticated API) and Toranoana (no documented public API; 18+ catalogue).

### Reverse-image discovery (v2.5.0-dev.4)

* **SauceNAO** is the first-priority provider and the only automated one: your own API key (stored encrypted with
  an Android Keystore key; shown afterwards only as `••••••••ab12`, with *Replace* and *Remove*) is sent **in the
  request body** together with the captured cover through its documented JSON API. Every state it can report -
  results, nothing found, timeout, rate limited, quota used up, blocked, unreachable, failed, needs key - is drawn as
  that state, and the row shows the provider's own remaining quota. A key this device cannot store is reported as a
  storage problem, never as a bad key. **Verified live**: a real cover returned 8 normalised results with
  `quota: 99 of 100 left` (key redacted).
* **IQDB** is the second-priority provider and is **manual-only in this build**: it publishes no API and its
  `robots.txt` disallows its own search routes (`/multi-search`, `/db-search`, `/kc-search`, `/mi-search`,
  `/index`, `/?url=`), so the app opens IQDB's page in your browser and accepts the result you paste back — the
  paste box is always visible, with no toggle. No IQDB result is ever presented as an automated search result.
* A candidate found by both providers keeps **both** numbers and both links (`SauceNAO 95.1% · IQDB 92%`). They
  are never added or averaged: two services agreeing is provenance, not a probability.
* Every candidate is a **suggestion** until you tap **Confirm** or **Reject**; the decision is remembered per
  wishlist profile, and the suggestion line is drawn in white so it cannot be missed. Confirming changes no
  workbook cell and adds no wishlist entry.
* An IQDB card carries a **rubbish bin** at its top-right that removes *that card only* from the list on screen —
  no other card, no SauceNAO result, no saved decision and no bookmark is touched. Since v2.5.0-dev.4 **every**
  provider card is dismissible, SauceNAO cards included, labelled with the provider whose evidence the tap removes.
* **Every card shows a picture when one can be obtained**: SauceNAO's own result thumbnail first, otherwise the image
  the result's page declares for itself (Open Graph, then Twitter card, then `image_src`). Retrieval is conservative
  and guarded — HTTPS only, public hosts only, every redirect validated, page/image byte caps, content-type and decode
  checks — and a page that declares nothing, refuses the request or serves something unreadable leaves the card intact
  with a `no image` placeholder. The picture is decoration: a result stays confirmable and bookmarkable without it.
* **Each card can be bookmarked or added to a wishlist** (`Bookmark`, `+ Wishlist`), exactly like a platform result:
  the chips say what is already true and repeating either action cannot create a duplicate.
* **Two bulk bookmark actions with separate scopes**: `Bookmark all platform results` (the catalogue search results)
  and `Bookmark all discovery results` (the reverse-image results of this search, SauceNAO and IQDB alike).
  `Add entire result profile to wishlist` saves the whole profile — offline top-5, platform results *and* the eligible
  discovery results — as one session.
* **Manual listings live in the Wishlist**, which owns bookmarks, manual bookmarks, deduplication and profile
  isolation. The Online tab searches and discovers; it no longer creates listings.

Follow-up work that is *not* in this build: showing the per-candidate catalogue evidence (matched / ambiguous /
absent) computed with the existing OpenCLIP pipeline, and any automated IQDB route that is genuinely permitted.


---

## 7. Build and run

**Installing the APK on a phone** — see [`INSTALL.md`](INSTALL.md): which artifact to use, the checksums for
v2.5.0-dev.5, the Android 8.0 (`minSdk 26`) floor, and why a release APK will not install over a debug build (the
two are signed with different certificates — uninstall first).

Requirements: Android Studio with SDK 35, JDK 17, a CPython **3.12** for Chaquopy, and network access
for the first Python dependency install. The build runs on **Android Gradle Plugin 9.4.1**, **Gradle
9.8.0** via the wrapper, and **Kotlin 2.2.10** (`gradle/libs.versions.toml`, `gradle-wrapper.properties`).

`local.properties`:
```properties
sdk.dir=C:\\path\\to\\Android\\Sdk
chaquopy.buildPython=C:\\path\\to\\Python\\3.12\\python.exe
```

```powershell
.\gradlew.bat assembleDebug                     # fetches + SHA-256-verifies the PP-OCRv6 models first
.\gradlew.bat testDebugUnitTest                 # JVM suite, live checks skipped
.\gradlew.bat testDebugUnitTest -DonlineCheck=1 # adds the four live provider checks
.\gradlew.bat connectedDebugAndroidTest         # on-device OCR routing, native engine, catalogue and controller tests
powershell -ExecutionPolicy Bypass -File tools\verify-ocr-apk.ps1   # APK really contains the models
```

The first build downloads the two PP-OCRv6 ONNX models (~132 MB) from their pinned HuggingFace
revisions into the git-ignored `app/src/main/assets/ocr/ppocrv6/`; every build re-verifies size and
SHA-256 and **fails** if a file is missing or different, so an APK that cannot run PaddleOCR is never
produced. Verified copies are reused, so only the first build (or a clean CI checkout) needs network.
The models are packaged uncompressed, and on first use the app copies them into private storage with
the same hashes checked, which is why a clean installation needs no ADB, script or download.

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
├── MainActivity.kt, MainViewModel.kt, UiState.kt, OnlineSearchUi.kt,
│   WorkbookCommentsUi.kt, WorkbookCommentsController.kt, WorkbookComparisonUi.kt
├── core/                         plain Kotlin/JVM, unit-tested
│   ├── excel/                    workbook and picture extraction;
│   │                             comments/ = legacy note text, VML images, media index,
│   │                             worksheet dimensions + WorkbookComparison.kt, and the source
│   │                             navigation model (SourceLocation.kt, SheetWindow.kt)
│   ├── search/                   OpenCLIP index, ranking, cache
│   ├── vision/                   image ops, preprocessing, interfaces
│   ├── autoscan/                 auto-scan engine
│   ├── catalog/                  one WorkbookCatalog behind the five Insights screens:
│   │                             fields, dates (incl. Excel serials), column mapping +
│   │                             codec, health report, analytics, timeline, gallery view,
│   │                             TimelineDates (the date columns the user chose), and the
│   │                             v2.4.0 SheetScope (what a report and the gallery cover),
│   │                             TimelineFilter (what the timeline shows) and the
│   │                             gallery's own worksheet selection
│   ├── wishlist/                 LibraryModels (folders/sessions/items), repository,
│   │                             MiniJson, identity, bookmark rules and
│   │                             BookmarkPresentation (the card's own lines)
│   └── online/                   OcrQueryExtractor, GoogleBooksResponseParser,
│                                 OpenLibraryResponseParser, DlsiteResponseParser,
│                                 MelonbooksResponseParser, HtmlText, ProviderCatalog,
│                                 ProviderLinks, SearchRouting, SearchLimits,
│                                 OnlineAggregator, SessionCache
├── data/                         Prefs, OnlineSearchPrefs, ModelStore, WorkbookFiles,
│                                 CommentImageCache, ColumnMappingStore, TimelineDateStore,
│                                 InsightsViewStore, WorkbookMappingStore
├── vision/                       AndroidImageCodec, OrtClipEmbedder
├── camera/                       CameraController (CameraX)
├── online/                       OnlineSearchController, ChromeLensPyBridge, PythonRuntime,
│                                 OcrTextSource/Parser, OpenLibraryBooksSource,
│                                 GoogleBooksSource, MelonbooksSource, DlsiteSource,
│                                 HttpJsonClient, CoverImageLoader
└── ui/                           AppScreen (bottom nav), MainScreen (Home/Search),
                                  WishlistScreen, MoreScreen, OnlineSearchPanel, PlatformPicker,
                                  WorkbookCommentsScreen, WorkbookLibraryScreen, WorkbookComparisonScreen,
                                  ProductResultCard, ResultCardParts (shared by both result cards),
                                  BookmarkDialogs,
                                  Overlays, CameraCard, components/, theme/
app/src/main/python/lens_bridge.py   the only Python; wraps chrome-lens-py
app/src/test/                        658 JVM tests (7 opt-in: 4 live provider checks and the
                                     3 real-workbook tests whose 40 MB files are not committed)
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

> This section records the 2.2.x corrections. Later releases live in `CHANGELOG.md`: **2.2.4** added the
> Toranoana browser-only card and made every card link follow the query field; **2.3.0** added the
> offline legacy-comment image preview; **2.3.1** is a fix patch for the Wishlist bookmark card, its
> control drawer and the bookmark form's price sample.

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
| JVM unit tests | `.\gradlew.bat testDebugUnitTest` | **329 tests**, 0 failures (4 live checks skipped by design) |
| JVM + live providers | `.\gradlew.bat testDebugUnitTest -DonlineCheck=1` | **329 tests**, 0 failures; all **four** live checks ran: Open Library and **DLsite** returned real records, Google Books was found or correctly `RATE_LIMITED`. Melonbooks left the live suite with its registration, so the run makes no request to it |
| DLsite live query | `DlsiteSource().search("東方", 3)` inside the live suite | **real works returned**: `RJ…` ids, Japanese titles, circle names, prices (`924円`-style), `img.dlsite.jp` covers, `dlsite.com` product URLs |
| Provider access probe | `GET robots.txt` and the search URL with the app's own UA (2026-10-23) | Melonbooks: robots **404** (no rules), search **200**; Suruga-ya: robots **200** with `Disallow: /search/`; DLsite: robots **200**, paged URLs disallowed |
| Compose UI tests | none | **Not run**: the project has no `compose.ui:ui-test-junit4` dependency and no emulator/device was attached; platform selection, link independence, the count cap and the batch repository operations are covered by `SearchRoutingTest`, `ProviderLinksTest`, `OnlineResultLimitTest` and `BatchOperationsTest` |
| Real workbook (v2.3.0) | `.\gradlew.bat :app:testDebugUnitTest --tests "*RealWorkbookIntegrationTest"` | **3 tests**, 0 failures, run through the production parser on `test_excel/DoujinNew.xlsx` / `DoujinOld.xlsx` (skipped when the files are absent): 999 comments, 165/139 media, 124/103 verified links, 41/36 unreferenced, 24-field `LIST` with 124 records, 8/7 sheets; the version comparison detects `C109`, the hidden pivot sheet, the widened `LIST` range and media 139 → 165 |
| Full JVM suite (v2.3.0) | `.\gradlew.bat :app:testDebugUnitTest` | **449 tests**, 0 failures, 4 skipped by design (`assembleDebug`: BUILD SUCCESSFUL, version 207 / 2.3.0) |
| Full JVM suite (v2.3.1) | `.\gradlew.bat :app:testDebugUnitTest` | **470 tests**, 0 failures, 4 skipped by design, across 62 result classes (`BookmarkPresentationTest` 9, `BookmarkFormTest` 22); `assembleDebug`: BUILD SUCCESSFUL, `app-debug.apk` 194,768,801 bytes, version 208 / 2.3.1 |
| Full JVM suite (v2.4.0-dev.1) | `.\gradlew.bat :app:testDebugUnitTest` | **487 tests**, 0 failures, 4 skipped by design, across 63 result classes; the new `SourceNavigationTest` (17 tests) covers verified image/comment/record locations, ambiguous notes, unreferenced and unresolved images, stale worksheets, trailing-space/Unicode sheet names, the worksheet window and the overlay state; `assembleDebug`: BUILD SUCCESSFUL, version 209 / 2.4.0-dev.1 |
| Full JVM suite (v2.4.0-dev.2) | `.\gradlew.bat :app:testDebugUnitTest` | **574 tests**, 0 failures, 4 skipped by design, across 71 result classes; 87 of them new: `ColumnMappingTest` 14, `ColumnMappingStoreTest` 5, `WorkbookCatalogTest` 9, `HealthReportTest` 18, `CollectionAnalyticsTest` 9, `CollectionTimelineTest` 12, `GalleryViewTest` 10, `WorkbookInsightsUiTest` 10; `assembleDebug`: BUILD SUCCESSFUL, `app-debug.apk` 195,015,505 bytes, version 210 / 2.4.0-dev.2 |
| Full JVM suite (v2.4.0-dev.4) | `.\gradlew.bat :app:testDebugUnitTest` | **658 tests**, 0 failures, 7 skipped by design, across 81 result classes; 37 of them new: `TimelineFilterTest` 10, `SheetScopeTest` 5, `ScopedReportsTest` 8, `InsightsFilterUiTest` 11, `InsightsOrderTest` 3; `assembleDebug`: BUILD SUCCESSFUL, version 212 / 2.4.0-dev.4 |
| Full JVM suite (v2.4.0) | `.\gradlew.bat :app:testDebugUnitTest` | **781 tests**, 0 failures, 7 skipped by design, across 94 result classes; 64 of them new: `GallerySheetFilterTest` 11, `CatalogueNumberFormatTest` 11, `DefaultOrderTest` 10, `InsightsViewPersistenceTest` 8, `SheetLinksTest` 7, `CellPeekAndMappingTest` 5, `CatalogueFormattingWorkbookTest` 4, `RescanStateTest` 4, the real-workbook date/order/link checks 3, the 1904 case 1, minus one test for the removed import timeline; plus `SourceWindowFormattingTest` 14 and `MappingPersistenceTest` 13 for the source-preview and persistence corrections; plus `DetectedStatusTest` 14, `WorksheetMappingIsolationTest` 11 and `ProfileViewSwitchingTest` 7 for the mapping and profile-view fixes; `assembleDebug`: BUILD SUCCESSFUL, `app-debug.apk` 195,929,512 bytes, version 213 / 2.4.0 |
| Full JVM suite (v2.4.0-rc.7) | `.\gradlew.bat :app:testDebugUnitTest` | **790 tests**, 0 failures, 0 errors, 7 skipped by design, across 96 result classes; 9 new: `CatalogueTruncationTest` 6 (the blank-row stop rule, the rows counted below it, leading blanks and out-of-table cells, and the per-worksheet health warning) and `DefaultRangeTest` 3 (the default range covers column `D`/`X` and 5000 rows, a fresh `UiState` starts on it, and `A1:B20` would have missed `D`); `assembleDebug`: BUILD SUCCESSFUL, `app-debug.apk` 195,194,901 bytes, version 214 / 2.4.0-rc.7 |
| **Stable release (v2.4.0)** | `.\gradlew.bat assembleDebug` + `.\gradlew.bat :app:testDebugUnitTest` on `master` | BUILD SUCCESSFUL; **790 tests, 0 failures, 0 errors, 7 skipped**, 96 result classes — the same suite as `2.4.0-rc.7`, only the version changed (`215 / 2.4.0`); annotated tag `v2.4.0` on the release commit |
| Emulator (v2.4.0-rc.7) | Pixel 8 AVD `emulator-5554`, Android 17 / API 37, `adb install -r`, app data cleared | Fresh install shows range `A1:Z5000`; `layout_blank_rows.xlsx` staged (`34 KB`) and read as **3 records with the warning** "LIST: 5 rows below empty row 5 not read" in *Catalogue*, the same note under the sheet header in Table view, and the health finding "Rows below an empty row were not read" naming rows 5–10; 0 fatal exceptions |
| Full JVM suite (v2.4.0-dev.3) | `.\gradlew.bat :app:testDebugUnitTest` | **621 tests**, 0 failures, 7 skipped by design, across 76 result classes; 47 of them new: `WorksheetColumnsTest` 7, `TimelineDatesTest` 17, `TimelineDateUiTest` 9, `GalleryScrollTest` 6, `SampleWorkbookColumnsTest` 5 and 3 more timeline cases; `assembleDebug`: BUILD SUCCESSFUL, `app-debug.apk` 195,064,657 bytes, version 211 / 2.4.0-dev.3 |
| Sample workbook (v2.4.0) | `.\gradlew.bat :app:testDebugUnitTest --tests "*SampleWorkbookColumnsTest"` | **9 tests**, 0 failures, run through the production reader on `test_excel/doujin.xlsx` (skipped when absent): `LIST` reads as **24 columns A–X** with 24 values on every record, `ツイッター`/`ツイッター (ユーザー名)`/`ミスキー`/`ミスキー (ユーザー名)` resolve to S/T/U/V, `出版日付` (G) and `購入日` (R) are readable date columns (serial numbers), `通し番号` is the detected Default order and really breaks a date tie, the workbook's stored hyperlinks keep their destinations apart from their text, every column of a record reads identically in the Catalogue table and in the source window, the source window draws all 24 columns, and the worksheet inventory is unchanged |
| Debug build | `.\\gradlew.bat assembleDebug` | **BUILD SUCCESSFUL** |
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
* The v2.3.1 Wishlist `Bookmarks` drawer (its handle and animation) and the rebuilt bookmark card: no
  device or emulator was available and the project has no Compose UI-test dependency, so they were not
  driven through the UI. Their shared text rules are unit-tested (`BookmarkPresentationTest`) and both
  builds pass.
* The v2.4.0-dev.1 source view — its buttons, the highlight and the scrolling — and the worksheet window:
  no device or emulator was available and there is still no Compose UI-test dependency, so the screens
  were not tapped through. The mapping, the window model and the overlay state are unit-tested
  (`SourceNavigationTest`, 17 tests) and both builds pass.
* The v2.4.0 final round — the source preview's tappable cells and their blue link styling, the cell dialog's
  **Copy**/**Open Link** (they need an Android clipboard and intent) and the Fields **Change**/**Clear**/
  **Use detected** buttons: compiled, and their state and model are unit-tested
  (`SourceWindowFormattingTest` 14, `MappingPersistenceTest` 11), but no button was tapped on a device.
* The mapping state itself — the Detected chip, the profile-view switch (Detected ↔ a saved profile, and between
  two profiles), applying and saving a profile per worksheet, profile inheritance, the per-worksheet
  persistence and both migrations — is covered by `DetectedStatusTest`, `WorksheetMappingIsolationTest` and
  `ProfileViewSwitchingTest` at the state level; no Compose UI test asserts the chip's colour on screen, and the
  corner case where a selected profile means exactly detection (both the Detected chip and the profile chip
  highlighted) was reasoned about rather than seen.
* The v2.4.0-dev.2 Insights screens — Health, Gallery, Analytics, Timeline and Fields, their chips, the
  header picker and the save-profile dialog: compiled and unit-tested (`HealthReportTest`,
  `GalleryViewTest`, `WorkbookInsightsUiTest` and the other catalog tests, 87 in all), not tapped through
  on a device. The report and the analytics only see what the parser can see, so a drawing-anchored picture
  makes its record look cover-less rather than broken.
* The v2.4.0-dev.3 surface — the Cards/Table switch and the worksheet table's shared horizontal scroll, the
  two-line section labels, the *Choose date columns* dialog and the gallery's scroll reset: compiled and
  covered by state tests (`WorksheetColumnsTest`, `TimelineDatesTest`, `TimelineDateUiTest`,
  `GalleryScrollTest`, `SampleWorkbookColumnsTest`, 47 tests in all), but not tapped through on a device or
  measured at a specific screen width. The clipping fix and the scroll reset are layout behaviour, so they
  are reasoned and built, not photographed.
* The v2.4.0-dev.4 surface — the two-line directory entries and their count line, the gallery divider, the
  timeline search/year-range bar, the mode-switch scroll reset and the worksheet-scope picker: compiled and
  covered by state and calculation tests (`TimelineFilterTest`, `SheetScopeTest`, `ScopedReportsTest`,
  `InsightsFilterUiTest`, `InsightsOrderTest`, 37 tests in all), but not tapped through on a device. The
  scroll reset, the two-line layout and the divider are layout behaviour, so they are reasoned and built,
  not photographed.
* The v2.4.0 surface — the gallery's worksheet bar and its picker, the persisted selections, the Default
  order row, the formatted catalogue cells, the cell dialog with its clipboard/link actions, the rescan
  rebuild and the two-mode timeline: compiled and covered by `GallerySheetFilterTest` (11),
  `CatalogueNumberFormatTest` (11), `DefaultOrderTest` (10), `InsightsViewPersistenceTest` (8),
  `SheetLinksTest` (7), `CellPeekAndMappingTest` (5), `RescanStateTest` (4), `CatalogueFormattingWorkbookTest`
  (4) and the updated timeline/date tests, but not tapped through on a device: the dialog's clipboard and
  browser calls need an Android runtime, so they are reasoned and compiled rather than unit-tested. The cell
  widths of the table are unchanged, so a long date is ellipsised like any other value.
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
* The source view reads the staged workbook copy on the device only. Cell values, worksheet names and
  locations are never uploaded, cached in a server or logged.
* Bookmarks store only the URL/title/optional fields you provide; the app never fetches the page to
  enrich them.
* The app refuses non-HTTPS links and never renders third-party pages in a WebView.
* Open Library data is used under its published Open Data terms; `chrome-lens-py` is MIT licensed
  and used unmodified, pinned in `app/build.gradle.kts`.
* Version 2.1.0 is the behavioural reference for offline matching; the historical diagnostic is in
  `ONLINE_COVER_RECOGNITION_DIAGNOSTIC.md`.
