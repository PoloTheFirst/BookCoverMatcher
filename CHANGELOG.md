# Scope every report by worksheet, follow the workbook's own order, read dates as dates

**v2.4.0 is a stable release.** The Insights gallery can now be limited to **any set of worksheets** —
all of them, one, or several — and that selection composes with its search, cover and missing-data
filters. Every worksheet selection and every timeline year range is **remembered per workbook**, when two
records share a date the timeline now follows the workbook's own **Default order** column, the rarely used
**Imported** timeline is gone (Timeline keeps **Published** and **Acquired**), and in *Catalogue → Table* a
date-formatted cell is shown as a date (`2026-10-31`, or `2026-10-31 14:30`) with a percentage rounded to
**two decimals at most** (`12.35%`). One display rule now caps every measurement at two decimals, while the
stored values are left exactly as the workbook wrote them.

The **Source location** preview reads its cells with that same rule and is now tappable like the table — every
value, link and copy describes the **original worksheet cell**, not the position on screen — and the
**Default order** column can be changed from *Insights → Fields* with a real button and is remembered per
workbook.

## v2.4.0-rc.7 (2026-11-05)

**Version code/name: 214 / 2.4.0-rc.7.** A QA-remediation build: the two defects the previous emulator
engagement left open are fixed and re-verified on the Pixel 8 AVD. No feature was added and no parsing
rule was changed.

### Fixed

* **A catalogue that stops at an empty row now says so.** The reader ends a table at the first all-empty
  row, which is deliberate — skipping blank rows could merge two unrelated blocks that share one sheet —
  but a sheet with data below that row was reported with fewer records than it holds and nothing
  explained the difference: `layout_blank_rows.xlsx` showed 8 comments and **3** records, silently
  (QA defect XLS-01). The reader now counts the rows below the blank row **that carry data in the
  table's own columns** (`CatalogueTable.truncation`), and that fact is shown in three places: a warning
  line under the record count in *Catalogue*, the same line under the worksheet's header in the Table
  view ("5 rows below empty row 5 were not read — a blank row ends a table"), and a warning finding in
  *Insights → Health* ("Rows below an empty row were not read", with the row numbers and how to fix the
  sheet). The stop rule, every fixture's record count and every worksheet's mapping are unchanged.
* **The matcher's default range covers the layout the app documents.** A fresh install started on
  `A1:B20`, two columns wide, while the doujin catalogue keeps its 24 columns in `A:X` and its cover
  pictures in the title column `D`, so a first run against a real workbook found nothing at all (QA
  defect UI-01). The default is now `A1:Z5000` — a wide range costs nothing, because planning an
  extraction reads the workbook's own drawing and comment anchors and keeps the ones inside the range —
  and the empty-result message now names the range and says where covers usually sit, instead of only
  reporting the emptiness. A range the user has already typed is never overwritten.

### Tests

* `CatalogueTruncationTest` (6): the record count still stops at the blank row, the rows below are
  counted with their row numbers, leading blank rows and out-of-table cells are not a truncation, and the
  health finding is a per-worksheet warning that names the rows.
* `DefaultRangeTest` (3): the default range parses, contains the documented title column `D` and column
  `X`, spans 5000 rows, is what a fresh `UiState` starts on, and the old `A1:B20` would have missed
  column `D`.
* Full suite: **790 tests, 0 failures, 0 errors, 7 skipped** across 96 result classes.

### Verified on the emulator

Pixel 8 AVD (`emulator-5554`, Android 17 / API 37, `sdk_gphone64_x86_64`), debug APK installed with
`adb install -r`, app data cleared first so a **fresh install** was tested:

| Check | Result |
|---|---|
| Fresh install's range field | `A1:Z5000` |
| `layout_blank_rows.xlsx` import | staged `layout_blank_rows.xlsx · 34 KB` |
| Catalogue record count (unchanged) | `3 record(s) shown · all worksheets` |
| Catalogue warning | `LIST: 5 rows below empty row 5 not read` |
| Table view note | `5 rows below empty row 5 were not read — a blank row ends a table` |
| Health finding | "Rows below an empty row were not read": the catalogue stops at row 5, 5 rows from row 6 are not counted, delete the empty row and rescan |
| Fatal exceptions during the run | 0 |

### Known limitations

* A sheet whose records continue after an empty row is still read as one table up to that row: the app
  reports what it skipped instead of guessing that the rows below belong to the same catalogue.
* The "no images found" wording is verified by inspection; the full scan-and-find path was not re-driven
  end to end in this round (it needs a camera capture or an image upload first).
* Camera content scenarios (angled, low light, blurry, obstructed, several books) remain blocked on the
  emulator: its virtual scene cannot be posed over adb, so those were neither tested nor claimed.


## v2.5.0-dev.1 (2026-11-05)

**Version code/name: 216 / 2.5.0-dev.1.** First development build of the online-discovery refinement. It
delivers the audited **core** of the feature; the screens that would consume it are not wired yet, so
nothing user-visible changes in this build (see *Known limitations*).

### Added

* **A structured search query.** `OnlineQuery` splits what the user types into title, circle, author and
  product code — `title:`/`circle:`/`author:`/`code:` prefixes, and a bare `RJ01234567` (or `RJ 01234567`,
  `RJ-01234567`) recognised wherever it appears — and keeps the original text untouched for display and
  diagnostics. Full-width Latin and the ideographic space fold to half-width; kana, kanji and long-vowel
  marks are never rewritten. Identifiers lose only spaces, hyphens and case, and only for comparison.
* **Declared provider capabilities.** `ProviderCapabilities` records what each in-app provider actually
  searches — Open Library and Google Books: title, author, ISBN; DLsite: title, circle, RJ code — and
  reports the parts of a query it must ignore. An author-only search is therefore not sent to DLsite's
  keyword box, and a circle-only search is not sent to the bibliographic catalogues; the provider is
  reported as unable to search, not as having failed.
* **An explainable candidate matcher.** `CandidateMatcher` compares an online candidate with a local
  record one dimension at a time (identifier 0.45, title 0.30, circle 0.15, author 0.10, cover 0.35 — the
  weights are documented constants), renormalises over the dimensions both sides can supply so a field a
  provider never sends is never held against it, treats an exact product identifier as decisive, refuses to
  call a shared title identity, and returns per-dimension evidence plus an ambiguity list instead of
  silently choosing a record.

### Tests

* `OnlineSearchRefinementTest` (18): query parsing and normalisation, capability routing, identifier
  precedence, missing-evidence handling, cover participation, ambiguity and similarity. The suite also
  caught three real defects during development (a title-only match was being called *likely*, a spaced
  `RJ` code was not parsed, and ranking used the enum's declaration order instead of a quality order), all
  fixed before the build below.

### Known limitations

* **No UI wiring yet.** The unified search screen, source-grouped results, side-by-side cover comparison,
  confirm/reject actions, collection-status controls, cover-image cache and wishlist integration described
  in the prompt are **not implemented**; the new core is not reachable from the UI.
* Image similarity is accepted as an input from the existing OpenCLIP pipeline but is not called from it
  yet, and local catalogue records are not yet read into `LocalProduct`.
* Provider access is unchanged: Open Library, Google Books and DLsite are queried; Melonbooks, Suruga-ya,
  Doujin no Mori, E-Hentai, Doujinshi.info, MyAnimeList, nhentai and Toranoana stay browser-only for the
  measured reasons in `ONLINE_SEARCH.md`. No provider was newly enabled and **no reverse-image discovery**
  is claimed or implemented.

## v2.4.0 (2026-11-05)

*The stable release is the `2.4.0-rc.7` code base — the QA-remediation build documented in the section
above — with the application version set to `2.4.0` (versionCode 215). `rc.5` and `rc.6` were pre-release
builds of the same release at versionCode 213; `rc.7` was 214. No feature, parsing rule or test expectation
changed between `rc.7` and this release.*

### Added

* **A worksheet filter on the Gallery.** Inside *More → Workbook comments → Insights → Gallery* the filter
  bar now starts with *Worksheets: All worksheets / LIST / 2 of 3 worksheets*, which opens the same
  worksheet picker the Health and Analytics reports use: all worksheets, one, or any combination, with each
  worksheet's record count and hidden state, and **Use these worksheets** to commit.
  The selection is a set of worksheet **identities** (the package part, `xl/worksheets/sheet5.xml`), not
  display names — renaming a worksheet in Excel keeps the selection, and a key the workbook no longer has
  is reconciled instead of hiding every card. It filters the records **before** anything else runs, so it
  changes what the grid holds rather than hiding cards after they were chosen: search, *With cover*,
  *No cover*, *Missing title*, *Missing metadata*, every sort order, the count line (`12 of 40 record(s)`)
  and the scroll reset all work with it. Gallery, Health and Analytics each keep their **own** selection.
  A workbook opened in a fresh session starts at *All worksheets*, so a selection cannot follow the user
  into another file.
* **A worksheet filter on Health, Analytics and the Gallery that is remembered.** Each of the three screens
  keeps its own selection — all worksheets, one, or several — and it now survives navigation, closing the
  workbook and restarting the app: `files/mappings/insights-views.json` stores it per workbook (its SHA-256),
  never per display name, and a saved key the workbook no longer has is dropped instead of matching some
  other sheet. None of the three can overwrite another's selection.
* **The timeline's year filter is remembered too**, separately for Published and Acquired, so reopening a
  workbook comes back to the period the user was reading.
* **Linked cells in the Catalogue table, and a cell dialog to act on them.** The workbook's own hyperlinks
  (`<hyperlinks>` plus the worksheet's `.rels`) are now kept with the cell they belong to: a linked value is
  drawn in the accent colour and underlined, and tapping any cell opens a centred dialog with its value and
  three stacked actions — **Copy** (the displayed text, exactly as shown), **Open Link** (only for a link the
  workbook really stores: an `http(s)`/`mailto` destination goes to the browser, an in-workbook `location`
  opens that cell in the app's own source view) and **Back** — outside a tap on the card the dialog closes.
  Nothing is inferred from the text: a cell that merely looks like a URL has no link, and the destination
  never replaces the displayed value.
* **A "Default order" field for the workbook's own ordering column.** *Insights → Fields* now has an
  optional **Default order** field — a serial number, a shelf position, anything the user picks. It is
  mapped like any other field (any header, any language, any position), can be changed or cleared, and the
  screen shows what the chosen column really holds (how many values are numbers, how many are text, how many
  are blank, plus examples). Nothing is inferred from a header name: the field is offered, never forced.
  When two records share a Published or Acquired date, the timeline now orders them by this column —
  **ascending**, numerically (`2` before `10`, `0012` the same as `12`), and text values predictably after
  numbers. A record with no value sorts last and keeps its verified source row as the final tie-break, so
  nothing is invented. Changing the mapping changes the order; without it, the timeline keeps its previous
  behaviour.
* **The Source location preview reads and behaves like the table.** The worksheet window that *go to source*
  opens used to print the raw cell — a date-formatted serial showed as `45292`, a percentage as `0.123456` —
  and no cell could be inspected. Every cell is now drawn the way the workbook shows it, through the **same**
  number-format rule the Catalogue table uses (one call, two screens, so the same cell can never read two
  ways), every cell is a touch target that opens the same **Copy / Open Link / Back** dialog, and a cell the
  workbook links is drawn in the accent colour and underlined. The dialog's identity is the worksheet's own
  row and column — the window shows about thirteen rows around the verified cell, so the drawn position is
  never used as the identity — and the value, the hyperlink and *Open Link* therefore always describe the
  cell that was tapped. Header cells are inspectable too, whether the header row is inside the window or
  carried above it.
* **The mapping is remembered per workbook.** *Insights → Fields* now stores the mapping in effect next to the
  other per-workbook settings (`files/mappings/workbook-mappings.json`, keyed by the workbook's SHA-256), so
  a manual choice — the Default order column included — is still in effect after navigating away, closing the
  workbook or restarting the app, instead of falling back to detection. Picking **Detected** removes the
  stored entry; nothing unapplied is ever stored.

### Changed

* **The Rescan button is now the blue of the Saved workbooks Import button** (`Palette.NavyGradient`, the
  same token, not a new blue), so it reads as the primary action of the header next to the grey Back button.
  Its size, typography, and its loading/disabled behaviour are unchanged.
* **Cover first is no longer a gallery order.** Cover state stays a **filter** (*With cover* / *No cover*)
  driven by the verified image association; the separate "Cover first" ordering option is gone, so the four
  remaining orders describe the workbook (title, Circle, Artist, source order) and nothing ranks cards by
  their picture.
* **Numbers are displayed with at most two decimals, everywhere.** One shared rule
  (`Formatting.decimal`) now formats every measurement the app shows: a similarity score reads `0.81`
  instead of `0.812`, a workbook cell whose number format declares decimals reads `1234.57` instead of
  `1234.5678`, and percentages keep their own formatting (`12.35%`). This is a **display** rule only —
  nothing is rounded before it is stored, aggregated, sorted, filtered or exported, and a value that is not
  a measurement is left alone: `General` and integer cells (identifiers, serial numbers, row numbers, page
  counts) keep every digit they had.
* **Timeline: Published and Acquired only.** The **Imported** mode was removed from the timeline: it
  repeated every record once, under the app's own import date rather than a date the collection has. Both
  remaining modes, their manual column choices, their filters, their ordering and the scroll reset are
  unchanged. Nothing else about import metadata changed — the saved-workbook library still records and sorts
  by its import timestamp.
* **Catalogue → Table shows dates as dates.** A cell whose number format is a date was printed as the
  serial number Excel stores (`45292`); it now reads `2024-01-01`, and `2024-01-01 14:30` when the cell also
  carries a time. The workbook's own date system is honoured (@1900/@1904), and the record's stored value is
  untouched, so filters, search, exports and the canonical catalog keep reading exactly what the file
  contains.
* **A percentage cell is rounded to two decimals.** A cell formatted as a percentage was printed as the
  stored fraction (`0.123456`); it now reads `12.35%`, `50%`, `100%`, `0%`. Trailing zeros are dropped, the
  fraction is scaled exactly once, and a cell that is *not* percentage-formatted never gains a `%`. Nothing
  is rounded before it is stored.

### Fixed

* **Rescan now refreshes every Insights function in place.** Clicking **Rescan** while the Insights
  directory was open dropped the parsed data but rebuilt nothing, so Health, Analytics, Gallery, Timeline and
  Fields only came back after leaving the directory and returning. A finished rescan now rebuilds the catalog
  and all five functions from the new analysis immediately, re-previews the Fields draft, and keeps every
  choice the user made (profiles, worksheet scopes, gallery and timeline filters, chosen date columns) — the
  panels show a reading state instead of an empty report while it runs, and a failed scan clears that state
  instead of leaving them stuck.
* **The global Sheets button is gone.** One button in the shared header opened the worksheet picker for every
  directory, including the three that keep their own independent worksheet scope. It was removed; the picker
  is still opened where it belongs (Comments, Images, Catalogue), and no worksheet feature was touched.
* **A manual mapping now outranks automatic detection.** A mapping applied from the Fields screen without
  being saved as a profile was silently reverted to the detected one on the next rebuild (and would have been
  on a rescan). It is now kept in the state, used by every report until the user picks a profile or
  **Detected**, and the Fields screen says which of the three is in effect.
* **The Default order column can be changed in Fields.** The field had no visible control of its own: the row's
  only affordance was a muted word, and the read-only card beside it looked like the whole story, so the one
  column the timeline depends on appeared locked. Every mapped field now shows a bordered **Change** button, and
  **Default order** has its own card with **Change / Choose column**, **Clear** and **Use detected** — the same
  column picker the other fields use, with *Not mapped* available — plus a line saying which column detection
  would use. The picker also no longer fails when two columns carry the same header (both stay choosable, and
  the resolver keeps reporting the ambiguity).
* **Applying an unchanged mapping keeps the Detected status.** The indicator was a pair of state flags ("no
  profile picked, no manual mapping applied"), so **Apply mapping** on a mapping that was identical to the
  detected one turned it grey even though nothing had changed — it reported the click instead of the mapping.
  It now compares the mapping **in effect** with the mapping detection would choose for that worksheet, by
  meaning rather than by object: trimmed, case-folded headers, blank ("not assigned") values ignored, fields
  present on one side only a difference. Applying exactly what detection found stores no override at all, so
  the status stays blue and nothing is written; changing one or more assignments turns it grey; putting the
  detected assignments back — or picking **Detected** — turns it blue again.
* **Detected and a saved profile are two independent views, and picking one only changes what is shown.** The
  Fields screen's chips used to *apply* a profile to the worksheet on screen, and the indicator compared state
  flags, so it was never clear whether a profile had been picked or a mapping merely matched. Picking **Detected**
  or a profile now **switches which configuration is displayed and used**: Detected is derived from the workbook's
  own headers whenever it is needed and is never written to, and each saved profile keeps its own record. A switch
  in either direction copies, merges and rewrites nothing, so Detected → profile → Detected → profile shows each
  configuration exactly as it was, and the draft, the reports and the "mapping in effect" line follow the view.
  Applying a mapping on top of the selected view stays a separate, explicit action; switching the view drops the
  edits that belonged to the previous one (they are this workbook's session state, not saved data), and **Save
  profile** is how an edit is kept.
* **A saved profile holds one mapping per worksheet, and saving one carries the others forward.** A profile was a
  single column set, so saving it while `LIST` was on screen made `LIST`'s header names the mapping of **every**
  worksheet: the other worksheets were re-resolved with them, their own detected mappings were lost, and `LIST`'s
  blank fields appeared as unmapped everywhere. A profile is now a snapshot of **several worksheets**, keyed by
  worksheet identity (the worksheet's package part), because two worksheets rarely share a header row. Saving a
  new profile records the worksheet on screen with the user's edits and **every other worksheet with the mapping
  it already has** (the selected profile's entry, or its own detected headers), so untouched worksheets are
  inherited rather than reset or overwritten, an empty value stays where it was cleared, and the worksheet on
  screen is never a template for the others. Deriving a profile from another profile keeps that profile's
  customisations and replaces only the worksheets actually edited. A worksheet a profile says nothing about (one
  added later, or another workbook's) is read with its own detected headers instead of borrowing a mapping.
  Deleting a profile changes no worksheet: the view falls back to Detected. `profiles.json` is now schema 2 (one
  entry per worksheet; a schema-1 profile still loads as the single column set it was), and
  `files/mappings/workbook-mappings.json` remembers the selected view and the per-worksheet edits, migrating a
  file written by the previous build (one column set for the whole workbook) to the worksheets that really have
  those headers.
* **A price, a page count or an identifier can no longer look like a date.** The display decides what a
  number means from the cell's **own number format** (`xl/styles.xml` + the cell's style index), never from
  its value: `45292` is a date only in a date-formatted cell, and a bare number in a `General` or integer
  cell keeps its digits. Text cells are never formatted, even when their style says otherwise, and invalid
  or ambiguous values (`0`, Excel's fictitious `1900-02-29`) keep their stored text rather than being
  shifted to a neighbouring day.
* **The Images tab's catalogue filter keeps working with formatted columns.** Its value list and its
  matching now use the same displayed text the Catalogue table shows, so a date filter still finds the rows
  it lists.

### Verified

* 781 JVM tests, 0 failures, 7 skipped by design (4 opt-in live provider checks and 3 real-workbook tests
  whose 40 MB files are not committed), across 94 result classes. 64 of them are new for this release: the
  Gallery worksheet filter 11, date and percentage formatting 11, the formatting workbook 4, the Default
  Order mapping and its timeline tie-break 10, the persisted views 8, the rescan state 4, the cell dialog and
  mapping precedence 5, the hyperlink parser 7, the real-workbook date/order/link checks 3 and the 1904
  date-system case 1, minus one test for the removed import timeline, plus 26 for the source-preview and
  mapping-persistence corrections (`SourceWindowFormattingTest` 14, `MappingPersistenceTest` 11 and the real
  workbook reading identically in both table views 1), 29 for the Detected-status and per-worksheet-mapping
  corrections (`DetectedStatusTest` 14, `WorksheetMappingIsolationTest` 12, three more in
  `MappingPersistenceTest`), and 5 more for this round: `ProfileViewSwitchingTest` 7 (the switching matrix:
  Detected → profile → Detected → profile, two profiles in turn, the draft following the view, no profile
  changing) plus `WorksheetMappingIsolationTest` 11 and `DetectedStatusTest` 14 rewritten around profile
  inheritance (`MappingPersistenceTest` 13 covers the view, the edits and both migrations). The regression
  suite — health, gallery, analytics, timeline, catalogue, source navigation, rescan and the persisted
  views — still passes unchanged.
* The new date and percentage reading was run against the real `test_excel/doujin.xlsx`: its `出版日付` and
  `購入日` columns are `yyyy"年"m"月"d"日"`-formatted serials and display as dates, while `価格（稅込）` and
  `ページ数` stay numeric. Its `通し番号` column is detected as the Default order and really does order the
  records that share a publication date, and its stored hyperlinks (the circle column links to a shop search)
  reach the right cells while the cells keep their own text.
* The workbook's date system is honoured everywhere a serial is read — the table, the date-column preview,
  both timelines, the analytics' year buckets and the source preview — so a 1904 workbook cannot show one year
  in the table and another on the timeline (pinned by a dedicated test).
* The source preview was checked against the same real `test_excel/doujin.xlsx`: every column of a record reads
  identically in the Catalogue table and in the window, and the workbook's stored hyperlinks reach the same
  cells in both.
* Debug build: BUILD SUCCESSFUL, version 213 / 2.4.0.

### Known limitations

* The formatted text is a **display** value: the CSV export, the catalogue filters' identity and the
  canonical catalog keep the stored text, in both views alike. A filter is chosen from what the table shows,
  so two source values that display identically are one filter chip.
* Percentages are capped at two decimals even when a format asks for more (`0.00000%` still shows two), and
  are not padded when a format asks for fewer, so the two-decimal rule is applied to the value rather than
  copied from the format's own precision.
* The two-decimal rule reaches a workbook cell only when its number format declares decimals (`0.00`,
  `#,##0.00`); `General` and integer cells are shown exactly as stored, because an integer cell is as likely
  an identifier as a measurement. The workbook's own currency symbol is not re-rendered — the table shows the
  number, as it always did.
* Only `http`, `https` and `mailto` destinations are handed to the browser, and only `Sheet!Cell` locations
  are followed inside the workbook. A `file://` link, a defined name or a scheme with no handler keeps its
  value and its link, but **Open Link** is not offered for it.
* The cell dialog, the source preview's cells and the Fields buttons were compiled and covered by state tests,
  but not tapped through on a device; the dialog's clipboard and browser calls need an Android runtime and are
  not unit-tested.
* The source preview draws at most 96 columns of a worksheet (and says how many it left out), and a hyperlink
  whose `ref` covers more than 1024 cells is attached to that range's first cell rather than expanded.
* The stored mapping is the one that was **applied**; a draft that was never applied is not remembered. It is
  stored per worksheet, so each worksheet of a workbook keeps its own mapping — and a mapping identical to what
  the selected view already gives that worksheet is not stored at all, because it changes nothing.
* Switching the view drops the edits applied on top of the previous view: they are the session state of one
  configuration, not saved data, so an edit that should outlive a switch has to be saved as a profile.
* A profile covers the worksheets it was saved with. A worksheet it has no entry for (one added to the workbook
  later, or another workbook's) is read with its own detected headers rather than with a column set borrowed
  from a sibling, so reusing a profile on a different workbook is safe but not automatic.
* When a profile whose mapping is exactly detection's is selected, the **Detected** chip stays blue (the mapping
  in effect *is* detection) while that profile's chip is also highlighted (it is the selected view): both
  statements are true, and the line under the chips names the view that is showing.
* The Images and Catalogue worksheet picker is session state: it is not remembered per workbook the way the
  three Insights scopes are.
* None of the layout (the gallery's worksheet bar, the picker, the table cells, the source window's cells, the
  Fields buttons) was driven on a device or an emulator, and no screen width was measured.

**Version code/name: 215 / 2.4.0.**

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
