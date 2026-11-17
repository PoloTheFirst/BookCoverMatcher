# Defect fix report

## Source changes

**None.** No application source file was modified by this engagement.

That is a deliberate outcome, not an omission:

- All 52 Excel fixtures behaved exactly as specified through the real UI (47 matches, 5 correct
  rejections, 0 mismatches).
- Camera, capture, embedding, ranking, wishlist and persistence worked on the emulator.
- The only two confirmed issues (XLS-01 blank-row truncation, UI-01 default range) are product
  decisions rather than coding errors:
  - XLS-01's stop rule is intentional and encoded in the fixture contract; changing it could merge
    unrelated tables. The defect is the *absence of a warning*, which needs a UI string and a
    product decision about wording.
  - UI-01 changes first-run behaviour for every user; picking a new default range or rewording the
    message is a product call.

Per the brief, a defect is only "fixed" when the original reproduction steps pass afterwards. Neither
issue was reproduced against a changed build, so neither is claimed as fixed.

## What was changed instead

All work is in the QA workspace:

| File | Purpose |
|---|---|
| `scripts/deepseek_driver.py` | hierarchy-driven adb/UIAutomator driver (replaces the failing one) |
| `scripts/deepseek_sweep.py` | 52-fixture UI sweep with resumable JSONL output |
| `scripts/deepseek_structure_check.py` | independent OOXML validator |
| `scripts/deepseek_camera.py` | camera/permission/auto-scan helpers |
| `scripts/generate_camera_images.py` | synthetic cover + scenario images |
| `scripts/generate_matching_fixture.py` | graded matching catalogue |
| `test-data/matching/matching_covers.xlsx` | new fixture (5 records, synthetic covers) |

## Recommended next steps for the two open issues

1. XLS-01: add a catalogue warning when the reader terminates early on a blank row; assert it in a
   unit test against `layout_blank_rows`.
2. UI-01: default the range to the detected table extent after staging, or name the range in the
   "no images found" message.
