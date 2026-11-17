# Scanning and auto-scan report

## Manual scan

`Scan Book Cover` is a two-step control on first entry: the first tap starts the camera (badge
`Starting` -> `LIVE`, toast "Camera ready - tap Scan again to capture"), the next tap grabs the
guide-box crop. This is intentional per `MainViewModel.noteCameraStartIntent`, and it was reproduced
on the emulator.

Guide crop: `CameraController.guideCrop` rotates by `imageInfo.rotationDegrees`, crops
`GuideGeometry.guideRect(...)` for the current view aspect, then downsizes to `Config.MAX_IMAGE_DECODE`.
The dashed guide in the UI matches the region that was captured (the target thumbnail shows the
same framing as the guide box).

## Auto Scan

- Toggle: enabled and disabled from Home; state persisted in prefs and reflected in the content
  description and badge.
- Engine: `AutoScanEngine` with `intervalMs=400`, `stableFrames=3`, `cooldownMs=1500`,
  blur/dark/bright thresholds, `graceFrames=1`.
- **No unintended upload was observed.** With online search off (the default), the app made no
  network calls during scanning; the only camera-adjacent log lines are CameraService/CameraX.
  Online search remains opt-in on the More screen.

## Not verified

- Detection quality (does a cover in frame trigger a capture?) - this needs controlled camera
  content, which is exactly what was blocked above. The toggle and state machine were verified;
  the detection outcome was not.
- Duplicate suppression across repeated detections, rapid frame changes, and cooldown behaviour
  under real content.
- Memory/CPU behaviour over a long auto-scan session.
