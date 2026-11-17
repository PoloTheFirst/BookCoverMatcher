# Recovery Plan — deepseek-qa-recovery

Derived from `previous-qa-audit.md`. The fixture corpus is reused unchanged; the missing work is
**application-level testing on the Pixel 8 emulator**.

## Guiding constraints

- Authentic workbooks (`BookCoverMatcher\test_excel\`) are read-only. Nothing in this engagement
  writes there.
- Previous QA artifacts are preserved. New work lives only under `reports/deepseek-qa-recovery/`
  and the new `scripts/deepseek_*.py` files.
- No commit, push, tag, stash, reset or clean. The pre-existing dirty working tree is preserved.
- Every status is reported as: Passed / Failed / Blocked / Not tested / Needs investigation, and
  every claim names the evidence type (source inspection, unit test, JVM harness, emulator UI,
  camera, image-file input).

## Sequencing

| Step | Work | Depends on | Status |
|---|---|---|---|
| 0 | Record git + environment + authentic-workbook baseline | — | done |
| 1 | Independent fixture re-validation (OOXML, hashes) | — | done |
| 2 | Start/verify Pixel 8 AVD, record device baseline | — | done |
| 3 | Build APK from working tree, install, launch, confirm no crash | 2 | done |
| 4 | Push 52 fixtures to `/sdcard/Documents/QA/<category>/` | 2 | done |
| 5 | Replace the broken UI driver with a hierarchy-driven one | 3 | done |
| 6 | Sweep all 52 fixtures through the import + comments UI | 4,5 | running |
| 7 | Camera: permission matrix, preview, capture, crop, retake, auto-scan | 6 | pending |
| 8 | Local matching: ONNX/OpenCLIP inference, ranking, empty/large catalogue | 6 | pending |
| 9 | Wishlist add/remove/persist/restart + UI states | 6 | pending |
| 10 | Exploratory session (combined workflows, interruptions) | 7–9 | pending |
| 11 | Classify + prioritise defects; root-cause the confirmed ones | 6–10 | pending |
| 12 | Fix confirmed defects with the smallest correct change + regression test | 11 | pending |
| 13 | Rebuild, reinstall, re-run failing scenarios + full regression | 12 | pending |
| 14 | Write the report set and final summary | 13 | pending |

## Known risks and how they are handled

| Risk | Handling |
|---|---|
| SAF picker is the historic failure point | Driver navigates by live view hierarchy, forces list view, verifies selection by focus change |
| Emulator camera has no real optics | Back camera is `virtualscene`; content is injected with `-virtualscene-poster wall=…`, which is genuine camera-pipeline input, not a file import. Scenarios that need precise framing that the scene cannot provide are recorded as Blocked, never as Passed |
| Image-file import could be mistaken for camera testing | The two are reported separately and never conflated |
| Large perf workbooks may exceed UI patience | 90 s settle budget, `perf_*` catalogue rendering skipped (documented) |
| Dirty working tree makes "before/after" ambiguous | Git status captured to `evidence/logcat/git-baseline.txt` before any edit; every source change listed in the fix report |

## Exit criteria (mirrors the engagement brief §17)

Everything in the brief's checklist must be either **done with evidence** or **explicitly Blocked
with the technical reason**. No item may be silently skipped.
