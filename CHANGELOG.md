# Bug fixes

**v2.2.3 is a bug-fix patch** for the v2.2.2 release: it adds no provider, no search mode and no stored
field. Only this fix patch is recorded here — the notes for earlier releases stay in the Git history.

## v2.2.3 (2026-10-25)

Fixes three defects from v2.2.2, one of them a v2.2.2 regression. `records.json` stays at version 3, and
no provider, setting or stored field was added.

### Fixes

* **DLsite search with more than one word (a v2.2.2 regression).** The keyword is a path segment on
  DLsite (`/books/fsr/=/keyword/<query>`), and the app encoded spaces there as `%20`. DLsite answers
  that with **HTTP 403**, so `HelloWorld Bobby` or `Blue Archive` failed while single words worked.
  Measured 2026-10-23 with the app's own user agent: `keyword/Blue+Archive` → **200 with 30 works**,
  `keyword/Blue%20Archive` → **403**. Queries are now encoded with a per-platform query form
  (`QueryForm.PLUS` for DLsite, `%20` for every other platform), so spaces stay word separators while
  `+`, `&`, `%`, `#` are still percent-encoded. The DLsite card's `Open` link uses the same form, so
  the browser hand-off no longer 403s either.
* **Result profiles could not be renamed.** They now carry the same pencil as folders — same icon,
  same dialog, same trim and blank-name rule, same duplicate-name policy. The profile is renamed in
  place, keeping its id, folder, kind, query, results and every bookmark reference.
* **A result inside a profile could not be bookmarked.** Each result row now offers `★ Bookmark` /
  `★ Bookmarked` (the same label and state colour as the live result card). The bookmark is a
  `Membership` in `Bookmarks` while the profile entry remains a `SessionEntry` of the **same** item
  record, so neither membership duplicates the work and neither deletion removes the other. Workbook
  comparison rows stay read-only and are still refused.
* Version code/name: **205 / 2.2.3**.

### Verified

* `.\gradlew.bat :app:testDebugUnitTest` — **285 tests, 0 failures** (4 opt-in live checks skipped by
  design). New: 8 profile-rename tests, 10 profile-bookmark tests, 6 DLsite URL-encoding tests and 2
  query-form tests.
* `.\gradlew.bat :app:assembleDebug` — BUILD SUCCESSFUL.
* Opt-in live run (`-DonlineCheck=1 --tests "*LiveCatalogueTest"`) — **4/4 passed**, including
  `dlsiteAnswersAMultiWordQuery` (“Blue Archive” returned real works through the adapter).
* Live DLsite probe (curl, app user agent): single word → 200; `Blue+Archive` → 200 with 30 works;
  `Blue%20Archive` → 403; a two-word Japanese query → 200 with 30 works; `A%26B`, `C%2B%2B`, `100%25`
  and `hash%23tag` → 200.
* Compose UI tests: still none — the project has no `ui-test-junit4` dependency and no device or
  emulator was attached. The rename pencil, the bookmark chip and the dialogs are therefore covered by
  compilation plus ViewModel/repository tests, not by an on-device interaction test.

### Known limitations

* No Android device or emulator was available, so the three fixes were verified by unit tests and a
  live HTTP probe, not by tapping through the UI on hardware.
* DLsite still serves one page of 30 results and the app never paginates it (its `robots.txt` asks for
  a crawl delay and disallows the paged URLs).
* A profile result is bookmarked into `Bookmarks` — the app's single bookmark collection — matching the
  existing behaviour for live results; there is no separate bookmark-folder picker.
