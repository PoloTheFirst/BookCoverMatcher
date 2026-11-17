# Regression test report — v2.4.0-rc.7

## Unit suite

```
.\gradlew.bat :app:testDebugUnitTest
```

| Metric | Before this round | After |
|---|---|---|
| Tests | 781 | **790** |
| Result classes | 94 | **96** |
| Failures | 0 | **0** |
| Errors | 0 | **0** |
| Skipped by design | 7 | **7** (4 opt-in live provider checks, 3 real-workbook tests whose 40 MB inputs are not in `test_excel/`) |
| Build result | — | **BUILD SUCCESSFUL** |

The 9 new tests are `CatalogueTruncationTest` (6) and `DefaultRangeTest` (3). No existing test was
modified, weakened or skipped to make the new behaviour fit — `ExcelCoreTest` and `PipelineTest` use the
literal `A1:B20` as a *sample range string*, not as the default, so they were untouched.

## Build

```
.\gradlew.bat :app:assembleDebug
→ BUILD SUCCESSFUL in 39s, 55 tasks, app-debug.apk 195,194,901 bytes, versionCode 214, versionName 2.4.0-rc.7
```

## Emulator regression (Pixel 8 AVD, `emulator-5554`)

| Area | Test | Result |
|---|---|---|
| App launch after `pm clear` + install | reaches the home screen, no fatal exception | **PASS** |
| Excel import | `layout_blank_rows.xlsx` staged via the real picker | **PASS** |
| Worksheet enumeration / catalogue reading | `3 record(s) shown` — unchanged from the pre-fix count | **PASS** |
| Catalogue warning (new) | `LIST: 5 rows below empty row 5 not read` | **PASS** |
| Table view per-sheet note (new) | `5 rows below empty row 5 were not read — a blank row ends a table` | **PASS** |
| Health finding (new) | warning with rows 5→10 and the fix | **PASS** |
| Default range (new) | `A1:Z5000` on a fresh install | **PASS** |
| Crash/ANR check | `logcat -d` filtered for `FATAL EXCEPTION` / `ANR in com.bookcovermatcher` | **PASS** (0) |

## Regression matrix — status of every area the brief lists

| Area | Status | Note |
|---|---|---|
| Workbook import | **PASS** (1 fixture this round; 47/52 by the previous engagement) | Parser code unchanged except the truncation measurement |
| Worksheet enumeration | **PASS** | `LIST` detected in the imported fixture |
| Embedded image extraction | **NOT RUN** (this round) | Unchanged code; 52/52 verified by the previous engagement |
| Excel notes/comments | **NOT RUN** (this round) | 8 comments read on the imported fixture (visible in the prior sweep); code unchanged |
| Image-to-cell association | **NOT RUN** (this round) | Unchanged code |
| Camera preview / capture | **NOT RUN** (this round) | Unchanged code; passed previously |
| Image orientation / cropping | **NOT RUN** (this round) | Unchanged code |
| OpenCLIP / ONNX inference | **NOT RUN** (this round) | Unchanged code; passed previously |
| Local matching, result ordering | **NOT RUN** (this round) | Unchanged code; the empty-result message is the only change |
| Wishlist | **NOT RUN** (this round) | Unchanged code; 790-test suite covers the model |
| Preferences | **PASS** (partially) | The range default is exercised by `DefaultRangeTest` and the fresh-install device check |
| Profile switching / worksheet mapping | **NOT RUN** (this round) | Unchanged code; `ProfileViewSwitchingTest`/`WorksheetMappingIsolationTest` still pass |
| Auto Scan | **NOT RUN** (this round) | Unchanged code; detection quality remains BLOCKED (camera content) |
| Offline behaviour | **PASS** (implicitly) | The whole run had no network dependency; the import, catalogue and health path are local |

## What this means

The **changed** behaviour is verified at both levels (unit + device). The **unchanged** areas were not
re-tested in this round; they are covered by (a) the 790-test suite, which includes the rc.2–rc.6
regression tests for mapping, profiles, formats, filters and timelines, and (b) the previous engagement's
emulator run. This report does not claim a fresh device pass for them.
