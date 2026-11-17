# QA evidence audit — v2.4.0-rc.7

**Scope:** every artifact under `BookCoverMatcher-QA` (reports, fixtures, harness, logs, screenshots,
scripts, baselines) plus the two prompt-history files `historical_prompt/test_phone*.md`.
Nothing was deleted or rewritten; the audit adds a new directory and re-runs a validator.

## 1. What exists

| Area | Files | Assessment |
|---|---|---|
| `reports/initial-qa/fixture-inventory.md` | 1 (15 KB) | Fixture inventory. Its "Execute: yes (full)" column is **not supported by any application artifact** — no import evidence exists for that engagement |
| `reports/deepseek-qa-recovery/` | 20 markdown reports | The recovery engagement's full set (audit, plan, test plan, per-area reports, bug register, RCA, fix/regression reports, summary) |
| `test-data/` | 52 `.xlsx` + manifest + generation report + structure analysis + 17 synthetic images | Real fixtures; re-validated in this round |
| `harness/` | `QaParserHarness.java`, `harness_results.json` (52 rows), compiled classes | JVM-level parser evidence |
| `scripts/` | 14 Python scripts | Tooling; two still hard-code the pre-relocation QA path |
| `logcat/`, `screenshots/`, `videos/`, `baseline/` | initial-qa evidence, empty `videos/` | Retained as-is |

## 2. Verified claims

| Claim | Verdict | Evidence in this round |
|---|---|---|
| 52 fixture workbooks, matching the manifest exactly | **Holds** | `scripts/rc7_fixture_check.py` → 52 checked, **52 PASS**, 0 failures (presence, size, sha256, ZIP, `[Content_Types].xml`, workbook part, sheet parts, media parts) |
| Manifest is the expected-value oracle | **Holds for the fields used** (records/comments/media); one field (`unreferenced_media`) is informational only | Prior report's nuance, unchanged |
| The first engagement's "52/52 application pass" | **Invalid as application evidence** | Its own sweep log records 0 imports; only a JVM harness ran |
| The recovery engagement genuinely used the emulator | **Supported** | Screenshots show app screens (not pickers), logcat shows the app process, and its staging labels/counts are consistent with the fixtures |
| Camera permission/capture works | **Supported** | Its capture produced a real embedding; this round did not re-drive it |
| Two open defects (XLS-01, UI-01) | **Confirmed and independently reproduced** | See `defect-triage.md` |
| "No source changes were made by the recovery engagement" | **Holds** | The repo diff contained no QA-round code changes |

## 3. Findings about the QA material itself (not the app)

| ID | Finding | Classification |
|---|---|---|
| QA-01 | `scripts/deepseek_driver.py` and `scripts/deepseek_structure_check.py` still point at `C:\Users\ASUS\AndroidStudioProjects\BookCoverMatcher-QA` (the pre-relocation path). My first rc.7 run wrote evidence there; it was moved into `reports/v2.4.0-rc.7/` and the external copy deleted | Tooling defect, worked around (rc.7 scripts derive the root from `__file__`) |
| QA-02 | `uiautomator dump` blocks on the home screen's infinite animation, so a dump can take its whole timeout | Tooling/environment; the driver bounds and retries |
| QA-03 | `harness/` results were produced before the rc.2–rc.6 rebuild of the parser classes | Supporting evidence only; not re-run in this round |

## 4. What this round did not repeat

* The 52-fixture **UI sweep** was not re-executed end to end. One fixture (`layout_blank_rows.xlsx`) was
  driven through the UI because it is the defect's input; the other 51 remain as the recovery
  engagement verified them, against unchanged parser code.
* The camera content scenarios, the wishlist flow and the matching run were not re-driven (no code change
  in those areas; see `regression-test-report.md` for what was and was not re-verified).
