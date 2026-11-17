# Fixture completion report

## Verdict

The previous engagement's fixture corpus was **complete and sound**; it did not need regeneration.
This phase therefore consisted of independent re-validation plus one new fixture for matching.

## What existed and was re-verified

| Check | Result |
|---|---|
| Workbooks on disk | 52, matching the manifest exactly (no orphans, none missing) |
| SHA-256 vs manifest | 52/52 match |
| ZIP integrity | 49 open cleanly; 3 are intentionally unopenable (`invalid_not_a_zip`, `invalid_truncated_zip`, `invalid_encrypted_container`) |
| XML well-formedness | all parts parse except the deliberate `invalid_malformed_sheet_xml` |
| Sheet parts resolve | 52/52 where a workbook part exists |
| Media magic bytes | PNG/JPEG/GIF/BMP/WEBP all consistent with their extensions |
| Comment refs vs manifest | consistent |
| JVM parser harness (re-run) | 52/52 PASS, 0 problems |

Run: `scripts/deepseek_structure_check.py` -> `evidence/logcat/independent-structure.json`.

## New fixtures created by this engagement

| File | Purpose |
|---|---|
| `test-data/synthetic-images/camera/covers/*.png` | 5 original synthetic covers (Pillow, invented titles) |
| `test-data/synthetic-images/camera/scenarios/*.png` | 12 degraded/placed variants: angled, rotated, low-light, overexposed, blurry, obstructed, small-in-frame, two-covers, similar-cover, non-book, blank, front-facing |
| `test-data/matching/matching_covers.xlsx` | 5 records whose comment images are the synthetic covers, for graded matching |

`matching_covers.xlsx` was validated structurally (5 records, 5 PNG media, all parts resolve) before
use, and then verified through the application (staged as `matching_covers.xlsx · 85 KB`, 5 ranked
results).

## Repaired fixtures

**None.** No fixture failed validation, so nothing needed repair. The three deliberately corrupt
archives were preserved as negative tests, as the brief requires.

## Authentic workbook

`test_excel/doujin.xlsx` was never modified:
sha256 `a5ad2ba78808c9aa8e57d7ffec64f0577d34d28d63eadb56ba952d79ac2a6aad`, mtime 2026-10-31.

## Manifest accuracy

`test-data/fixture-manifest.json` remains the source of truth for expected values and was used as the
oracle for all 52 UI comparisons. Its `expected` blocks proved accurate for comments, records, media
totals and unresolved counts. One nuance was documented rather than "fixed": its `unreferenced_media`
field and the Images tab's "Unreferenced" chip count different things when floating pictures exist
(the chip folds floating pictures in), so that field was treated as informational, not as a pass/fail
oracle.
