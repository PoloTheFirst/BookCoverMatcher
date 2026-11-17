# Exploratory testing report

Scenarios were chosen from realistic user journeys rather than extended case lists. Only what was
actually executed is listed as Passed.

## E2E-1 - Full offline journey (Passed)

Choose workbook -> scan a cover -> rank -> save as a session -> restart -> verify.

Observed: `matching_covers.xlsx · 85 KB` staged; capture produced a 512-d embedding; "Compared
against 5 image(s)."; Search tab showed 5 ranked rows; session saved into a new folder
`NightArchiveQA` ("1 session(s) · 5 result(s)"); after force-stop + relaunch the folder and session
were still there with identical counts.

This is the app's core promise and it holds end to end.

## E2E-2 - Recover from a broken file (Passed)

An import that fails must not poison the next one. A 0-byte workbook produced
`Error: The selected file is empty.` and left the field on "No file chosen"; re-pushing a good file
staged normally and matched. No restart needed.

## E2E-3 - Deny the camera, then change your mind (Passed)

Scan without permission -> system dialog -> "Don't allow" -> card falls back to
"Camera is off / Tap 'Scan Book Cover' to start", no spinner, no crash. Granting the permission and
tapping again brought the preview back to `LIVE` and capture still worked. The denial path does not
wedge the camera.

## E2E-4 - Wrong range, then correct it (Passed, and it exposed UI-01)

Default `A1:B20` gave "No images found inside A1:B20". Editing the field to `A1:Z30` and pressing the
same button produced 5 results, with no re-import required. Recovery is easy *if* you know the cause;
nothing in the message points at the range. Logged as UI-01.

## E2E-5 - Re-import the same workbook repeatedly (Partially verified)

The sweep imported `valid_minimal`, `valid_typical` and others several times across runs with no
degradation or duplicate-state artefacts. A deliberate rapid double-import was not performed.

## E2E-6 - Interrupted navigation (Not tested)

Leaving the camera screen mid-capture, rotating during a scan, and cancelling a match from the busy
card were not exercised. The busy card exposes a Cancel action in source; behaviour under
cancellation was not verified on device.

## Observations that are not defects but affect users

- Errors are delivered as toasts, which vanish and are invisible to accessibility dumps. A user who
  looks away misses the only explanation of a failure.
- The camera card's "tap once to start, tap again to capture" is explained only by a transient toast.
- The More screen requires scrolling to reach the Saved-workbooks entry point referenced from Home
  ("More -> Saved workbooks").

## Not tested

Online search (off by default), bookmarks (as opposed to sessions), rename/delete flows, export of
images, batch selection.
