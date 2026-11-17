# Release readiness report — v2.4.0-rc.7

**Verdict: ready for review as `2.4.0-rc.7` (versionCode 214), with the limitations listed below
disclosed.** The two defects this build exists to fix are confirmed, fixed and re-verified on the device.
The areas this round did not re-test are named explicitly rather than assumed.

## Application integrity

| Criterion | Status | Evidence |
|---|---|---|
| Native Kotlin + Compose architecture preserved | **PASS** | The diff touches five source files; no dependency, no module, no build setting changed beyond the version |
| Excel import functional | **PASS** | `layout_blank_rows.xlsx` imported through the real picker on the device |
| Embedded image extraction | **PASS (unchanged)** | Not modified; 52/52 fixtures verified by the previous engagement; `CatalogueTruncationTest` exercises the reader path |
| CameraX | **PASS (unchanged)** | Not modified; camera code untouched |
| Cropping / orientation | **PASS (unchanged)** | Not modified |
| OpenCLIP / ONNX Runtime | **PASS (unchanged)** | Not modified |
| Local matching | **PASS (unchanged)** | Not modified; the only related change is the empty-result message |
| Wishlist behaviour | **PASS (unchanged)** | Not modified; model covered by the suite |
| Preferences persist | **PASS** | `DefaultRangeTest` + the fresh-install device check |
| Profile switching does not mutate profiles | **PASS (unchanged)** | `ProfileViewSwitchingTest`/`WorksheetMappingIsolationTest` still pass in the 790-test run |
| Worksheet mappings preserved | **PASS (unchanged)** | Same suites; no mapping code touched |
| Auto Scan intact | **PASS (unchanged)** | Not modified; detection quality remains blocked on camera content |
| Offline features available | **PASS** | The whole verified path (import → catalogue → health) runs without a network |

## QA integrity

| Criterion | Status |
|---|---|
| Previous QA evidence reviewed | **PASS** — `qa-evidence-audit.md` |
| Findings independently classified | **PASS** — `defect-triage.md` (2 confirmed, 5 dismissed with reasons, 3 new) |
| Fixture work resumed from Phase 4 | **PASS** — already complete; independently re-validated (`fixture-completion-report.md`) |
| Missing fixture work completed or documented | **PASS** — nothing missing for this patch; `.xlsm` gap documented again |
| Synthetic fixtures validated | **PASS** — 52/52, exit 0 (`excel-fixture-validation.md`) |
| Corrected fixtures documented separately | **N/A** — no fixture needed repair |
| Authentic source workbook untouched | **PASS** — sha256 `a5ad2ba7…6aad` matches the prior record |
| Fixtures tested through the real application | **PASS for the defect's fixture**; the other 51 are unchanged-code carry-forward |
| Pixel 8 emulator verified | **PASS** — `emulator-5554`, API 37, genuine install/launch/interaction |
| Real application testing performed | **PASS** for both fixes (XLS-01 on three surfaces; UI-01 default) |
| Camera scenarios tested or documented as blocked | **PASS** — documented as **BLOCKED** (`camera-test-report.md`) |
| Evidence preserved | **PASS** — screenshots, dumps, logcat, build logs under `reports/v2.4.0-rc.7/` |

## Defect remediation

| Criterion | Status |
|---|---|
| Confirmed defects have root-cause analysis | **PASS** — `root-cause-analysis.md` RCA-1, RCA-2 |
| Fixes address the actual causes | **PASS** — the stop rule's *silence* is fixed, not the rule; the *default* range is fixed, not a stored value |
| Regression tests added | **PASS** — 9 new tests, both defect paths pinned |
| Original failures re-tested | **PASS** — the same fixture on the same AVD; the record count is unchanged and the warnings appear |
| Neighbouring functionality checked | **PASS** — record count, comments, health summary, and the whole unit suite |
| No unrelated architectural change | **PASS** — five files, smallest maintainable change |

## Release preparation

| Criterion | Status |
|---|---|
| `versionName` | `2.4.0-rc.7` (repo convention omits the `v`; the tag would be `v2.4.0-rc.7`) |
| `versionCode` | `214` — the next integer after 213 |
| Changelog above the previous entry | **PASS** — `## v2.4.0-rc.7` above `## v2.4.0` |
| Documentation synchronized | **PASS** — `documentation-sync-report.md` |
| QA reports completed | **PASS** — 14 reports + fixture inventory + progress record |
| Remaining risks disclosed | **PASS** — below |
| Build + tests | **PASS** — `assembleDebug` and `testDebugUnitTest` both succeeded; 790 tests, 0 failures |

## Remaining risks

1. **The empty-result message was not observed on the device** — the scan-and-find path needs a query
   embedding first. Code-verified only.
2. **51 of 52 fixtures were not re-imported this round.** The reader changed only by *measuring* its stop,
   and the record counts are pinned by tests, but the full sweep was not repeated.
3. **Camera content scenarios remain blocked** (virtual scene cannot be posed over adb). No claim of
   camera-quality testing is made.
4. **A sheet whose records continue below a blank row is still not read as one table.** rc.7 tells the
   user; it does not guess. Products that need that layout should keep the block in its own worksheet.
5. **The QA tooling still contains two scripts that point at the pre-relocation directory** (QA-01). They
   were not edited, because they are prior QA artifacts; the rc.7 scripts do not depend on them.
