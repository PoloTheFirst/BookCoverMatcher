# Bug register

**Build:** BookCoverMatcher 2.4.0 (versionCode 213), commit `e751450` + uncommitted working tree
**Device:** Pixel_8 AVD, Android 17 / API 37, 1080x2400 @420dpi

No **Critical** or **High** defects were confirmed. Two issues are logged; both are confirmed on the
emulator and both are behavioural/UX rather than crashes or data corruption.

---

## XLS-01 - Reader stops at the first blank row and silently drops later records

| Field | Value |
|---|---|
| Severity | Medium |
| Type | Confirmed behaviour, design risk |
| Version / commit | 2.4.0 / `e751450` |
| Device | Pixel_8, Android 17 |
| Preconditions | A workbook whose catalogue has an all-empty row between records |
| Steps | 1. Import `test-data/worksheet-layout/layout_blank_rows.xlsx` on Home. 2. More -> Open workbook comments. 3. Compare the Comments tab with the Catalogue tab. |
| Expected | 8 comments and 8 records, or an explicit warning that rows below a blank row were skipped |
| Actual | Comments 8, Catalogue **3**. Rows after the blank row are never read and nothing tells the user. |
| Frequency | Always (reproduced in the sweep and consistent with the JVM harness) |
| Evidence | `evidence/screenshots/excel/comments-layout_blank_rows.png`, sweep row in `fixture-sweep-deepseek.jsonl` |
| Source | `core/excel/comments/CatalogueTable.kt` (blank-row termination), `WorkbookAnalysis.kt` |
| Root cause | The table reader treats the first all-empty row as end-of-table. The fixture generator encoded this as expected (`records: 3`), so it is intentional - but the consequence (silent truncation) is not surfaced anywhere in the UI. |
| Proposed correction | Keep the stop rule but report it: when rows exist below the terminating blank row, add a catalogue warning ("N rows below an empty row were not read"). A parser change to skip blank rows was **not** made - the authentic workbook has no interior blank rows and changing the rule could merge unrelated tables. |
| Regression test | `layout_blank_rows` must keep reporting 3 records *and* the new warning. |
| Status | Open (documented, not fixed) |

---

## UI-01 - Default cell range excludes the title/image column of the canonical layout

| Field | Value |
|---|---|
| Severity | Low |
| Type | UX defect (confirmed) |
| Version / commit | 2.4.0 / `e751450` |
| Preconditions | Fresh install (range defaults to `A1:B20`), canonical doujin-format workbook |
| Steps | 1. Choose any imported workbook. 2. Leave the range at `A1:B20`. 3. Press "Link & Find Top 5". |
| Expected | The comparison runs against the workbook's records |
| Actual | Toast "No images found inside A1:B20 (checked floating images and comment fills)." and zero results. The images live in column D (ブック名), outside A-B. |
| Frequency | Always with the canonical layout |
| Evidence | `evidence/screenshots/matching/011-toast-1.png` (after widening to `A1:Z30`: "Compared against 5 image(s).") |
| Source | `data/Prefs.kt` (default range), `MainViewModel.findTopMatches` |
| Root cause | The shipped default range is narrower than the layout the app documents as authentic. Nothing links "no images found" to "your range is too small". |
| Proposed correction | Either default to a range that covers the canonical 24 columns, or make the empty-catalogue message name the range and suggest widening it. Not changed in this pass: changing defaults alters first-run behaviour for existing users and needs a product decision. |
| Regression test | New-install flow with the canonical workbook must not produce a zero-result first run. |
| Status | Open (documented, not fixed) |

---

## False leads investigated and dismissed

| Item | Why it looked like a defect | Why it is not |
|---|---|---|
| "Error: The selected file is empty." on an 86 KB workbook | Looked like the app misreading a valid file | The file on the device was **0 bytes**: it was pushed and the emulator was force-killed seconds later, so the write never flushed. `WorkbookFiles.stage` correctly reported zero bytes. Re-pushed, md5 matched, import worked. |
| `valid_note_images_mixed_formats` "MISMATCH" in the previous sweep | Recorded as an app mismatch | It compared the UI against a manifest field that does not exist (`expected.images`); the value compared was `None`. |
| Emulator `device offline` mid-run | Looked like an app/camera fault | Caused by a wedged `uiautomator` process after a dump hung on the app's infinite pulse animation; cleared by restarting the AVD. |

## Items with no defect found

Excel import (52/52 behaved as specified), image association, invalid-file rejection, camera
permission handling, capture, ONNX/OpenCLIP ranking, wishlist creation and persistence, navigation.
