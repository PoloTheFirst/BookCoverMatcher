# Camera test report — v2.4.0-rc.7

## What was done in this round

**Nothing was re-driven on the camera.** The two defects fixed in rc.7 are in the Excel reader and the
default range, and no camera, capture, crop, orientation or auto-scan code was touched. The previous
engagement's camera results are therefore carried forward as *reported evidence*, not re-verified here,
and this report says so rather than implying a fresh pass.

## Prior evidence (recovery engagement, same AVD, same code path)

| Scenario | Input method | Result |
|---|---|---|
| Camera permission dialog | real UI | Passed |
| Permission denied | real UI | Passed — the app explains and recovers |
| Permission granted after denial | real UI | Passed — preview recovers |
| Live preview | real CameraX on the virtual scene | Passed |
| Capture | virtual-scene frame → real CaptureX capture | Passed — produced a real 512-d OpenCLIP embedding |
| Auto Scan toggle and state transitions | real UI | Passed (`Auto-scan: ON`, badge updates, persisted in prefs) |
| Auto Scan detection quality | — | **Blocked** |
| Angled / rotated / low-light / overexposed / blurry / obstructed / small-in-frame / two covers / similar cover / non-book / blank | synthetic images exist (`test-data/synthetic-images/camera/scenarios/*.png`) but could not be put in front of the lens | **Blocked** |
| Focus / exposure behaviour | — | **Not applicable** to the virtual scene |
| Rotation of the activity | — | Portrait-locked by design |

## Why the content scenarios are blocked (unchanged)

`-virtualscene-poster wall=<file>` places an image on the virtual scene's wall plane, but the camera pose
that faces that plane is interactive-only (host window input). Sending arrow keys through `AppActivate` +
`SendKeys` produced a pixel-identical preview, so the pose did not move. The emulator build exposes no
adb path to pose the scene or to inject a camera frame.

## What was *not* claimed

* No synthetic cover was imported as if it were a camera capture. A file import exercises the image
  pipeline, not the camera pipeline, and the two are reported separately (this is the distinction the
  brief asks for and the previous engagement respected).
* The image-processing path itself *was* exercised: `test-data/matching/matching_covers.xlsx` was used by
  the previous engagement for a graded matching run (correct record first at cos 0.98 vs 0.51 runner-up,
  i.e. 97.6% vs 25.7%) — image-file input, not camera input.

## Recommendation for the next round

Camera-content QA needs one of: an interactive emulator session where the scene pose can be moved by
hand, a physical device, or a debug-only camera frame injection path. Until one exists, these scenarios
should stay marked **BLOCKED** rather than be approximated with file imports.
