# Root cause analysis

## RCA-1 - Why the previous engagement produced zero application-level results

**Symptom:** 84 of 92 fixture runs ended `DRIVER-FAIL` with "Choose file button not found"; 7 crashed
the driver; 0 passed.

**Causes, in order of impact:**

1. **No fixtures on the device.** `/sdcard/Documents` was empty at the start of this engagement. The
   driver looked for a `QA` folder that had never been pushed. Every import was therefore impossible
   regardless of the rest of the logic.
2. **Fixed-scroll navigation.** `home_to_spreadsheet()` swiped a hard-coded number of times, then
   queried the hierarchy once. The home screen is scrollable and its content height changes with the
   camera preview, so the button was frequently off-screen.
3. **Grid-view tap target.** The picker's grid cards overlap a per-card preview button with the
   filename node. Tapping the node centre opened a preview instead of selecting the file.
4. **No post-action verification.** The driver never confirmed that a pick had closed the picker, so
   one failure cascaded into every later fixture.

**Fix applied:** hierarchy-driven driver (`scripts/deepseek_driver.py`) that re-reads the view tree at
each step, forces list view, taps only nodes fully inside the viewport, confirms selection by focus
change, and retries once. All 52 fixtures then imported.

## RCA-2 - Why `uiautomator dump` intermittently hung for 60 s

`uiautomator dump` waits for the window to be idle. The home screen runs
`rememberInfiniteTransition` (the pulsing dot on the camera card), so the window can be permanently
non-idle. A wedged dump blocked the adb shell and eventually the device went `offline`.

**Fix:** every dump is bounded (20 s) and retried; a timed-out adb call returns a synthetic
non-zero result instead of raising; a `DEVICE-DOWN` guard stops a run from recording bogus failures.

## RCA-3 - Why the injected camera poster never appeared

`-virtualscene-poster wall=<file>` loads an image onto the virtual scene's wall plane. The emulator's
default camera pose faces the TV wall, and the pose is interactive-only (host window input). Sending
`{LEFT}` via `AppActivate` + `SendKeys` produced a pixel-identical preview, so the pose did not move.

**Conclusion:** content-controlled camera QA requires either an interactive session at the emulator
window or a camera injection path that this emulator build does not expose over adb. Recorded as
Blocked rather than worked around with file imports, which would have misrepresented the evidence.

## RCA-4 - Why a valid fixture appeared "empty"

The emulator was force-killed ~2 s after `adb push`. QEMU's disk cache had not flushed, leaving a
0-byte file with the correct name. The app's `stage()` correctly counted zero bytes and said so.

**Lesson recorded in the driver notes:** always verify device-side size/md5 after a push and never
kill the emulator until the push has settled.
