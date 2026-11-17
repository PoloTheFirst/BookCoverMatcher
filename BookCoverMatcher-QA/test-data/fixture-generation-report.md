# BookCoverMatcher — Authentic-Format Excel QA Fixture Generation

**Report date:** 2026-11-02 · **App under test:** BookCoverMatcher 2.4.0
**Workspace:** `C:\Users\ASUS\AndroidStudioProjects\BookCoverMatcher-QA` (the app repo is untouched)

---

## 1. What was produced

| Deliverable | Location | State |
|---|---|---|
| Workbook structure analysis | `test-data/workbook-structure-analysis.md` | complete (Phases 1–4) |
| OOXML fixture builder library | `scripts/qa_fixtures.py` | 647 lines |
| Fixture matrix generator | `scripts/generate_fixtures.py` | 52 fixtures, `--out`, `--only` |
| Structural validator | `scripts/validate_fixtures.py` | independent Python mirror + `--merge` |
| JVM parser harness | `harness/QaParserHarness.java` | drives the **real Kotlin parser classes** |
| Fixture workbooks | `test-data/{valid,worksheet-layout,image-extraction,invalid,edge-cases,performance}/` | 52 files, 2,388,367 B total |
| Machine-readable manifest | `test-data/fixture-manifest.json` | per-fixture purpose, `expected`, `sha256`, both validations |
| Harness results | `harness/harness_results.json` | per-fixture PASS/FAIL + observed values |

## 2. Inputs and preservation

* Authentic workbook: `BookCoverMatcher\test_excel\doujin.xlsx` — **never modified**.
  sha256 `a5ad2ba78808c9aa8e57d7ffec64f0577d34d28d63eadb56ba952d79ac2a6aad`, 40,483,600 bytes, mtime 2026-10-31 (verified after generation).
* No file under `BookCoverMatcher\app\` or `test_excel\` has a modification time after 10:00 on 2026-11-02 (checked with `find -newermt`); this task wrote nothing to the app repo.
* No real user data is embedded in any fixture: the validator fails a fixture that contains authentic titles/authors (`Astolfo`, `淫乱少年`, `Ryan Polo`, `melongbooks`), and all 52 pass that check. Covers are synthetic PIL gradients.

## 3. Canonical synthetic format (derived from doujin.xlsx)

Fixtures replicate the authentic mechanisms, not just "a workbook with images":

* **Legacy cell-note images**: `xl/comments{N}.xml` (note text, `ref="D2"`) + `xl/drawings/vmlDrawing{N}.vml` note shapes anchored by `<x:Row>/<x:Column>` + image bytes referenced through `<v:fill o:relid>` / `<v:imagedata r:id>` resolved by `xl/drawings/_rels/vmlDrawing{N}.vml.rels`, or directly by `<v:fill src="../media/x.png">`. This is what `CommentImageScanner` + `VmlNoteParser` actually parse — no spreadsheet library emits it, so the builder is hand-written OOXML (zipfile + XML).
* **Catalogue layout**: doujin-style 24-column header (ブック名/作者/サークル名/…), hidden pivot-bearing sheets (`pivotTable` relationship), `PIVOT - *` names, sheet index positions mirrored in `valid_multiple_sheets`.
* **Floating pictures**: `xl/drawings/drawing{N}.xml` `xdr:pic` anchors → second extraction pipeline.
* Workbook-level features exercised: shared strings (incl. rich `<r>` runs, `xml:space="preserve"`), formulas with cached values and error values (`#DIV/0!`, `#VALUE!`), hidden sheets/rows/columns, duplicate cells, pivot rels, missing parts, truncated zips.

## 4. Fixture matrix — 52 fixtures

| Category | Count | Coverage highlights |
|---|---|---|
| valid | 8 | minimal; typical (authentic 24-col); 8-sheet pivot shape; 30 records; mixed PNG/JPEG/GIF/BMP/WEBP covers; floating+note pipelines together; text-only notes |
| worksheet-layout | 11 | renamed/hidden/reordered sheets; sheet order; two catalogues; blank rows & blank optional cells; trailing junk rows; hidden rows/cols; notes sheet ignored |
| image-extraction | 12 | two images per note; broken relid; missing media; corrupt image bytes; shape-without-comment; comment-without-shape; floating-vs-note on one cell; unreferenced media; `src=` style; tiny+large; upper-case/whitespace VML |
| invalid | 7 | not-a-zip; no workbook part; no worksheets; truncated zip; encrypted container; malformed sheet XML; no title header |
| edge-cases | 10 | unicode/CJK/emoji titles; whitespace-padded headers; 5k-char text; numerics where text expected; formula errors; duplicate records/headers; empty sheet; empty record rows; rich shared strings |
| performance | 4 | 500 rows no images; 500 note images; 1000 mixed rows; 12 worksheets |

2,207 catalogue records, 1,301 synthetic cover images in total.

## 5. Validation results (this run)

1. **Structural validator** (`validate_fixtures.py`): re-implements ZIP/OOXML structural checks, comment↔shape matching, CatalogueReader header/record rules, pivot skipping, title-header alias matching — **52/52 PASS**.
2. **JVM harness** (`QaParserHarness`): loads the **app's own compiled Kotlin classes** from `app/build/tmp/kotlin-classes/debug` (JBR 25, kotlin-stdlib 2.2.0) and runs the real pipeline per fixture: `CommentImageScanner.scan` → `WorkbookAnalyzer.analyze` (tables, detected title headers, coverage) → `WorkbookImageExtractor.requireWorkbook/plan/run`, comparing every value against the manifest `expected` block — **52/52 PASS**, 0 exceptions.
3. **Determinism**: full regeneration into a clean directory produced **52/52 byte-identical files** (fixed ZIP timestamps, seeded RNG everywhere). Per-fixture sha256 is recorded in the manifest.

### Parser behaviours pinned by the fixtures (discovered while asserting)

These are app behaviours the matrix now locks in; each has a fixture asserting it:

* Media counts as *referenced* **only** when associated with a comment: floating-drawing-only media, orphan note shapes, and floating-vs-note collisions all report as unreferenced (`media_total − referencedMediaCount`).
* Sheets carrying a `pivotTable` relationship are **structurally** excluded from catalogues (pivot sheets in `valid_multiple_sheets` have perfectly table-shaped data and still don't appear).
* Hidden/very-hidden sheets are still scanned for tables and comments.
* A second sheet with a shaped header (`COVERS`, `C108`) **does** become a second catalogue; `detected_title_headers` is per-table in workbook order.
* Malformed sheet XML does **not** throw: cells parsed before the error survive, `unreadableSheets` stays empty; a header-only prefix yields no table (no data row).
* VML matching is local-name based: `<V:shape>`, `<v:Fill o:RELID>`, whitespace in `<x:Row>` all resolve like the canonical spelling.
* Extraction dedupes per cell: with a floating picture and a note on the same cell, exactly one image is produced ("first picture per cell wins", floating first).
* Garbage/encrypted files fail at ZIP open — the scanner's `This file is not a readable .xlsx/.xlsm package.` message applies only to openable-but-unusable packages.

## 6. Regeneration and validation commands

All commands from `C:\Users\ASUS\AndroidStudioProjects\BookCoverMatcher-QA`, git-bash shell.
Prerequisites: the QA venv (`.venv`, Python 3.14, pillow) and an Android Studio build of the app (`app/build/tmp/kotlin-classes/debug` current).

```bash
# 1) Generate all 52 fixtures + fixture-manifest.json
.venv/Scripts/python.exe scripts/generate_fixtures.py --out test-data
# subset:  ... --only valid_typical,image_broken_relid
# clean dir check (determinism): --out "$LOCALAPPDATA/hermes/cache/scratch/fxrerun"

# 2) Structural validation (writes results into the manifest)
.venv/Scripts/python.exe scripts/validate_fixtures.py --out test-data

# 3) JVM harness against the real Kotlin parser
STDLIB="$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.2.0/fdfc65fbc42fda253a26f61dac3c0aca335fae96/kotlin-stdlib-2.2.0.jar"
CLASSES="C:/Users/ASUS/AndroidStudioProjects/BookCoverMatcher/app/build/tmp/kotlin-classes/debug"
"/c/Program Files/Android/Android Studio/jbr/bin/javac" -encoding UTF-8 \
    -cp "$CLASSES;$STDLIB" -d harness/out harness/QaParserHarness.java
"/c/Program Files/Android/Android Studio/jbr/bin/java" -Dfile.encoding=UTF-8 \
    -cp "harness/out;$CLASSES;$STDLIB" QaParserHarness \
    test-data/fixture-manifest.json > harness/harness_results.json

# 4) Merge harness verdicts into the manifest
.venv/Scripts/python.exe scripts/validate_fixtures.py --out test-data --merge harness/harness_results.json
```

After changing the parser in the app, rebuild the app (so `kotlin-classes/debug` refreshes) and re-run steps 2–4; only fixtures whose *expected behaviour* legitimately changed need edits to their `expected` block in `generate_fixtures.py`, then regenerate.

## 7. Limitations

* The JVM harness exercises the comment/image/catalogue pipeline headlessly; UI-layer code (Compose, file pickers, DB persistence) is out of scope — the "not a readable" UI string for garbage files is verified at the parser layer instead (failure at ZIP open).
* JVM harness relies on the debug build's compiled classes; if the app is not built, `harness/out` compile fails with "cannot find symbol".
* `doujin.xlsx` itself is intentionally not fed to the harness (40 MB; the app's own QA already covers it manually) — the fixtures reproduce its *mechanisms* at 0.2–800 KB each.
* Threaded comments (`xl/threadedComments/`) are reported by the scanner but not synthesized here (not present in the authentic workbook).
* BMP/WEBP coverage is structural (valid headers, real encoders); pixel-accurate cover appearance is not asserted — image *association*, not rendering, is under test.

## 8. Phase completion

Phases 1–3 (structure + parser analysis): re-verified on disk, preserved.
Phase 4 (canonical format): `workbook-structure-analysis.md`, preserved.
Phase 5+6 (matrix + generation): **complete — 52 fixtures, deterministic**.
Phase 7a (structural validation): **complete — 52/52**.
Phase 7b (JVM harness vs real parser): **complete — 52/52, 0 exceptions**.
Phase 8 (manifest + report + regen commands): **complete — this document + `fixture-manifest.json`**.
Integrity gate: authentic workbook hash re-verified; no app-repo file modified.
