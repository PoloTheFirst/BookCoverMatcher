# Search a timeline, scope a report, and read a stable count

**v2.4.0-dev.4 is a development build** of 2.4.0: the Insights directory is now ordered **Health, Analytics,
Gallery, Timeline** (analysis first, browsing second), its entries show the section name above the count
instead of squeezing both onto one line, the timeline can be **searched and narrowed to a year range** and
returns to the top when you switch between Published and Acquired, the gallery's search bar no longer sits on
top of its filter chips, and the Health report and the analytics can be limited to **any set of worksheets**
with every number and breakdown recalculated for that selection.

This is a beta. The layout and the wording may still change before 2.4.0 is released.

## v2.4.0-dev.4 (2026-10-31)

### Fixed

* **The timeline kept the previous mode's scroll position.** Switching from Published to Acquired (or back)
  left the list where the other dataset had been scrolled to. The list now owns its `LazyListState` and
  returns to the first item when the meaning, the ordering or the filter actually changes — and only then,
  so a rebuilt report, a thumbnail or any other recomposition no longer moves the user.
* **The Insights count disappeared when a spreadsheet was opened.** The section entry rendered its count
  inline (`Insights (3)`) and dropped it entirely at zero or while the report was being (re)built, and one
  line could not hold the name and the count together. Each directory entry now has two lines — the name on
  top, the count underneath — and the count is never hidden: `0 errors`, `1 error`, `2 errors`, `reading…`
  while a build runs, `—` when no report exists yet. It is a separate `Text`, so opening, reloading or
  replacing a workbook cannot push it out of the entry.
* **The gallery's search bar was crowded against its filters.** The bar now has its own margins, a hairline
  in the palette's line colour separates it from the chip rows, and the chips keep their own spacing. No
  font size changed and no filter behaves differently.

### Added

* **Timeline filtering.** A text search over the record's own fields (title, circle, artist, product ID and
  the worksheet row) and a year range chosen from *Years: …* (from/to, either bound may be open, and
  `Any year` clears it). The filter bar shows how many records are shown out of how many, a **Clear filters**
  action appears only while a filter is active, an empty result says "No record matches this filter", and the
  two date meanings keep **separate** filters, so a search typed for Published cannot silently narrow
  Acquired. A date range never judges a record whose own date is unknown: those records are excluded from
  the range and counted in an explicit line, never given a year that fits.
* **Worksheet filters for Health and Analytics.** A single bar on each report — *Worksheets: All worksheets /
  LIST / 2 of 3 worksheets* — opens a picker of the workbook's own worksheets (with each one's record count
  and hidden state). All, one, or any combination can be selected, "All worksheets" is always one tap away,
  and the picker edits a draft until **Use these worksheets** is pressed.
  The scope is a filter on the **calculation**, not on the rendering: the report and the analytics are
  recomputed from the canonical catalog for exactly those worksheets, so summary tiles, error/warning/info
  counts, category chips, duplicate groups, distributions, the year breakdown and the record/cover totals all
  come from the same scoped record list. Health and Analytics each keep their **own** scope; changing one
  never changes the other. A selection stored for a worksheet that no longer exists is reconciled (the
  surviving sheets are kept, and a selection that matches nothing falls back to *All worksheets*), and a
  different workbook starts from *All worksheets*.
* **The Insights directory order** is now **Health → Analytics → Gallery → Timeline → Fields**: the two
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
* A worksheet that carries no catalogue table can be selected, but it contributes no records — the picker
  says so ("no catalogue table") instead of hiding it.
* Health and Analytics do not share a stored preference: opening the app again starts both at *All
  worksheets*, and the choice is not persisted per workbook.
* None of the new layout — the two-line directory entries, the gallery divider, the timeline filter bar, the
  worksheet picker — was driven on a device or an emulator, and no screen width was measured.

**Version code/name: 212 / 2.4.0-dev.4.**

## v2.4.0-dev.3 (2026-10-31)

### Fixed

* **Incomplete worksheet column display.** A worksheet was drawn as the sixteen columns nearest the verified
  cell, so a wide sheet (the sample `LIST` has 24, A–X) silently ended at column P: `ツイッター`,
  `ツイッター (ユーザー名)`, `ミスキー` and `ミスキー (ユーザー名)` were unreachable. The source view now draws
  every column the worksheet uses, at a hard bound far above any real catalogue, and says how many columns it
  left out when a sheet is wider than that instead of pretending it ended. The view keeps Excel's own column
  letters, so a value can still be read as a cell reference.
* **A blank header no longer shifts a column.** The reader built its column list from the header cells that
  had text, so a blank header cell between two named columns dropped that column and moved every value to its
  right one position to the left. The columns between the first and the last header are kept, a gap column is
  kept when it carries data, and one without a header is labelled by its letter (`Column C`) — never printed
  as an empty name, and never mapped to a field by guesswork.
* **Directory labels are readable in full.** The four sections of the workbook preview — *Comments, Images,
  Catalogue, Insights* — were each given a quarter of one line, so their labels were truncated to
  `Catalogue (5…`. They now wrap onto a second line at the same font size: nothing is shrunk, no card inside
  the sections changes, and the tab, its count and the selected state stay where they were.
* **The Insights gallery starts at the top of a new order.** The grid kept the old scroll offset after a sort
  or filter changed the displayed order, so the first card of the new order could be off screen. The grid now
  owns its scroll state and returns to the first item when — and only when — the ordering or the filtered
  dataset actually changes. A thumbnail finishing its decode, a dialog opening or a report being rebuilt
  leaves the scroll untouched.

### Added

* **Cards or the whole worksheet.** The Catalogue tab has a **Cards / Table** switch. *Table* draws the
  worksheet: every column in the workbook's own order, Excel's letter above each header, the row number frozen
  on the left, one horizontal scroll shared by the header row and every data row so a header can never sit
  above another column's values, lazy rows for long sheets, and a row number that opens that row in the
  verified source view. Switching worksheet switches the columns to that worksheet's own structure.
* **Published and Acquired date columns, chosen by you.** *Insights → Timeline → Choose date columns…* assigns
  two independent columns per worksheet. The dialog names the workbook and the worksheet, lists the
  worksheet's own columns with their letters, previews a few real values (`43465 → 2018-12-31`), reports how
  many of them can be read and how many cells are blank, and marks a column whose header has vanished or
  become duplicated instead of reading it. Nothing is pre-selected from a header name, and a workbook with no
  assignment shows a setup state that says how to configure it. The choice is scoped to the workbook (its
  SHA-256) **and** the worksheet, stored as `files/mappings/dates.json` — header names, not positions — so it
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
* **A wide worksheet is no longer shortened by the source view** — the window's cap went from 16 columns to a
  bound no real catalogue reaches, and the number of undrawn columns is reported rather than implied.

### Verified

* 621 JVM tests, 0 failures, 7 skipped by design (4 opt-in live provider checks and the 3 real-workbook tests
  whose 40 MB files are not committed), across 76 result classes. 47 of them are new: worksheet columns 7,
  date columns 17, the date dialog state 9, the gallery scroll key 6, the sample workbook 5, and 3 more
  timeline cases.
* The supplied sample workbook `test_excel/doujin.xlsx` (40 MB, not committed) is used by
  `SampleWorkbookColumnsTest`: `LIST` reads as 24 columns A–X with 24 values on every record, the Twitter and
  Misskey columns resolve to S/T/U/V, `出版日付` (G) and `購入日` (R) are readable date columns, and the source
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
* None of the new layout — the two-line section labels, the worksheet table, the date dialog — was driven on a
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
