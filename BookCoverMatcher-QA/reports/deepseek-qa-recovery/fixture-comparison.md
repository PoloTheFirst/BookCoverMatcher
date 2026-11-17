# Fixture comparison - emulator UI vs manifest expectations

Every one of the 52 fixtures was imported through the real application on the Pixel 8 emulator.
Expected values come from `test-data/fixture-manifest.json`.

- **Verified match: 47**
- **Correctly rejected: 5**
- **Mismatch: 0**

Comparison fields: Comments tab vs `expected.comments`; Catalogue tab vs sum of `expected.tables[].records`;
Images "All" chip vs `expected.media_total`; "Unresolved" chip vs `expected.unresolved`.

## Matched

| Fixture | Category | Comments | Images | Records | Parse s |
|---|---|---|---|---|---|
| `valid_minimal` | valid | 1 | 1 | 1 | 9.5 |
| `valid_typical` | valid | 6 | 6 | 6 | 47.1 |
| `valid_multiple_sheets` | valid | 6 | 6 | 10 | 47.2 |
| `valid_multiple_records` | valid | 30 | 30 | 30 | 9.6 |
| `valid_note_images_mixed_formats` | valid | 6 | 6 | 6 | 9.3 |
| `valid_records_without_images` | valid | 3 | 2 | 4 | 9.2 |
| `valid_text_comments` | valid | 4 | 3 | 4 | 9.2 |
| `valid_floating_images` | valid | 4 | 6 | 4 | 9.2 |
| `layout_renamed_sheet` | worksheet-layout | 5 | 5 | 5 | 9.3 |
| `layout_sheet_order` | worksheet-layout | 4 | 5 | 5 | 9.2 |
| `layout_extra_columns_rows` | worksheet-layout | 6 | 6 | 6 | 9.6 |
| `layout_blank_rows` | worksheet-layout | 8 | 8 | 3 | 9.3 |
| `layout_blank_optional_cells` | worksheet-layout | 5 | 5 | 5 | 9.3 |
| `layout_trailing_junk` | worksheet-layout | 7 | 7 | 7 | 9.3 |
| `layout_hidden_sheets` | worksheet-layout | 4 | 4 | 5 | 9.2 |
| `layout_hidden_rows_cols` | worksheet-layout | 6 | 6 | 6 | 9.3 |
| `layout_reordered_columns` | worksheet-layout | 5 | 5 | 5 | 9.3 |
| `layout_notes_sheet_ignored` | worksheet-layout | 3 | 3 | 3 | 9.0 |
| `layout_two_tables` | worksheet-layout | 7 | 7 | 7 | 9.2 |
| `image_src_style_notes` | image-extraction | 3 | 3 | 3 | 9.2 |
| `image_formats_extra` | image-extraction | 5 | 5 | 5 | 9.3 |
| `image_tiny_and_large` | image-extraction | 5 | 5 | 5 | 9.3 |
| `image_two_per_note` | image-extraction | 3 | 4 | 3 | 9.1 |
| `image_broken_relid` | image-extraction | 3 | 3 | 3 | 9.1 |
| `image_missing_media` | image-extraction | 2 | 2 | 2 | 9.2 |
| `image_corrupt_bytes` | image-extraction | 2 | 2 | 2 | 9.1 |
| `image_shape_without_comment` | image-extraction | 1 | 2 | 1 | 9.0 |
| `image_comment_without_shape` | image-extraction | 2 | 1 | 2 | 9.1 |
| `image_floating_vs_note_same_cell` | image-extraction | 2 | 3 | 2 | 9.1 |
| `image_unreferenced_media` | image-extraction | 3 | 8 | 3 | 9.2 |
| `image_whitespace_case_vml` | image-extraction | 2 | 2 | 2 | 9.1 |
| `invalid_malformed_sheet_xml` | invalid | 1 | 1 | 0 | 9.1 |
| `invalid_missing_title_header` | invalid | 4 | 4 | 4 | 9.2 |
| `edge_unicode_titles` | edge-cases | 8 | 8 | 8 | 9.3 |
| `edge_whitespace` | edge-cases | 3 | 3 | 3 | 9.1 |
| `edge_long_text` | edge-cases | 3 | 2 | 3 | 9.1 |
| `edge_numeric_where_text` | edge-cases | 3 | 3 | 3 | 9.1 |
| `edge_formula_errors` | edge-cases | 3 | 3 | 3 | 9.2 |
| `edge_duplicate_records` | edge-cases | 6 | 6 | 6 | 9.5 |
| `edge_duplicate_headers` | edge-cases | 3 | 3 | 3 | 9.1 |
| `edge_empty_sheet` | edge-cases | 0 | 0 | 0 | 8.9 |
| `edge_empty_record_rows` | edge-cases | 4 | 4 | 4 | 9.2 |
| `edge_shared_strings_rich` | edge-cases | 3 | 3 | 3 | 9.1 |
| `perf_500_no_images` | performance | 0 | 0 | 500 | 8.9 |
| `perf_500_note_images` | performance | 500 | 500 | 500 | 9.2 |
| `perf_1000_mixed` | performance | 600 | 640 | 1000 | 9.2 |
| `perf_many_sheets` | performance | 13 | 13 | 13 | 9.3 |

## Correctly rejected (invalid by design)

- `invalid_not_a_zip`
- `invalid_no_workbook_part`
- `invalid_no_worksheets`
- `invalid_truncated_zip`
- `invalid_encrypted_container`

All five were discarded by the app's early-open validation: the file field stayed on "No file chosen"
and no catalogue was produced.

## Mismatches

None.

## Performance fixtures (measured on the emulator)

| Fixture | Stage s | Settle s | Records | Comments |
|---|---|---|---|---|
| `perf_1000_mixed` | 39.3 | 9.2 | 1000 | 600 |
| `perf_500_no_images` | 40.3 | 8.9 | 500 | 0 |
| `perf_500_note_images` | 39.1 | 9.2 | 500 | 500 |
| `perf_many_sheets` | 39.2 | 9.3 | 13 | 13 |

`Stage s` is picker navigation plus staging/early-open validation; `Settle s` is until the tab
counters stopped changing. Both include uiautomator overhead (~2.2 s per hierarchy dump), so they
are upper bounds, not parser timings.
