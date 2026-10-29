# Check a workbook before trusting it, and browse it without a spreadsheet

**v2.4.0-dev.2 is a development build** of 2.4.0: the workbook preview grows an **Insights** area that checks the
workbook it already parsed, shows the records as a gallery, counts them, puts them on a timeline, and lets you
map its columns to the app's own fields. All five screens read one catalog, so a finding and a gallery card
never disagree, and nothing is written to the workbook.

This is a beta. The layout and the wording may still change before 2.4.0 is released.

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