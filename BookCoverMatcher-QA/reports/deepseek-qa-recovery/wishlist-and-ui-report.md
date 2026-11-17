# Wishlist and UI report

**Method:** emulator UI.

## Wishlist

| Scenario | Result |
|---|---|
| Save a scan as a session | Passed - "Save this scan as a session" dialog, 5 comparison results saved as one session |
| Create folder inline while saving | Passed - folder `NightArchiveQA` created from the dialog |
| Folder list | Passed - "Folders (1)", `NightArchiveQA`, subtitle "1 session(s) · 5 result(s)" |
| Expand folder | Passed - reveals `SESSIONS` with `Scan #1 / Offline comparison / 2026-11-02 18:22 · 5 result(s)` |
| Persistence after app restart | Passed - force-stop + relaunch, folder and session still present with the same counts |
| Rename / delete affordances | Present (Rename folder, Delete folder, Delete session, Rename result profile) - not exercised |
| Empty state | Passed - "No folders yet. Create one below; a folder is just the name you give it." |

Bookmarks tab showed `Bookmarks (0)`; bookmark creation from a result card was not exercised.

## UI observations

- Navigation: bottom bar Home / Search / Wishlist / More, all reachable; no dead ends or crashes.
- Long lists scroll correctly; the More screen needs scrolling to reach the Saved-workbooks panel.
- Toasts are the primary error channel and are not exposed to accessibility/uiautomator dumps -
  they had to be captured by screenshot. Worth knowing for future automation.
- The infinite pulse animation on the camera card can make `uiautomator dump` hang; see
  `root-cause-analysis.md`.

## Not tested

- Rotation (activity is portrait-locked), dark/light theming, font scaling.
- Dialogs other than the save-session dialog.
- Snackbar behaviour (the app uses toasts).
