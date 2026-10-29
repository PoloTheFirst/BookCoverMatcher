# Find the worksheet cell behind an image or a record

**v2.4.0-dev.1 is a development build** of 2.4.0: an image or a catalogue record whose source the workbook
proves can now be opened at its worksheet cell, with the neighbouring cells shown so the row can be checked
in context. Nothing is guessed, and nothing is written to your files: the workbook is still opened
read-only and no macro is ever run.

This is a beta. The layout and the wording may still change before 2.4.0 is released.

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