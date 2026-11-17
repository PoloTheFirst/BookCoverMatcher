# Bug fix report — v2.4.0-rc.7

Two confirmed defects were fixed with the smallest change that addresses the root cause. No feature was
added, no parsing rule was changed, and no unrelated file was touched.

## Changes

| File | Change | Why |
|---|---|---|
| `app/src/main/java/com/bookcovermatcher/core/excel/comments/CatalogueTable.kt` | New `CatalogueTruncation(blankRow, skippedRows, firstSkippedRow)`; `CatalogueTable.truncation` (trailing default, so every existing construction still compiles); reader captures the truncation instead of just `break`ing; new private `truncationBelow(...)` counts the rows below the gap that carry data in the table's columns | XLS-01: the consequence of the stop rule becomes data |
| `app/src/main/java/com/bookcovermatcher/core/catalog/HealthReportBuilder.kt` | `structure()` adds `structure:blank-row:<sheet>`: STRUCTURE / **WARNING** / MANUAL, naming the row the table stopped at, how many rows follow and where they start | The workbook-level report is where "the sheet is shaped in a way that hides records" belongs |
| `app/src/main/java/com/bookcovermatcher/ui/WorkbookBrowserPanels.kt` | `CatalogueBrowser` prints one `Palette.Warn` line for the scoped tables under the record count; `TableSheetHeader` repeats the note under that worksheet's header | The user is looking at the records when the number is short |
| `app/src/main/java/com/bookcovermatcher/core/Config.kt` | `DEFAULT_RANGE` `A1:B20` → `A1:Z5000`, with the reasoning in a KDoc comment | UI-01: the default must cover the layout the app documents |
| `app/src/main/java/com/bookcovermatcher/MainViewModel.kt` | The empty-result toast names the range and says covers usually sit in the title column | UI-01: make the failure actionable |
| `app/src/test/java/com/bookcovermatcher/core/CatalogueTruncationTest.kt` (new) | 6 tests | Regression coverage for XLS-01 |
| `app/src/test/java/com/bookcovermatcher/core/DefaultRangeTest.kt` (new) | 3 tests | Regression coverage for UI-01 |
| `app/src/test/java/com/bookcovermatcher/core/catalog/CatalogFixtures.kt` | `table(...)` accepts a `truncation` | Lets the health test build a truncated table |

## What deliberately did **not** change

* The blank-row stop rule, `MAX_RECORDS`, `MIN_HEADER_CELLS`, header detection, column selection.
* Any record count of any fixture (`layout_blank_rows` still yields exactly 3 records).
* The image extraction plan, `MAX_EXTRACTED_IMAGES`, or any camera/ONNX/wishlist/mapping code.
* A stored or typed range: `Prefs.range` still returns the user's value when one exists.

## Verification of each fix

| Defect | Unit test | Device verification |
|---|---|---|
| XLS-01 | `CatalogueTruncationTest` 6/6 — count stops at the blank row; rows below counted with row numbers; leading blanks and out-of-table cells are not truncations; the health finding is a per-sheet warning naming rows 5–10 | Pixel 8 AVD: Catalogue shows `3 record(s) shown` **plus** `LIST: 5 rows below empty row 5 not read`; Table view shows the same note; Health shows the warning (screenshots and dumps in `reports/v2.4.0-rc.7/`) |
| UI-01 | `DefaultRangeTest` 3/3 — the default parses, contains columns `D` and `X`, spans 5000 rows, `UiState` starts on it, and `A1:B20` would have missed `D` | Pixel 8 AVD, app data cleared: the range field on the first screen reads `A1:Z5000` |

## Honest status of each fix

| Fix | Status |
|---|---|
| Catalogue truncation is reported (XLS-01) | **Confirmed and verified** — unit tests + real UI + health report, with the record count unchanged |
| Default range covers the documented layout (UI-01) | **Confirmed and verified** for the default (fresh install on the device) |
| Empty-result message names the range (UI-01) | **Code-verified only** — the full scan-and-find path (query embedding → Find) was not re-driven this round, so the toast text was not observed on the device |

## Build and tests after the changes

```
.\gradlew.bat :app:assembleDebug        → BUILD SUCCESSFUL (195,194,901 B, version 214 / 2.4.0-rc.7)
.\gradlew.bat :app:testDebugUnitTest    → BUILD SUCCESSFUL, 790 tests, 0 failures, 0 errors, 7 skipped, 96 classes
```
Logs: `reports/v2.4.0-rc.7/build-logs/`.
