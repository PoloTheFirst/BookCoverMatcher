# Emulator test report — v2.4.0-rc.7

## Device

| Item | Value |
|---|---|
| AVD | `Pixel_8` (existing configuration, no duplicate created) |
| ADB id | **`emulator-5554`** (verified with `adb devices -l`; not assumed) |
| Product / OS | `sdk_gphone64_x86_64`, Android 17 / **API 37**, build `CE2A.260420.051.A2` |
| Display | 1080 × 2400 @ 420 dpi |
| Boot | headless cold boot (`-no-window -no-audio -no-boot-anim -gpu swiftshader_indirect`); the saved snapshot failed to load |
| Build installed | `app-debug.apk`, `adb install -r` → **Success** |

## What was done, in order

1. **Preserved the device's previous state** before clearing anything:
   `test-output/device-prefs-before-rc7.xml` (the prefs file the prior round left behind).
2. **Fresh-install state**: `adb shell pm clear com.bookcovermatcher`, then launch. The app reached the
   home screen; logcat was cleared first so the run's output is its own.
3. **UI-01 part 1 — the range a fresh install starts on.** The range field is below the fold (the camera
   card takes the top third), so the script scrolls the home content and re-dumps. Result:
   `A1:Z5000` (evidence `screenshots/r7-008-fresh-range.png`, `test-output/r7-008-fresh-range.txt`,
   `rc7-range-verify.json`).
4. **XLS-01 — import `worksheet-layout/layout_blank_rows.xlsx`** through the real SAF picker
   (`documentsui`, list view, `Documents/QA/worksheet-layout/…`). Staged label:
   `layout_blank_rows.xlsx · 34 KB`.
5. **Open the workbook** (*More → Open workbook comments*) and read the **Catalogue** tab.
6. Switch the Catalogue to **Table** and read the per-sheet header.
7. Open **Insights → Health** and read the findings.

## Results

| # | Check | Expected after the fix | Observed | Verdict |
|---|---|---|---|---|
| 1 | Fresh install's range field | `A1:Z5000` | `A1:Z5000` | **PASS** |
| 2 | Import of the fixture | staged, no error | `layout_blank_rows.xlsx · 34 KB` | **PASS** |
| 3 | Record count (must not change) | `3 record(s) shown · all worksheets` | `3 record(s) shown · all worksheets` | **PASS** |
| 4 | Catalogue warning | names the sheet, the rows and the row number | `LIST: 5 rows below empty row 5 not read` | **PASS** |
| 5 | Table-view per-sheet note | visible under `LIST`'s header | `5 rows below empty row 5 were not read — a blank row ends a table` | **PASS** |
| 6 | Health finding | warning, with row numbers and the fix | `Rows below an empty row were not read` + “The catalogue of “LIST” stops at row 5, which is empty, so 5 rows below it (from row 6) are not counted here…” + “Delete the empty row inside the table, or move the block below it into its own worksheet, then rescan.” | **PASS** |
| 7 | Fatal exceptions / ANRs during the run | 0 | 0 | **PASS** |

## Evidence

| Item | Path (under `BookCoverMatcher-QA/reports/v2.4.0-rc.7/`) |
|---|---|
| Fresh home | `screenshots/r7-001-fresh-home.png`, `test-output/r7-001-fresh-home.txt` |
| Workbook opened | `screenshots/r7-004-workbook-open.png` |
| Catalogue (cards) with the warning | `screenshots/r7-005-catalogue-cards.png`, `test-output/r7-005-catalogue-cards.txt` |
| Catalogue (table) with the per-sheet note | `screenshots/r7-006-catalogue-table.png`, `test-output/r7-006-catalogue-table.txt` |
| Health warning | `screenshots/r7-007-health.png`, `test-output/r7-007-health.txt` |
| Fresh-install range | `screenshots/r7-008-fresh-range.png`, `test-output/r7-008-fresh-range.txt` |
| Machine-readable run log | `test-output/rc7-emulator-verify.json`, `test-output/rc7-range-verify.json` |
| Logcat of the whole run | `logcat/r7-logcat.txt` |
| Emulator boot logs | `emulator-stdout.log`, `emulator-stderr.log` |

## Not tested on the device in this round

| Item | Status | Reason |
|---|---|---|
| The 51 other fixtures | **NOT RUN** (this round) | Verified by the previous engagement against unchanged reader code; only the defect's fixture was re-driven |
| Camera content scenarios | **BLOCKED** | Virtual scene cannot be posed over adb (`camera-test-report.md`) |
| Camera permission / capture | **NOT RUN** (this round) | Unchanged code; passed in the previous engagement |
| Local matching run (scan → Find) | **NOT RUN** (this round) | Needs a query embedding first; the empty-result message is verified by inspection only |
| Wishlist, profiles, worksheet mapping | **NOT RUN** (this round) | Unchanged code; covered by the 790-test JVM suite |
| Online search | **NOT APPLICABLE** | Off by default and out of scope for this patch |
