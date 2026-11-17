# Excel fixture validation — v2.4.0-rc.7

**Method:** `scripts/rc7_fixture_check.py` (written for this round, project paths only). It re-derives
everything from the files themselves and does not trust the manifest's own `validation` block:

1. file present at the manifest path, 2. size equals the manifest, 3. sha256 equals the manifest,
4. ZIP opens (or fails **as declared**), 5. `[Content_Types].xml` present, 6. `xl/workbook.xml` present,
7. the declared sheet parts exist, 8. media parts ≥ the images the fixture declares, 9. the three
unopenable archives fail and the two structurally-refused ones are refused.

Run: `BookCoverMatcher-QA\.venv\Scripts\python.exe scripts\rc7_fixture_check.py` → exit code **0**.

## Result

```
{"checked": 52, "passed": 52, "failed": 0}
```

| Check | Outcome |
|---|---|
| Files present, sizes and sha256 match the manifest | 52/52 |
| ZIP opens | 49/52 — the 3 that do not are `invalid_not_a_zip`, `invalid_truncated_zip`, `invalid_encrypted_container`, all **declared** unopenable |
| `[Content_Types].xml` + `xl/workbook.xml` | 50/52 — `invalid_no_workbook_part` omits the workbook part **by design** (refused fixture), `invalid_not_a_zip` is not a package at all |
| Sheet parts | 52/52 where a workbook part exists |
| Media parts vs declared images | all consistent; `image_unreferenced_media` and `perf_1000_mixed` carry more media than referenced images **by design** and are excluded from that comparison |
| Manifest vs disk (no orphans, nothing missing) | 52 entries ↔ 52 files |

Full per-fixture results: `reports/v2.4.0-rc.7/fixture-manifest.json`.

## Authentic workbook — untouched

| Item | Value |
|---|---|
| File | `test_excel/doujin.xlsx` (38.6 MB) |
| sha256 | `a5ad2ba78808c9aa8e57d7ffec64f0577d34d28d63eadb56ba952d79ac2a6aad` |
| mtime | 2026-10-31 06:40:37 |
| Matches the hash recorded by the previous engagement? | **Yes**, byte for byte |
| Modified by this round? | No |

`test_excel/` contains no other workbook; the two 40 MB workbooks the real-workbook tests look for
(`DoujinNew.xlsx`, `DoujinOld.xlsx`) are absent, which is why those 3 tests are skipped by design.

## Kotlin parser validation of the fixtures

The fixture corpus was exercised through the **real reader** in two ways this round:

* **In the application**, on the Pixel 8 AVD: `worksheet-layout/layout_blank_rows.xlsx` imported and
  read (staged label `layout_blank_rows.xlsx · 34 KB`, Catalogue `3 record(s) shown`). That is the
  fixture for the fixed defect; the remaining 51 were verified through the UI by the previous engagement
  against the same reader code, which this round did not change.
* **In the JVM suite**, the reader-level behaviour the fixtures encode is pinned by
  `CatalogueTruncationTest` (blank row ends the table; the rows below are counted) and by the existing
  `CatalogueTable`/`PipelineTest` cases. The prior engagement's `harness/QaParserHarness.java`
  (52 rows, all PASS) was **not** re-run; it is supporting evidence only.

## Fixture defects found: none

No fixture was malformed unintentionally, so nothing needed repair, and no corrected duplicate was
created. The three deliberately corrupt archives and the two structurally-refused ones are preserved as
negative tests.
