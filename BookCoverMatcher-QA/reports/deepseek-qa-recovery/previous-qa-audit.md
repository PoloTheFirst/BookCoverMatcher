# Previous QA Audit — deepseek-qa-recovery

**Audit date:** 2026-11-02
**Auditor:** recovery engagement (independent of the original QA run)
**Workspace audited:** `C:\Users\ASUS\AndroidStudioProjects\BookCoverMatcher-QA`
**App repo:** `C:\Users\ASUS\AndroidStudioProjects\BookCoverMatcher` (branch `dev`, commit `e751450`)

---

## 0. Headline finding

The previous engagement's **Excel fixture generation is real and holds up under independent
re-validation** (52 fixtures, hashes, OOXML structure, and a JVM harness driving the app's own
compiled parser classes).

The previous engagement's **in-application testing never happened**. Its own sweep log records
**0 successful fixture imports out of 92 attempts**: every run failed in the UI driver before any
workbook was opened. The "52/52 PASS" figure describes parser-harness runs, not the Android app.

The brief's premise ("completed Phases 1–3, stopped during Phase 4") is therefore **half right and
half wrong**: the fixture work went *further* than Phase 3, while the application testing that the
report implied was completed produced **no usable evidence at all**.

---

## 1. Inventory (what is actually on disk)

| Area | Path | Files | Notes |
|---|---|---|---|
| Fixture workbooks | `test-data/{valid,worksheet-layout,image-extraction,invalid,edge-cases,performance}/` | 52 `.xlsx` | matches the claimed matrix exactly |
| Structure analysis | `test-data/workbook-structure-analysis.md` | 1 | 349 lines, 12 sections |
| Fixture manifest | `test-data/fixture-manifest.json` | 1 | 87,646 B, 52 entries |
| Generation report | `test-data/fixture-generation-report.md` | 1 | 114 lines |
| Generators / validators | `scripts/qa_fixtures.py`, `generate_fixtures.py`, `validate_fixtures.py`, `fixture_sweep.py`, `qa_driver.py`, `probe_*.py` | 8 `.py` | |
| JVM parser harness | `harness/QaParserHarness.java`, `harness/harness_results.json`, `harness/out/*.class` | 3 | 52 result rows |
| Previous reports | `reports/initial-qa/fixture-inventory.md` | **1** | the *only* previous report document |
| Screenshots | `screenshots/initial-qa/{excel-import,image-extraction,interface}/` | 8 `.png` | |
| Logcat / UI dumps | `logcat/initial-qa/` | 5 | 2 UI dumps, 1 debug png, sweep jsonl + progress log |
| App-data baseline | `baseline/initial-qa/` | 3 | prefs xml, wishlist json, app-data txt |
| Screen recordings | `videos/initial-qa/` | **0** | directory exists, empty |
| Python env | `.venv/` | — | Python 3.14, Pillow 13.0.0 (no numpy) |

No files were deleted, moved, or rewritten during this audit. Every previous artifact referenced
below is still in place.

---

## 2. Claims vs. verified reality

| Claim in previous material | Verified? | Evidence |
|---|---|---|
| 52 fixtures generated | **Yes** | 52 `.xlsx` on disk; 52 manifest entries; no orphan files |
| Per-fixture sha256 recorded and correct | **Yes** | re-hashed all 52 — 0 mismatches |
| Manifest contains every file, no strays | **Yes** | set of manifest paths == set of `.xlsx` on disk |
| Structural validation 52/52 PASS | **Yes, with nuance** | independent OOXML validator: 49/52 fully clean; the 3 "failures" are the three intentionally corrupt archives (`invalid_not_a_zip`, `invalid_truncated_zip`, `invalid_encrypted_container`) — correct for negative fixtures |
| JVM parser harness 52/52 PASS | **Claim is supported by the artifact** | `harness/harness_results.json` has 52 rows, all `PASS`; harness drives `app/build/tmp/kotlin-classes/debug` |
| Deterministic regeneration (byte-identical) | **Not re-tested** | would require a full regeneration; not attempted to avoid disturbing the fixture set |
| Fixtures exercised in the application | **NO — false by the record** | see §3 |
| Screenshots prove import worked | **NO** | the 8 screenshots show the picker / home screen, not imported records |
| Phases 1–3 complete, stopped at Phase 4 | **Inaccurate** | fixture generation completed through its own Phase 8; application testing produced zero passes |

---

## 3. The in-app sweep: what the log actually says

`logcat/initial-qa/fixture-sweep.jsonl` — 92 rows (more than 52 because of retries):

```
EXCEPTION   : 7
DRIVER-FAIL : 84
MISMATCH    : 1
PASS        : 0
```

Every `DRIVER-FAIL` row carries `"pick": "Choose file button not found"` (83) or
`"picker did not open"` (1). The `EXCEPTION` rows are the driver crashing at
`pick step failed: QA folder (pattern ^QA$)`.

Root cause of that failure is **in the harness, not the app**:

1. The fixtures had **never been copied to the emulator**. `/sdcard/Documents` was empty when this
   audit started; there was no `QA` directory to find.
2. The old driver scrolled a fixed number of times and then looked for a label once, with no
   re-read of the view hierarchy.
3. The old picker path tapped a grid card centre, which overlaps the per-card preview button, so
   nothing was selected.

Consequence: **no fixture was ever imported through the application by the previous engagement.**
Excel import, image extraction, and catalogue rendering were all untested at the UI level.

The one `MISMATCH` row (`valid_note_images_mixed_formats … 'images UI 6 != expected None'`) is a
comparison against a manifest field that does not exist, not an app defect.

Corroborating screenshot `logcat/initial-qa/debug-pick-miss.png` shows the picker stranded at a
folder with no files.

---

## 4. Classification of existing work

### 4.1 Verified and directly reusable
- All 52 fixture workbooks (hashes, OOXML integrity, media/comment structure independently confirmed).
- `test-data/workbook-structure-analysis.md` — accurate description of the authentic mechanism
  (legacy VML note-fill images, 24-column doujin layout, pivot-sheet exclusion).
- `test-data/fixture-manifest.json` — internally consistent, machine-readable, usable as the
  expected-value source for this engagement.
- `harness/QaParserHarness.java` + results — valuable JVM-level evidence.
- `baseline/initial-qa/` app-data snapshots — useful for persistence comparisons.

### 4.2 Completed but insufficiently validated
- **JVM harness results.** They were produced by the same agent that wrote the fixtures, against
  parser classes that have since been rebuilt. Treated as *supporting* evidence only until re-run.
- **Manifest `expected` blocks.** Good, but several encode parser quirks (blank-row stop,
  per-cell dedupe) that only the UI can confirm are user-visible as described.

### 4.3 Incomplete
- Any report covering Excel import, image extraction, camera, matching, wishlist or UI — **none exist**.
- `test-data/fixture-generation-report.md` is complete for fixtures but claims nothing about the app.
- No `bug-register`, no `regression` artifacts, no recordings.

### 4.4 Invalid
- `logcat/initial-qa/fixture-sweep.jsonl` as evidence of application behaviour — it is evidence of
  harness failure only. Retained, not deleted, and explicitly relabelled in this report.
- `reports/initial-qa/fixture-inventory.md`'s "Execute: yes (full)" column — those executions never
  occurred.

### 4.5 Missing
- Fixtures never installed on the device (fixed by this engagement).
- Camera test images (none existed anywhere in the workspace).
- Any emulator baseline, environment baseline, test plan, or final summary.
- Screen recordings (`videos/initial-qa/` empty).

### 4.6 Unverifiable
- The determinism claim (byte-identical regeneration).
- Whether the parser harness was run against the *same* classes as the currently installed APK.

---

## 5. Fixture inventory cross-check (independent)

Run: `scripts/deepseek_structure_check.py` → `evidence/logcat/independent-structure.json`

| Check | Result |
|---|---|
| ZIP opens + CRC | 49/52 open; 3 fail by design (`not_a_zip`, `truncated_zip`, `encrypted_container`) |
| XML well-formedness | all parts parse except the deliberate `invalid_malformed_sheet_xml` sheet |
| Sheet parts resolve | 52/52 (where a workbook part exists) |
| Media magic-byte sniff | no extension/byte mismatches; PNG/JPEG/GIF/BMP/WEBP all recognised |
| Comment refs vs manifest | consistent |
| Manifest media count vs actual parts | consistent (incl. `perf_1000_mixed` 640 parts / 600 referenced) |

**Conclusion: the fixture corpus is trustworthy and is reused as-is.** Only one fixture category was
extended by this engagement: camera images (which had no prior work).

---

## 6. What the recovery must still do

1. Put fixtures on the device and drive the real UI (this engagement's `deepseek_driver.py` /
   `deepseek_sweep.py`).
2. Verify per-fixture outcomes against the manifest — records, comments, images, unresolved refs.
3. Test camera, permissions, capture, crop, auto-scan on the emulator.
4. Test local matching, wishlist, persistence, navigation.
5. Explore beyond the scripted cases.
6. Fix confirmed defects, add regression tests, re-verify on the emulator.
7. Produce the reports that the previous engagement never wrote.

See `recovery-plan.md`.
