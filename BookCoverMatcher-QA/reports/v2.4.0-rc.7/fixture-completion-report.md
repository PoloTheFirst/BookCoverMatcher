# Fixture completion report — v2.4.0-rc.7

## Phase status: continued from Phase 4, already complete

The assignment assumed fixture generation stopped during Phase 4. The evidence says otherwise: the
first engagement produced the full corpus, and the recovery engagement re-validated it and added the
camera/matching material. Both are preserved; nothing was regenerated, replaced or deleted.

| Phase | State found | What this round did |
|---|---|---|
| 1 — authentic workbook analysis | `test-data/workbook-structure-analysis.md` (23.8 KB, VML note-fill images, 24-column layout, pivot-sheet exclusion) | Read; not rewritten |
| 2 — fixture design | manifest with 52 entries and expected blocks | Read; used as the oracle |
| 3 — generation | 52 `.xlsx` across `valid/`, `worksheet-layout/`, `image-extraction/`, `invalid/`, `edge-cases/`, `performance/` + `matching/matching_covers.xlsx` | Counted; not regenerated |
| 4 — validation | prior: structural + JVM harness + emulator sweep | **Independently re-validated 52/52** with the new `scripts/rc7_fixture_check.py` |

## Corpus as verified in this round

| Category | Files | Coverage |
|---|---|---|
| `valid/` | 8 | minimal, typical, multiple records, multiple sheets, floating images, note images in mixed formats, text-only comments, records without images |
| `worksheet-layout/` | 11 | blank rows, blank optional cells, extra columns/rows, hidden rows/columns, hidden sheets, notes sheet, renamed sheet, reordered columns, sheet order, trailing junk, two tables |
| `image-extraction/` | 12 | broken relId, comment without shape, corrupt bytes, floating vs note on the same cell, extra formats, missing media, shape without comment, `src`-style notes, tiny+large, two images per note, unreferenced media, whitespace/case in VML |
| `invalid/` | 7 | not-a-zip, no workbook part, no worksheets, truncated zip, encrypted container, malformed sheet XML, missing title header |
| `edge-cases/` | 10 | duplicate headers, duplicate records, empty record rows, empty sheet, formula errors, long text, numeric-where-text, rich shared strings, Unicode titles, whitespace |
| `performance/` | 4 | 500 rows no images, 500 note images, 1000 mixed, many sheets |
| `matching/` | 1 | `matching_covers.xlsx` — 5 records whose note images are the 5 synthetic covers |
| `synthetic-images/` | 17 | 5 covers + 12 camera scenarios (angled, rotated, low light, overexposed, blurry, obstructed, small-in-frame, two covers, similar cover, non-book, blank, front-facing) |

## Added by this round

| File | Purpose |
|---|---|
| `reports/v2.4.0-rc.7/fixture-manifest.json` | The rc.7 inventory: the 52 manifest entries plus this round's fresh per-fixture check result (size, sha256 match, ZIP, part counts, status) |
| `scripts/rc7_fixture_check.py` | The independent validator used to produce it (project paths, no external directory) |

## Not done, and why

* **Regeneration determinism** (byte-identical re-generation) was not attempted: it would rewrite the
  fixture set the prior QA evidence refers to, and the assignment forbids disturbing validated fixtures.
* **New fixture categories** were not needed: every category the brief lists already had at least one
  fixture, and the two defects of this round are covered by existing fixtures
  (`worksheet-layout/layout_blank_rows.xlsx` for XLS-01; the canonical layout is exercised by
  `valid_*` for UI-01).
* `.xlsm` still has no fixture (documented previously as a gap; unchanged).
