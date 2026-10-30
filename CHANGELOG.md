# Read every column of a worksheet, and choose the dates yourself

**v2.4.0-dev.3 is a development build** of 2.4.0: the workbook preview now shows **every column** of a
worksheet instead of the sixteen nearest the verified cell, can draw the worksheet itself instead of only
cards, and lets **you** assign the Published and the Acquired date columns — the app never guesses one from a
header name. The four section labels are readable in full, and the Insights gallery returns to the top when
its order changes.

This is a beta. The layout and the wording may still change before 2.4.0 is released.

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
