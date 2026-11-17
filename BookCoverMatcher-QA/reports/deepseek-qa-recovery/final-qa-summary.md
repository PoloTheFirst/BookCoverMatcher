# Final QA summary - deepseek-qa-recovery

**Engagement:** recovery QA after an incomplete prior attempt
**App:** BookCoverMatcher 2.4.0 (versionCode 213), commit `e751450`, dirty working tree preserved
**Device:** Pixel 8 AVD (`emulator-5554`), Android 17 / API 37, 1080x2400 @420dpi - genuinely used
**Date:** 2026-11-02

---

## 1. Previous QA audit

Fixture generation was real and survives independent re-validation. Application testing did not
happen: the previous sweep logged **0 passes out of 92 attempts**, all failing in its own driver
before any file was opened. Its screenshots show pickers, not results. Details in
`previous-qa-audit.md`.

## 2. Fixture-generation completion status

**Complete.** The existing 52 fixtures were retained unchanged; one new fixture
(`matching_covers.xlsx`) and 17 synthetic images were added. No fixture needed repair.

## 3. Fixtures created, repaired, validated

| Item | Count |
|---|---|
| Re-validated (hash + OOXML + JVM harness + emulator) | 52 |
| Repaired | 0 |
| Created | 1 workbook + 17 images |

## 4. Pixel 8 identity

AVD `Pixel_8`; product `sdk_gphone64_x86_64`; Android 17; API 37; build `CE2A.260420.051.A2`;
1080x2400 @420; 7.4 GB free.

## 5. Was the emulator actually used?

**Yes.** Every result marked UI/CAM below comes from the running app on that device.

## 6. Build and installation

`assembleDebug` SUCCESSFUL; APK 195 MB built 07:29 (newer than the newest source file, 05:18);
`adb install -r` Success; launch resumed with **0 fatal exceptions**.

## 7. Excel import

**47/52 fixtures imported with counts matching the manifest exactly; 5/5 invalid files correctly
rejected; 0 mismatches.** See `fixture-comparison.md`.

## 8. Image extraction

Counts and associations correct for every fixture, including two-images-per-note, broken relIds,
missing media, orphan media, and 640 media parts in the 1000-record workbook. Result rows name their
source cell (`D4`, `D5`, ...), so attribution is verifiable, not just tallied.

## 9. Camera and scanning

Permission dialog, deny, grant, live preview and capture all Passed. Capture produced a real 512-d
OpenCLIP embedding from the camera HAL.

**Blocked:** angled / rotated / low-light / blurry / obstructed / small-in-frame / multi-cover /
similar-cover / non-book / blank scenarios. The emulator's `-virtualscene-poster` injection did not
put the cover on the camera's axis and the scene pose cannot be driven over adb. No substitute test
was claimed.

## 10. Auto Scan

Toggle and state transitions Passed (`Auto-scan: ON`, badge updates, persisted in prefs). **Detection
quality is Blocked** - it requires controlled camera content.

## 11. Local matching and wishlist

Matching: 5 ranked results, cosine strictly ordered; a controlled query scored **cos 0.98 / 97.6%**
against **0.51 / 25.7%** for the runner-up, i.e. the correct record ranked first with a wide margin
(image-file input; the camera path is Blocked).

Wishlist: folder creation, session save, expand, and **persistence across an app restart** all Passed.

## 12. Exploratory findings

The core offline journey works end to end and survives restart. Failure recovery works. Denying the
camera does not wedge it. The notable rough edge is that a failing import or empty match explains
itself only through a transient toast.

## 13. Defects

| ID | Severity | Status |
|---|---|---|
| XLS-01 - blank row silently truncates the catalogue (8 comments, 3 records) | Medium | Open, documented |
| UI-01 - default range `A1:B20` excludes the title/image column | Low | Open, documented |

No Critical or High defects. Three apparent defects were investigated and dismissed as harness or
environment artefacts (`bug-register.md`).

## 14. Root causes

- Prior zero results: fixtures never on the device + fixed-scroll driver + grid-view tap target.
- Intermittent 60 s hangs: `uiautomator dump` waiting on a never-idle infinite animation.
- "Empty file" red herring: emulator killed before a push flushed.

## 15. Source files changed

**None.**

## 16. Fixes implemented

**None.** Both open issues are product decisions (warn on truncation; choose a better default range)
rather than defects with an unambiguous correct patch. No fix is claimed without a passing
reproduction.

## 17. Automated tests

781 unit tests, 0 failures, 0 errors, 7 skipped (parsed from JUnit XML). JVM parser harness re-run:
52/52 PASS. No instrumentation tests exist to run.

## 18. Emulator regression

All 52 fixtures re-run after the harness fixes: 47 matches, 5 correct rejections, 0 mismatches.
Camera deny/grant, staging, matching and wishlist persistence re-checked.

## 19. Remaining limitations

- Camera content scenarios and auto-scan detection: Blocked (no injectable camera content).
- Focus/exposure: not applicable to the virtual scene.
- Rotation: activity is portrait-locked by design.
- `.xlsm`, cloud providers, bookmarks, rename/delete, export, batch operations: Not tested.
- Online search: out of scope, off by default.

## 20. Git working-tree status

Unchanged by this engagement. Branch `dev` at `e751450`; the 87 pre-existing entries (20 staged,
46 modified, 23 untracked) are exactly as found; baseline in
`evidence/logcat/git-baseline.txt`. Nothing was committed, pushed, stashed, reset or cleaned.

---

## Honest bottom line

The Excel side of this application is in good shape: 52 fixtures, a correct parser, correct image
association, graceful rejection of bad files, and sane behaviour at 1000 records. The camera and
ranking pipeline works when it is given something to look at. What this engagement could **not**
prove is how well the camera behaves on real, varied cover photographs - the emulator would not let
me put a chosen image in front of the lens, and I have marked that Blocked rather than pretend a file
import was a camera test.
