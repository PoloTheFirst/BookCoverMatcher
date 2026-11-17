# Camera testing report

**Device:** Pixel 8 AVD, back camera `virtualscene`, front `emulated` (see `emulator-baseline.md`).
**Method:** real CameraX preview and capture through the emulator camera HAL - not a file import.

## What was executed and observed

| Scenario | Result | Evidence |
|---|---|---|
| Camera permission dialog on first scan | Passed - system dialog "Allow BookCover Matcher to take pictures and record video?" appeared | `camera/027-permission-dialog.png` |
| Permission denied | Passed - card returns to "Camera is off / Tap 'Scan Book Cover' to start"; no crash, no stuck spinner | `camera/028-permission-denied.png`, `camera/029-after-deny.png` |
| Permission granted, preview starts | Passed - badge `LIVE`, live scene rendering | `camera/001-preview-with-cover.png` |
| Capture (guide-box crop) | Passed - produced a target with a 512-d embedding and a preview thumbnail | `camera/003-captured-target.png`, `camera/020-state-now.png` |
| Repeated capture | Passed - a second capture replaced the target with a different embedding vector | `camera/020-state-now.png` |
| Auto-scan toggle on/off | Passed - content description flips to "Auto-scan: ON (tap to switch to Manual)" | `camera/030-autoscan-on.png` |
| Camera lifecycle after leaving screen | Passed - returning to Home re-opens the camera | observed during the fixture sweep |

## Blocked: content-specific scenarios

The brief lists angled / rotated / low-light / blurry / obstructed / small-in-frame / two-cover /
similar-cover / non-book / blank scenarios. Cover art for all of them was generated
(`test-data/synthetic-images/camera/`), and the emulator supports
`-virtualscene-poster wall=<file>`, which was used to restart the AVD with a cover on the scene wall.

**The injected poster did not appear in the camera's view.** The virtual scene renders a fixed room
(the camera faces a TV wall); the poster is on a different plane, and the scene viewpoint is
controlled interactively in the emulator window. Host-level key injection
(`AppActivate` + `SendKeys {LEFT}`) did not move it - the preview was pixel-identical afterwards
(`camera/002-preview-rotated.png` vs `001`).

Consequently every content-specific camera scenario is **Blocked**, not Passed. They were not
substituted with image-file imports, which are reported separately under local matching.

## Not tested

- Focus and exposure (the virtual scene has no focusable optics; tapping to focus could not be shown
  to change anything measurable).
- Front/back camera switching (the app exposes no switch control).
- Orientation: `MainActivity` is declared `screenOrientation="portrait"`, so rotation does not
  produce a landscape layout by design.
- "Deny permanently" (don't-ask-again) - only a single deny was exercised.
