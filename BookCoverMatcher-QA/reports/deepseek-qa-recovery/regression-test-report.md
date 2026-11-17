# Regression test report

**No application source was modified**, so this is a confirmation pass rather than a
fix-verification pass.

## Re-run after harness corrections

The first sweep attempt produced three driver failures (`layout_trailing_junk`, `layout_hidden_sheets`,
`layout_hidden_rows_cols`) caused by harness defects, plus one emulator wedge. After fixing the
driver:

| Fixture | Re-run result |
|---|---|
| `layout_trailing_junk` | Passed - 7 comments / 7 images / 7 records |
| `layout_hidden_sheets` | Passed - 4 comments / 4 images / 5 records |
| `layout_hidden_rows_cols` | Passed - 6/6/6 |
| `invalid_encrypted_container` | Rejected correctly (after the emulator restart) |

## Full pass

All 52 fixtures were then re-run/confirmed in one sweep: **47 verified matches, 5 correct
rejections, 0 mismatches** (`fixture-comparison.md`).

## Other regressions checked

| Area | Result |
|---|---|
| App launch after reinstall | Passed, 0 fatal exceptions |
| Camera permission deny -> grant cycle | Passed, preview recovers |
| Workbook staging after restart | Passed (`matching_covers.xlsx · 85 KB`) |
| Matching after range change | Passed, 5 ranked results |
| Wishlist persistence across restart | Passed |

## Blocked

Camera content scenarios and auto-scan detection could not be regressed because they were never
executed (see `camera-testing-report.md`).
