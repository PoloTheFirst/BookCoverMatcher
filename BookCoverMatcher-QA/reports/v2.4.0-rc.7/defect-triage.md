# Defect triage — v2.4.0-rc.7

Every finding from the previous QA material was re-examined. Nothing is copied forward unverified:
"reproduced" below means I re-created the observation myself, in this environment, on this build.

## XLS-01 — a blank row silently truncates the catalogue

| Field | Value |
|---|---|
| Original report | `reports/deepseek-qa-recovery/bug-register.md` (Medium), `excel-import-report.md` |
| Original test ID | 52-fixture sweep, row `layout_blank_rows` |
| Evidence (prior) | `evidence/screenshots/excel/comments-layout_blank_rows.png`, `fixture-sweep-deepseek.jsonl` |
| Input | `test-data/worksheet-layout/layout_blank_rows.xlsx` |
| Expected | 8 comments and 8 records, **or** an explicit statement that rows below the blank row were skipped |
| Actual | 8 comments, **3** records, no warning anywhere in the UI |
| Classification | **Confirmed and reproducible** — application behaviour, not a fixture error |
| Independent reproduction (this round) | (a) reader-level: `CatalogueTruncationTest` drives `CatalogueReader.fromCells` over the same cell shape and reproduces the stop at the blank row; (b) device-level: the fixture was imported on the Pixel 8 AVD and the Catalogue showed `3 record(s) shown` before the fix |
| Severity / priority | Medium / high (silent data loss in the report, though the workbook itself is untouched) |
| Affected components | `CatalogueReader`, Health report, Catalogue tab |
| Source | `core/excel/comments/CatalogueTable.kt`, `core/catalog/HealthReportBuilder.kt`, `ui/WorkbookBrowserPanels.kt` |
| Status | **Fixed and re-verified** (`bug-fix-report.md`) |
| Next action | None; watch that the record count stays 3 for this fixture (the stop rule is intentional) |

## UI-01 — the default range excludes the title/image column

| Field | Value |
|---|---|
| Original report | `bug-register.md` (Low), `local-matching-report.md` |
| Original test ID | matching run with a fresh install (`A1:B20`) |
| Evidence (prior) | `evidence/screenshots/matching/011-toast-1.png` (zero result) and the widened-range run ("Compared against 5 image(s)") |
| Input | canonical doujin layout: 24 columns `A:X`, cover pictures in the title column `D` |
| Expected | a first run against a real workbook finds the workbook's pictures |
| Actual | `No images found inside A1:B20 (checked floating images and comment fills).` and zero results |
| Classification | **Confirmed and reproducible** — a shipped default narrower than the documented layout, plus a message that does not say what to change |
| Independent reproduction (this round) | `Config.DEFAULT_RANGE` read as `A1:B20`; `DefaultRangeTest` pins that the old range excludes column `D`; a fresh install on the emulator now shows `A1:Z5000` |
| Severity / priority | Low / medium (first-run usability) |
| Affected components | `Config`, `Prefs`, `UiState`, `MainViewModel.findTopMatches` |
| Source | `core/Config.kt`, `MainViewModel.kt` |
| Status | **Fixed and re-verified for the default; the message is verified by inspection only** (the end-to-end scan needs a camera capture or an image upload first) |
| Next action | Tap the full scan-and-find path on a device in the next round |

## Findings re-examined and **not** classified as application defects

| Item | Prior classification | This round |
|---|---|---|
| `valid_note_images_mixed_formats` "MISMATCH" | recorded as an app mismatch | **Incorrect QA assumption** — it compared a manifest field that does not exist (`expected.images`); reproduced by reading the manifest |
| "The selected file is empty" on an 86 KB workbook | suspected import defect | **Environment** — a 0-byte file after an emulator force-kill; `WorkbookFiles.stage` reported it correctly |
| `device offline` mid-run | suspected app/camera fault | **Environment/tooling** — a wedged `uiautomator` process on the home screen's infinite animation |
| First-engagement "0 of 92 imports" | suspected app failure | **Tooling** — the driver never opened the picker; no application evidence |
| Requiring a manual `A1:Z30` for images | product decision | **Now fixed** as UI-01 (the default covers the layout) |

## New findings from this round

| ID | Finding | Classification | Severity |
|---|---|---|---|
| QA-01 | Two QA scripts still target the pre-relocation QA directory | Tooling defect (not the app) | Low |
| ENV-01 | Camera content scenarios remain unreachable over adb | Environmental blocker | — |
| ENV-02 | The AVD's saved snapshot failed to load, so the run cold-booted | Environmental (recovered) | — |
