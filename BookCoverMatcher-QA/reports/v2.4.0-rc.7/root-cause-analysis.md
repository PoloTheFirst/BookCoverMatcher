# Root-cause analysis — v2.4.0-rc.7

## RCA-1 — XLS-01: a catalogue silently stopped at the first blank row

**Symptom.** `layout_blank_rows.xlsx` showed 8 comments and **3** records. Rows 5–9 held five more
records; nothing in the UI said so.

**Cause.** `CatalogueReader.fromCells` ends the table on the first all-empty row once records have
started:

```kotlin
if (values.all { it.isEmpty() }) {
    if (records.isNotEmpty()) break else continue
}
```

The rule itself is right for this app: a blank row is how a sheet ends a table, and skipping blank rows
would let two unrelated blocks on one sheet merge into one catalogue (the authentic workbook relies on
the rule — it has no interior blank row). The defect was that the **consequence was never made data**:
`CatalogueTable` had no field for "there is more below", so no consumer *could* report it. The user saw a
smaller number with no explanation, and the only place the behaviour was documented was the fixture
manifest.

**Fix (smallest that addresses the cause).** Keep the rule; measure its effect; report it.

* `CatalogueReader.truncationBelow(byRowCol, columns, blankRow, lastRow)` counts the rows below the gap
  that carry data **in the table's own columns** and returns `CatalogueTruncation(blankRow, skippedRows,
  firstSkippedRow)`; it is bounded by the same `maxRecords` the reader uses, so a pathological sheet
  cannot turn the count into a second full scan.
* `CatalogueTable.truncation` carries it; `WorkbookCatalogBuilder` keeps the same table instances, so the
  reports see it without another parse.
* Three surfaces: the Catalogue tab (one line for the scoped tables), the Table view (the note under that
  sheet's header), and `HealthReportBuilder.structure` →
  `structure:blank-row:<sheet>` (STRUCTURE / WARNING / MANUAL) naming the rows and the fix.

**Why not "skip blank rows"?** It changes what a record is for every workbook, could merge tables, and
would silently change the fixture contract the whole QA corpus is built on. The brief's own guidance is
to fix the observed problem, not to redesign parsing.

## RCA-2 — UI-01: the default range was narrower than the documented layout

**Symptom.** A fresh install, canonical workbook, "Link & Find Top 5" → toast *"No images found inside
A1:B20 (checked floating images and comment fills)."* and zero results.

**Cause, two parts.**

1. `Config.DEFAULT_RANGE = "A1:B20"` selects two columns, while the layout the app documents (and the
   authentic workbook uses) has 24 columns in `A:X` with the cover pictures in the title column `D`.
   `Prefs.range` returns that constant whenever the preference is unset, so the wrong default is what a
   first run uses — it is not a stored user choice being respected.
2. The message reported the emptiness but not the likely cause, so the user had no path from "no images"
   to "your range is too small".

**Why the fix is safe.** `WorkbookImageExtractor.plan` reads the workbook's VML shapes and drawing anchors
and keeps the ones inside the range (`range.contains(row, col)`); the cost is per picture, not per cell,
and `Config.MAX_EXTRACTED_IMAGES` still bounds it. A stored or typed range is never overwritten — only the
default changes.

**Fix.** `DEFAULT_RANGE = "A1:Z5000"` (covers `A:X` and the authentic 124 records with room to spare), and
the empty-result toast now names the range and says covers usually sit in the title column.

## RCA-3 — why two QA scripts wrote to the pre-relocation directory

`deepseek_driver.py` and `deepseek_structure_check.py` hard-code
`QA = C:\Users\ASUS\AndroidStudioProjects\BookCoverMatcher-QA`, the path from before the workspace was
moved inside the project. My first verification run therefore wrote its evidence outside the project. The
evidence was copied into `reports/v2.4.0-rc.7/` and the external copy removed; the rc.7 scripts derive the
root from their own location (`os.path.dirname(os.path.dirname(__file__))`) so they cannot drift again.
The two legacy scripts were **not** edited (they are prior QA artifacts); the finding is recorded instead.

## RCA-4 — why the first home-screen dump had no range field

Two causes, both benign: the range control is below the fold (the camera card fills the top third), and
`uiautomator dump` waits for an idle window while the home screen runs `rememberInfiniteTransition`. The
verification script scrolls, re-dumps and bounds each dump, which reproduced the field reliably.
