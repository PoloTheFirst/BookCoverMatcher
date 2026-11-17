# BookCoverMatcher — Authentic Workbook Structure Analysis

Task: Authentic-Format Excel QA Fixture Generation (Prompt A) — Phase 4 deliverable.
Author: QA fixture pipeline. Date: 2026-11-02 (Asia/Shanghai).
App under test: BookCoverMatcher 2.4.0 (`app/build.gradle.kts` versionName `2.4.0`).

Provenance of this analysis: an earlier session performed the Phase 1–3 inspection but
terminated before writing any file (the QA workspace was found empty on 2026-11-02). All
findings below were therefore **re-derived from the authentic files and the current Kotlin
sources** by read-only scripts; nothing was taken on trust from the earlier session.

Legend:
- **[OBS]** — observed directly in the authentic workbook file (`doujin.xlsx`).
- **[KOT]** — found in the Kotlin production source (paths + line refs).
- **[INF]** — inference that still needs validation (validated where the harness allowed).

Authentic workbook integrity baseline (taken before any work, verified again afterwards):

```text
file    : C:\Users\ASUS\AndroidStudioProjects\BookCoverMatcher\test_excel\doujin.xlsx
size    : 40,483,600 bytes
mtime   : 2026-10-31 06:40:37 +0800
sha256  : a5ad2ba78808c9aa8e57d7ffec64f0577d34d28d63eadb56ba952d79ac2a6aad
```

---

## 1. Authentic workbook inventory

`test_excel/` contains exactly **one** workbook. **[OBS]**

| File | Size | Opens | Sheets | Notes |
|---|---|---|---|---|
| `doujin.xlsx` | 40,483,600 B | yes (ZIP valid, `testzip()` clean, 295 entries) | 8 (1 visible, 7 hidden) | the book catalogue |

The app's own error text names the supported formats: `.xlsx` / `.xlsm` only (a ZIP/OOXML
package that contains `xl/workbook.xml`); `.xls`, encrypted packages and anything else fail
with a user-visible message. **[KOT]** `core/excel/WorkbookImageExtractor.kt:228-235`
(`requireWorkbook`), `core/excel/comments/CommentImageScanner.kt:28-32`.

## 2. Worksheet structure and layout **[OBS]**

Workbook order (from `xl/workbook.xml`); rels are `/`-prefixed absolute targets
(`Target="worksheets/sheet5.xml"`, resolved relative to `xl/`):

| # | Name | state | Part | Dimension | Role |
|---|---|---|---|---|---|
| 1 | `PIVOT - BAR ` (trailing space!) | hidden | sheet1.xml | A4:E25 | pivot + chart sheet |
| 2 | `PIVOT - Tree` | hidden | sheet2.xml | A4:F80 | pivot + chart |
| 3 | `PIVOT - Pie` | hidden | sheet3.xml | A4:E15 | pivot + chart |
| 4 | `PIVOT - Area` | hidden | sheet4.xml | A4:AI16 | pivot + chart |
| 5 | **`LIST`** | **visible** | sheet5.xml | A1:X999 | **the catalogue table** |
| 6 | `DATA` | hidden | sheet6.xml | A1:S75 | per-year stats mini-table |
| 7 | `C108` | hidden | sheet7.xml | A1:D137 | event scratch table |
| 8 | `C109` | hidden | sheet8.xml | A1 (empty) | empty sheet |

`LIST` facts:
- Excel **table object** `LIST` + autoFilter over `A1:X125` (`xl/tables/table1.xml`), i.e.
  header row 1 + **124 data records** (rows 2–125). Declared dimension `A1:X999`.
- No merged cells, no hidden rows/columns, no blank rows inside the table.
- 2 external hyperlinks (melonbooks author searches) attached to author/circle cells.
- 248 formula cells: `YEAR(G..)` (shared formulas) and `REPT("★",P)&REPT("☆",5-P)` star
  strings with cached values.
- The heavy parts of the file: 165 media entries (40.2 MB), 125 note shapes, 7 chart/drawing
  parts, pivot caches, slicers, ink shapes, `xl/metadata.xml` + `xl/richData/` holding a
  single **leftover `#NAME?` rich value** (no local-image rich values are used **[OBS]** —
  `rdRichValueTypes.xml` has no image type).

## 3. Header row and column definitions **[OBS]**

Row 1 of `LIST` (columns A–X, all shared strings):

| Col | Header (as stored) | Type in data rows | Notes |
|---|---|---|---|
| A | 即売会 | text | event name |
| B | ジャンル | text | genre — alias for TAGS |
| C | 登場人物 | text | characters |
| D | **ブック名** | text | **title — carries the cover note on every row** |
| E | 作者 | text | author |
| F | サー circ名 → サークル名 | text | circle |
| G | 出版日付 | number, date-formatted serial (e.g. 43465) | date |
| H | 出版年 | formula `YEAR(Gn)`, cached number | derived |
| I | 年齢 | text | rating |
| J | サイズ | text | size |
| K | 種類 | text | kind |
| L | タイプ | text | type |
| M | 価格（稅込） | plain number | price |
| N | ページ数 | plain number | pages |
| O | 評価（スター） | formula string `t="str"`, cached `★★★★★` | stars |
| P | 評価(数字） | number | rating value |
| Q | 既読 | boolean `t="b"` | read flag |
| R | 購入日 | number, date-formatted serial | date |
| S | Twitter | text | |
| T | Twitter (user name) | text | may hold a hyperlink |
| U | Misskey | text | usually blank |
| V | Misskey (user name) | text | usually blank |
| W | 入手方法 | text | acquisition |
| X | 通し番号 | plain number | sequential unique id |

(F col header is `サークル名`; the space above is a typo in this report only — stored bytes
are exact: `即売会, ジャンル, 登場人物, ブック名, 作者, サークル名, 出版日付, 出版年, 年齢, サイズ, 種類, タイプ, 価格（稅込）, ページ数, 評価（スター）, 評価(数字）, 既読, 購入日, ツイッター, ツイッター (ユーザー名), ミスキー, ミスキー (ユーザー名), 入手方法, 通し番号`.)

## 4. Cell data types and representative formats **[OBS]**

- Text cells: `t="s"` shared strings (the dominant form; the file has a populated
  `xl/sharedStrings.xml`).
- Numbers: no `t` attribute, `<v>` raw decimal.
- Dates: raw serial numbers + a number-format style index (`s="1"`); stored value is e.g.
  `43465`. The app never rewrites them; a display layer formats them **[KOT]**
  (`CatalogueRecord.displayValues`, `WorkbookStyles`).
- Booleans: `t="b"` `<v>1</v>` → the app renders `"TRUE"`/`"FALSE"` **[KOT]** `SheetCells.kt:174-179`.
- Formulas: shared formulas with cached values (`<f t="shared" ...>...</f><v>2018</v>`), plus
  `t="str"` cached string formulas (stars). The app reads the **cached `<v>`**, never
  re-evaluates.
- Missing optional values: the cell element is simply absent (e.g. U2, V2 don't exist).
- Blank header cells: none in `LIST`, but the parser explicitly supports them **[KOT]**.

## 5. Embedded-image storage mechanism **[OBS + KOT]**

**Every cover is a picture used as the FILL of a legacy cell-note shape.** This is the
mechanism the fixtures must reproduce:

1. `xl/comments1.xml` — 125 comments, all in **column D** (D2..D126), all **text-empty**:
   `<comment ref="D2" authorId="0" shapeId="0" xr:uid="{...}"><text/></comment>` —
   the note exists only to host a picture. Author list has one author (redacted).
2. `xl/drawings/vmlDrawing1.vml` — 125 `v:shape type="#_x0000_t202"` shapes,
   `x:ClientData ObjectType="Note"`, each with **zero-based** `<x:Row>`/`<x:Column>`
   (Column 3 = D). 124 shapes carry the picture as a frame fill:
   `<v:fill o:relid="rIdN" o:title="<book title>" color2="infoBackground [80]" recolor="t" type="frame"/>`;
   one shape (row 126) has **no fill** (an empty leftover note).
3. `xl/drawings/_rels/vmlDrawing1.vml.rels` — `rIdN → ../media/imageN.jpeg|png` (124 rels).
4. `xl/worksheets/sheet5.xml` wires them: `<drawing r:id>` (floating drawing) **and**
   `<legacyDrawing r:id>`; the sheet rels declare `comments`, `vmlDrawing`, `table`,
   `drawing`, `hyperlink`×2, `slicer`, `printerSettings`.
5. Media: 165 files (`106 JPEG`, `59 PNG`), long edge min/median/max = 3 / 900 / 3200 px;
   includes 3×3 and 20×20 px placeholder covers and a 2266×3200 JPEG.
   **41 media files are referenced by nothing** (orphans) — the parser reports them as
   UNREFERENCED rather than erroring **[KOT]** `ImageIndex.kt`.

Exactly one note shape per cell; one media per note (no multi-image note in the authentic
file); no note↔media sharing; `o:title` mirrors the book title.

Ordinary floating pictures: `LIST`'s drawing part (`drawing5.xml`) contains 84 anchors but
**0 `<xdr:pic>`** — the anchors are shapes/charts (e.g. a 🔞 autoshape) **[OBS]**. So in the
authentic workbook, *all* covers travel via note fills; the floating path exists in the
parser but is unused here. **[KOT]** `WorkbookImageExtractor.kt:178-223` supports floating
pictures of the **first worksheet only**.

## 6. Image-to-record association **[KOT]**

Two independent pipelines consume the mechanism above; fixtures must satisfy both:

**A. Notes browser** — `CommentImageScanner.scan()` (`comments/CommentImageScanner.kt`):
- comments XML is authoritative for the cell; the VML Note shape (matched by exact
  zero-based `Row`/`Column`) supplies image refs only;
- ref kinds: direct `src=` (resolved against the drawing dir; external URLs rejected)
  preferred over relationship ids (`o:relid` / `r:id` on `fill` **or** `imagedata`);
- states: `WITH_IMAGES` / `TEXT_ONLY` / `UNRESOLVED_IMAGE` (never a silent association);
- diagnostics (warnings, not failures): second note shape at the same cell is ignored;
  unmatched shape → "no matching comment"; comment without VML → text shown without images;
  missing comments part / missing VML → diagnostic; unresolved refs keep a `detail`;
- a second `<v:fill>` on the same shape yields a **second image** (refs map dedupes only
  identical media) **[KOT]** `VmlNoteParser.kt:60-72`, `CommentImageScanner.kt:229-241`;
- limits (`CommentScanLimits`): 5000 comments, 1500 media listed, 32 MB media part, 16 MB XML.
- `WorkbookAnalyzer.analyze()` then joins scans with tables; sheets hosting pivot tables are
  skipped as catalogues (structural, via `pivotTable` relationship) — matching the authentic
  file where the four PIVOT sheets hold chart data.
- `ImageIndex`: media referenced by a verified comment on a **table row** → `LINKED`
  (with `CatalogueHit` = that record); media nothing references → `UNREFERENCED`;
  comment refs that fail to resolve → `UNRESOLVED` rows.

**B. Cover-catalog extractor** — `WorkbookImageExtractor.plan/run` (+ `CatalogBuilder`):
- scans **every** `xl/drawings/vmlDrawing<N>.vml` (regex, not relationship-driven), keeps
  `<v:shape>` blocks that have `<x:Row>`+`<x:Column>` **inside the user-chosen CellRange**;
- picture ref via 4 regexes: `v:fill/@o:relid`, `v:imagedata/@r:id`, `v:fill/@r:id`,
  `v:imagedata/@o:relid` — **a `src=`-only shape is NOT picked up here** (divergence with
  pipeline A, documented as fixture `image_src_style_notes`);
- rels read from `xl/drawings/_rels/<name>.rels`; media must exist in the ZIP;
- floating pictures (first worksheet's drawing, `Config.EXTRACT_FLOATING_IMAGES = true`,
  default) come first; **first picture per cell address wins** (floating before comment-fill);
  floating capped at `MAX_EXTRACTED_IMAGES = 300`;
- a picture whose `decode` returns null (undecodable bytes) is dropped, and the cell is
  re-available for a later candidate (unlike the browser model);
- cell key = `columnLetter(col) + (row+1)` — note: zero-based `x:Row` is trusted directly.
- Image MIME: magic bytes first (PNG/JPEG/GIF/BMP/WEBP), extension as fallback
  **[KOT]** `ImageMime.kt`. Actual decoding on device is Android `BitmapFactory`.

**C. Catalogue table detection** — `CatalogueReader` (+ `ColumnMappings`, `CatalogueField`):
- header row = best row among the first **12**: most non-empty, ≤40-char, not-all-digits,
  distinct cells; needs ≥3; all columns between first and last header belong to the table
  (blank header cells stay as columns);
- records: rows below header; a row whose values are all empty **stops the scan once at
  least one record exists** (blank rows truncate the table); a repeated header row is
  skipped; cap 5000 records; sheet scan cap 20 000 rows × 512 cols;
- nothing is hardcoded to a sheet name or column index: title/author/circle/genre/date
  are **aliases over the workbook's own header text** (`ブック名`, `作者`, `サークル名`→`サークル`,
  `ジャンル`, `出版/日付/年月`…); unmapped/duplicated headers are issues, never guesses;
  TITLE unmapped ⇒ records cannot be identified (blocking issue);
- values are trimmed on read; booleans become `TRUE`/`FALSE`; error cells (`t="e"`) keep
  raw text like `#NAME?`; **cell images are irrelevant here — an image belongs to a record
  only through a verified legacy comment on the same worksheet row** (`ImageIndex`).

## 7. Differences between authentic workbooks **[OBS]**

Only one authentic workbook exists, so cross-file variance could not be observed. Intra-file
variance that fixtures must respect: hidden vs visible sheets; pivot sheets vs the data
sheet; text-empty notes vs one fill-less leftover note; rich-text-free shared strings;
orphan media; a dimension (`A1:X999`) far larger than the real data range (`A1:X125`).

## 8. Kotlin parser expectations — summary for fixture design **[KOT]**

1. Package must be a readable ZIP containing `xl/workbook.xml` + ≥1 worksheet rel.
2. Sheet order and workbook-order rels matter for "first worksheet" (floating extraction).
3. Headers are **text-keyed, position-independent**, and compared trimmed; column order
   may be shuffled without loss; a missing `ブック名`-family header leaves TITLE unmapped.
4. A blank row after the first record truncates the record list.
5. Comment cells are addressed by the comments XML `ref`; VML shapes by zero-based
   `<x:Row>/<x:Column>`; **they must agree cell-for-cell** (exact match, or diagnostic).
6. `x:Row` zero-based vs Excel's 1-based row is the classic authoring trap: the VML anchor
   for comment `D2` is `<x:Row>1</x:Row><x:Column>3</x:Column>` (authentic file does this).
7. Unsupported/ignored by design: threaded comments (reported, never shown as note images),
   pivot sheets as catalogues, formulas (cached values only), external/URL image refs
   (rejected as UNRESOLVED with detail), `src=`-only images for the extractor pipeline.

## 9. Supported vs unsupported workbook features (as implemented)

Supported end-to-end: legacy note comments with text (`<t>` runs, phonetic `rph` skipped);
note-fill images via `o:relid`/`r:id` on `v:fill` or `v:imagedata` (browser) and via
`o:relid`/`r:id` (extractor); direct `src=` paths (browser only); multiple images per note
(browser only); shared/inline strings, numbers, booleans, error text, cached formula values;
internal & external hyperlinks attached per column; hidden sheets/rows (read anyway);
PNG/JPEG/GIF/BMP/WebP media by sniffing.

Not supported (by design): encrypted/OLE packages, `.xls`, threaded comments as note images,
drawing-anchored pictures on sheets other than the first, image-in-cell rich values,
live formula evaluation, `src=` note images for the extractor, comments >5000 / media >1500
listed (truncated flag), media part >32 MB, XML part >16 MB.

## 10. Risks and unresolved questions

- **[INF, validated]** Extraction depends on regexes over raw VML; well-formed but unusual
  whitespace/case must still parse — validated by `image_whitespace_case_vml` fixture.
- **[INF, validated]** Extractor trusts `x:Row`/`x:Column` without consulting comments XML;
  a shape with coordinates but no comment is extracted by pipeline B while pipeline A
  reports it as a diagnostic. Fixture `image_shape_without_comment` pins this down.
- **[INF]** Real-device image decode caps (`MAX_BUFFER_DECODE=640` long edge for the
  catalog; Android `inSampleSize`) cannot be exercised on the JVM harness; fixtures
  therefore include oversized covers (2000×2800) for on-device QA, marked PARTIAL in
  parser validation.
- The earlier (lost) session reached the same mechanism conclusions per its transcripts,
  including the `v:fill` (not `imagedata`) form — consistent with this re-derivation.
- Password protection: the app cannot decrypt; a synthetic encrypted *container* is not
  reproducible without Microsoft tooling, so `invalid_encrypted_container` emulates the
  observable precondition (non-ZIP OLE-magic file) — honestly labelled.

## 11. Canonical synthetic workbook template

Generated by direct OOXML assembly (openpyxl/xlsxwriter cannot author note-fill images; the
generator is a small, dependency-light `zipfile`+string writer; the real workbook proves the
target shape). Every "valid" fixture is an instance of this template:

```text
[Content_Types].xml          Default: xml, rels, vml, png, jpeg, jpg  + workbook/sheet1/comments1 overrides
xl/workbook.xml              <sheets><sheet name sheetId r:id/>...</sheets>  (state="hidden" where asked)
xl/_rels/workbook.xml.rels   rIds → worksheets/sheetN.xml, sharedStrings.xml (absolute 'worksheets/...' style like the authentic file)
xl/sharedStrings.xml         all text cells are t="s" (like the authentic file)
xl/worksheets/sheetN.xml     dimension, rows, cells; <sheetData>; optional <drawing r:id/> + <legacyDrawing r:id/>
xl/worksheets/_rels/sheetN.xml.rels   → ../comments1.xml, ../drawings/vmlDrawing1.vml [, ../drawings/drawing1.xml]
xl/comments1.xml             authors/commentList; <comment ref="Dn" authorId="0"><text/></comment>
xl/drawings/vmlDrawing1.vml  v:shape t202 per note: <v:fill o:relid="rIdN" o:title="…" type="frame" recolor="t"/>,
                             ClientData ObjectType="Note" with zero-based <x:Row>/<x:Column>
xl/drawings/_rels/vmlDrawing1.vml.rels  rIdN → ../media/imageN.png|jpeg
xl/drawings/drawing1.xml     only when the fixture has floating pictures: twoCellAnchor > xdr:pic > a:blip r:embed
xl/media/imageN.*            PIL-generated synthetic covers (gradients + title text + vol number)
```

Synthetic records: fictional doujin-style entries (fabricated titles/circles/events,
e.g. `合成テストブック Vol.N`), never data copied from `doujin.xlsx`. Covers generated with
Pillow (deterministic seed). Sheet named `LIST`, title column `D`, note on `D<row>` —
mirroring the authentic layout; layout fixtures vary this deliberately.

## 12. Planned fixture matrix

Implementation: 45 fixtures. Category codes A–E from the task spec. `V=` expected verdict
for the JVM parser harness; `n/a` = structurally validated only. Full per-fixture details,
deliberate deviations and validation results: `fixture-manifest.json` +
`fixture-generation-report.md` (Phase 8).

| Fixture | Category | Deliberate content | Expected app behavior |
|---|---|---|---|
| valid_minimal | A | 3 header cols (ブック名/作者/サークル名), 1 record, 1 note PNG | table read; 1 record; 1 comment WITH_IMAGES; extractor finds 1 |
| valid_typical | A | all 24 cols/row types incl. dates, booleans, shared formulas, hyperlink | matches authentic structure; V=PASS |
| valid_multiple_sheets | A/B | 4 sheets (2 hidden pivots-like, LIST visible, hidden notes sheet) | LIST detected; pivot-like sheet not treated as catalogue only when pivot rel present (fixtures w/o pivot rels may be read as tables — documented) |
| valid_multiple_records | A | 30 records, all with note images | V=PASS |
| valid_note_images_mixed_formats | A/C | PNG+JPEG fills; 20×20, 600×900, 1600×2266 covers | V=PASS |
| valid_records_without_images | A | records; 1 text-only note; 1 comment-less record | TEXT_ONLY diagnostic path; coverage 1/… |
| valid_text_comments | A | notes with real multi-run text + one image | text preserved, runs concatenated |
| valid_floating_images | A/C | first-sheet drawing with 2 anchored pics + note fills | floating first, first-per-cell dedup |
| layout_renamed_sheet | B | table sheet named カタログ | identical behavior (name-independent) |
| layout_sheet_order | B | table sheet second of two | notes scanned on all sheets; floating only from first |
| layout_extra_columns_rows | B | 4 unused right cols, 10 trailing rows | records unaffected |
| layout_blank_rows | B | blank row between records | **record list stops at the blank row** (parser truncation) |
| layout_blank_optional_cells | B | blank U/V style cells | empty values, no shift |
| layout_trailing_junk | B | dimension A1:AZ500, junk cell at AZ500 | 7 records; junk outside columns ignored |
| layout_hidden_sheets | B | LIST hidden | sheet listed as hidden; still scanned |
| layout_hidden_rows_cols | B | hidden data row + hidden col | hidden row still read (documented) |
| layout_reordered_columns | B | column order shuffled | aliases still resolve |
| layout_notes_sheet_ignored | B | 2-cell "sheet of pictures" | not a catalogue (<3 headers) |
| layout_two_tables | B | two sheet tables each with own notes/comments parts | comments matched per-sheet per-cell |
| image_src_style_notes | C | note images via `<v:fill src="../media/x.png">` | browser RESOLVED; extractor finds NONE (regex needs relid) |
| image_formats_extra | C | GIF/BMP/WebP note images | MediaBytes/ImageMime format labels; browser shows |
| image_tiny_and_large | C | 8×8 and 2000×2800 note covers | resolves; device decode capped (documented) |
| image_two_per_note | C | shape with two `v:fill`s → two media | browser: 2 media; extractor: first |
| image_broken_relid | C | fill o:relid not in vml rels | UNRESOLVED_IMAGE, detail "not declared" |
| image_missing_media | C | rels target absent from zip | UNRESOLVED_IMAGE, detail "no part" |
| image_corrupt_bytes | C | media exists, bytes are garbage | browser RESOLVED (existence); extractor decode→drop |
| image_shape_without_comment | C | note shape at empty cell, no comment | browser diagnostic "no matching comment"; extractor still extracts (range-based) |
| image_comment_without_shape | C | comment, shape removed | TEXT_ONLY + warning "no VML drawing"? (no: sheet has VML; warning only when parts.vmlParts empty → silent TEXT_ONLY) — validated |
| image_floating_vs_note_same_cell | C | floating pic + note fill, same cell | floating wins in extractor; browser keeps note association |
| image_unreferenced_media | C/E | 5 extra unreferenced media | 5 UNREFERENCED rows, no errors |
| invalid_not_a_zip | D | random bytes | Failure "not a readable .xlsx/.xlsm package" |
| invalid_no_workbook_part | D | zip w/o xl/workbook.xml | Failure / requireWorkbook error |
| invalid_no_worksheets | D | workbook.xml with empty sheets | Failure "no worksheets"/"no readable worksheets" |
| invalid_truncated_zip | D | half-written workbook | Failure (bad ZIP) — graceful |
| invalid_encrypted_container | D | OLE-magic non-ZIP file (encryption proxy) | Failure, not a crash |
| invalid_missing_title_header | D | table without ブック名 alias | table read; TITLE unmapped ⇒ REQUIRED_UNMAPPED issue |
| edge_unicode_titles | D | JP/CN/KR/emoji/XML-escapes/RTL | text preserved exactly (trimmed) |
| edge_whitespace | D | headers ` ブック名 `, fullwidth space, cell padding | header trim-match works; values trimmed |
| edge_long_text | D | 3000-char title | preserved (headers >40 chars lose header-detection eligibility — documented) |
| edge_numeric_where_text | D | number in title/circle; bool in title | values become "12345"/"TRUE" strings |
| edge_formula_errors | D | t="e" #NAME?/#DIV0; formula w/o cached value | error text kept; uncached → empty |
| edge_duplicate_records | D | 3 identical rows | all kept (parser does not dedupe) |
| edge_duplicate_headers | D | two ブック名 columns | detection picks first; profile resolve would flag AMBIGUOUS |
| edge_empty_sheet | D | one empty worksheet | no table; scan succeeds with 0 comments |
| edge_empty_record_rows | D | rows with blank title, other cells filled | records kept (only all-empty row stops) |
| edge_shared_strings_rich | D | multi-run rich shared strings | runs concatenated in order |
| perf_500_no_images | E | 500 records, 0 images | scan/analysis under limits |
| perf_500_note_images | E | 500 records + 500 small PNG notes | 500 LINKED; comment scan < 5000 limit |
| perf_1000_mixed | E | 1000 records, 600 mixed-fmt images, 40 orphans | counts exact |
| perf_many_sheets | E | 12 sheets (3 tables, notes on 2) | all sheets enumerated in order |

Regeneration commands (also in `fixture-generation-report.md`):

```bat
cd /d C:\Users\ASUS\AndroidStudioProjects\BookCoverMatcher-QA
.venv\Scripts\python.exe scripts\generate_fixtures.py           --out test-data
.venv\Scripts\python.exe scripts\validate_fixtures.py           --out test-data
tools\run_parser_harness.cmd                                    :: JVM harness (see report §harness)
```
