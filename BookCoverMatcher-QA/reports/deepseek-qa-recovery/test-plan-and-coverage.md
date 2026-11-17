# Test plan and coverage

## Evidence types (never conflated)

| Code | Meaning |
|---|---|
| UI | exercised on the Pixel 8 emulator through the real Compose UI |
| CAM | emulator camera HAL (CameraX preview/capture) |
| IMG-IN | image-file input via the photo picker (not a camera test) |
| JVM | parser harness against compiled Kotlin classes |
| UNIT | `testDebugUnitTest` |
| STRUCT | OOXML validation in Python |
| INSPECT | source reading only |
| BLOCKED | could not be executed; reason recorded |

## Coverage matrix

| Area | Method | Status |
|---|---|---|
| Fixture structural validity | STRUCT | Passed (49/52 clean; 3 intentionally corrupt) |
| Fixture hash/manifest integrity | STRUCT | Passed (52/52) |
| Excel import, 52 fixtures | UI | Passed (47 match, 5 correct rejects) |
| Worksheet layouts, headers, blank rows | UI | Passed |
| Embedded image extraction + association | UI | Passed |
| Invalid workbook handling | UI | Passed (5 rejects, no crashes) |
| Large workbook parsing (500/1000 records) | UI | Passed |
| Parser regression | JVM | Passed (52/52) |
| Unit tests | UNIT | Passed (781, 0 failures) |
| Camera permission grant/deny | CAM | Passed |
| Camera preview + capture | CAM | Passed |
| Content-specific camera scenarios | CAM | **Blocked** (poster not on camera axis) |
| Auto-scan toggle/state | UI | Passed |
| Auto-scan detection quality | CAM | **Blocked** (needs controlled content) |
| Local matching, ranking, correct match | UI + IMG-IN | Passed (D4 cos 0.98 vs 0.51) |
| Wishlist create/expand/persist | UI | Passed |
| Navigation, empty states | UI | Passed |
| Rotation | - | Not applicable (portrait-locked) |
| Online search | - | Not tested (off by default; out of scope) |

## Approach

1. Audit previous work; distrust its conclusions until re-derived.
2. Re-validate fixtures independently before using them as an oracle.
3. Fix the automation before blaming the app (the previous failure was 100% harness).
4. Test the app the way a user does: picker, staging, tabs, toasts, restart.
5. Record Blocked honestly instead of substituting a weaker test.
