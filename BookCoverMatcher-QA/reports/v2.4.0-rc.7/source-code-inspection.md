# Source-code inspection — v2.4.0-rc.7

Only the paths needed to understand the two confirmed defects were traced in depth; the rest of the app
was left alone (no unrelated refactoring).

## XLS-01 path

```
xl/worksheets/sheetN.xml
  └─ SheetCells.read                     (cells + shared strings + styles)
       └─ CatalogueReader.fromCells      ← the defect lives here
            detectHeaderRow              (first 12 rows; ≥3 usable header cells)
            columns                      (every column between first and last header)
            for row in headerRow+1 .. headerRow+maxRecords
               values.all { it.isEmpty() } → if records.isNotEmpty() BREAK   ← silent end of table
            CatalogueTable(sheetName, headerRow, fields, records)
```

* `CatalogueReader.MAX_RECORDS = 5000`, `MIN_HEADER_CELLS = 3`, `HEADER_SCAN_ROWS = 12`.
* The stop rule is **intentional** and is encoded in the fixture contract (`layout_blank_rows` expects 3
  records). Skipping blank rows would let two unrelated blocks on one sheet merge into one catalogue, and
  the authentic workbook never has an interior blank row, so the rule was preserved.
* What was missing was any signal: `CatalogueTable` had no field for "there was more below", so the
  Catalogue tab, the Health report and the import flow all had nothing to report.

Consumers inspected: `WorkbookAnalyzer.analyze` (tables → `analysis.tables`), `WorkbookCatalogBuilder`
(`tables.associateBy { it.sheetName }`, i.e. the same instances), `HealthReportBuilder.structure`
(findings map keyed by id, `HealthSeverity`, `HealthResolution.MANUAL`), `WorkbookBrowserPanels`
(`CatalogueBrowser`, `TableSheetHeader`, `RecordCards`, `WorksheetTables`), `Palette.Warn` for the colour.

## UI-01 path

```
UiState.rangeText  = Config.DEFAULT_RANGE            (fresh state)
Prefs.range        = stored value ?: Config.DEFAULT_RANGE
MainViewModel.findTopMatches
   └─ CellRange.parse(rangeText)                     (A1:B20 shape)
   └─ loadOrBuildCatalog(workbook, range, model)
        └─ CatalogBuilder.build(zip, range, model)
             └─ WorkbookImageExtractor.plan(zip, range, includeFloating)
                  listCommentCandidates  → VML shapes, filtered by range.contains(row, col)
                  listFloatingCandidates → drawing anchors, filtered by range
   └─ catalog.size == 0 → toast "No images found inside <range> …"
```

Two facts decided the fix:

1. **The range is a filter over the workbook's own anchors**, not an iteration over cells, so widening it
   costs nothing per empty cell. `Config.MAX_EXTRACTED_IMAGES = 300` still bounds the work.
2. The shipped default (`A1:B20`, two columns) was **narrower than the layout the app documents**
   (`LIST!A:X`, covers in `D`), and `Prefs.range` returns the constant whenever the preference is unset —
   so the default, not a stored value, is what a first run uses.

## Deliberately not touched

* `WorkbookImageExtractor`, `SheetCells`, `SheetLinks`, `NumberFormats`, `WorkbookStyles`: no defect was
  found in them, and the two fixes do not need them.
* CameraX, ONNX/OpenCLIP, wishlist, profiles/worksheet mapping: unchanged in this round.

## Code changes made (summary)

| File | Change |
|---|---|
| `core/excel/comments/CatalogueTable.kt` | `CatalogueTruncation` value; `CatalogueTable.truncation`; reader counts the rows below the blank row that carry data in the table's columns (`truncationBelow`), bounded by the same `maxRecords` |
| `core/catalog/HealthReportBuilder.kt` | `structure:blank-row:<sheet>` warning (STRUCTURE / WARNING / MANUAL) naming the rows and the fix |
| `ui/WorkbookBrowserPanels.kt` | one warning line under the record count in `CatalogueBrowser`, and the per-sheet note in `TableSheetHeader` |
| `core/Config.kt` | `DEFAULT_RANGE` `A1:B20` → `A1:Z5000`, with the reasoning recorded |
| `MainViewModel.kt` | the empty-result toast names the range and says where covers usually sit |
