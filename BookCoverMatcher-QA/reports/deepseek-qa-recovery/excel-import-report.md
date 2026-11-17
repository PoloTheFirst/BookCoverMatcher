# Excel import report

**Method:** actual emulator UI (SAF picker -> Home "Choose file" -> staged label -> More -> Open workbook comments).
Not source inspection, not the JVM harness.

## Result

| Outcome | Count |
|---|---|
| Imported and parsed, counts match manifest | 47 |
| Rejected by early-open validation (all invalid by design) | 5 |
| Mismatch | 0 |
| Driver failures | 0 |

## Import semantics observed

- Choosing a file **stages** it and validates by opening it once (`WorkbookFiles.stage` then
  `openWorkbook(...).close()`). A bad file clears the staged workbook and raises a toast.
- The file field shows `name · size` only after staging succeeds, so the label is a reliable
  "accepted" signal.
- Rejection messages observed: `Error: The selected file is empty.` (zero-byte file),
  and silent non-acceptance for non-ZIP/truncated/OLE containers.
- Sheet-name independence confirmed: `layout_renamed_sheet` (sheet named カタログ) imported identically
  to `LIST`-named workbooks.
- Hidden sheets, hidden rows and hidden columns are all still read (`layout_hidden_sheets` 4/4,
  `layout_hidden_rows_cols` 6/6).
- Two catalogue sheets in one workbook both count (`layout_two_tables` 7 records across LIST+BOOKS).
- Reordered headers change nothing (`layout_reordered_columns` 5/5) - mapping is text-based.
- Duplicate headers resolve to the first column (`edge_duplicate_headers` 3/3).

## Known behaviour worth flagging

`layout_blank_rows` produced **8 comments but only 3 records**: the reader stops at the first
all-empty row after data starts. This is documented in the fixture report as intended, and the
manifest encodes 3 records, so it is *expected* - but it means a stray blank row silently hides
every record below it. Logged as **XLS-01**.

## Not covered

- `.xlsm` macro-enabled files (no fixture was generated for the extension; the picker accepts it).
- Files on cloud/document providers other than local storage.
- Interrupted imports (picker dismissed mid-navigation) - only cancellation via Back was incidental.
