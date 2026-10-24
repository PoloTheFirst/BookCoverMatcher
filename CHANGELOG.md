# Added more providers

## v2.2.3 — 2026-10-23

A bug-fix patch. Three defects are fixed; no provider was added or removed, no new search mode was
introduced, and the saved-data format is unchanged (`records.json` v3).

### Fixed

* **DLsite search with more than one word.** The keyword is a path segment on DLsite
  (`/books/fsr/=/keyword/<query>`), and the app encoded spaces there as `%20`. DLsite answers that with
  **HTTP 403**, so `HelloWorld Bobby` or `Blue Archive` failed while single words worked. Measured
  2026-10-23 with the app's own user agent: `keyword/Blue+Archive` → **200 with 30 works**,
  `keyword/Blue%20Archive` → **403**. Queries are now encoded with a per-platform query form
  (`QueryForm.PLUS` for DLsite, `%20` for every other platform), so spaces stay word separators while
  `+`, `&`, `%`, `#` are still percent-encoded. The DLsite card's `Open` link uses the same form, so
  the browser hand-off no longer 403s either.
* **Result profiles can be renamed.** The folder pencil now has an exact counterpart on every result
  profile row: same icon button, same dialog, same trim and blank-name rule, same duplicate-name
  policy. The profile is renamed in place, keeping its id, folder, kind, query, results and every
  bookmark reference.
* **Bookmarking a result from inside a profile.** A result saved in a profile can be bookmarked from
  its row (`★ Bookmark` / `★ Bookmarked` — the same label and state colour as the live result card).
  The bookmark is a `Membership` in `Bookmarks` while the profile entry remains a `SessionEntry` of the
  **same** item record, so neither membership duplicates the work and neither deletion removes the
  other. Workbook comparison rows stay read-only and are still refused.

### Verified

* `.\gradlew.bat :app:testDebugUnitTest` — **284 tests, 0 failures** (3 opt-in live checks skipped by
  design). New: 8 profile-rename tests, 10 profile-bookmark tests, 5 DLsite encoding tests and 2
  query-form tests.
* `.\gradlew.bat :app:assembleDebug` — BUILD SUCCESSFUL.
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


## v2.2.2 — 2026-10-23

A design correction for the online-search panel. Users choose which platforms to search, every platform has its own browser link, and the online result count is configurable instead of being fixed at five. The offline workbook matcher, the saved-data structure (`records.json` v3: folder → session → results, with an independent `Bookmarks` collection) and the bookmark rules are unchanged.

## Added

* **Platform selection.** All ten platforms are drawn from `ProviderCatalog` as cards: the three the app can query (Open Library, Google Books, DLsite) and the seven browser-only shortcuts (Melonbooks, Suruga-ya, Doujin no Mori, E-Hentai, Doujinshi.info, MyAnimeList, nhentai). The card body is a checkbox-role target that selects or deselects an **automated** platform; its `Open ↗` control is a separate sibling target that opens that platform's search page and never changes the selection. Selection is persisted in `bookcover_online` (`online_providers`) and validated by `SearchRouting`.
* **Selected/unselected styling.** A selected card uses the blue accent with a soft glow, a thicker accent border and a check mark (shape, not colour alone); an unselected card keeps a subdued surface and muted text but remains fully usable, link included.
* **A browser link for every platform.** `ProviderLinks` builds each search URL from the current query (UTF-8, `%20`), falls back to the platform's landing page when the query is blank, and rejects any result that is not HTTPS on that platform's own host.
* **Configurable online result count.** 5 / 10 / 20 / 30 / 50 (default 5), persisted as `online_limit` and applied per provider through each adapter's cap (`SearchLimits.forProvider`).
* **Doujin no Mori, Doujinshi.info, MyAnimeList and nhentai are browser-only shortcuts.** Doujin no Mori (`doujinnomori.com`) answers and its `/comics/free-search?keyword=` page is server-rendered, but its `robots.txt` says `Disallow: /comics/free-search` — the search route itself — so the app links to it without querying it. Doujinshi.info answers HTTP 403 to automated requests; MyAnimeList has no unauthenticated API and its `/topmanga` URL is a 404; nhentai answers HTTP 403 (Cloudflare) and is an adult catalogue. E-Hentai's `robots.txt` disallows `/s/` and `/codegen.php`, so its card only opens the site's own search page. None of them is parsed, stored or counted as an in-app result.

## Changed

* **Search routing follows the selection.** Only selected automated platforms are queried, concurrently and with a 20-second deadline each; one failure never hides another provider's results. Selecting only browser-only platforms issues no request at all and shows a browser-handoff message.
* **Honest per-provider states:** results / no match / failed / blocked / searching / browser-only /
  not selected. A skipped or browser-only platform is never counted as an answered provider, and its
  card still offers its link.
* **Session cache key** now includes the requested maximum and the selection, so a cached five-result answer cannot satisfy a request for twenty; changing either cancels an in-flight search and drops the stale entry.
* **Result header** reports the number of cards actually drawn, the configured maximum, and states when fewer than the maximum arrived.
* **Melonbooks is browser-only and unselectable** by explicit product decision: its card stays, its link stays, and it is never queried in the app. Its adapter and parser remain in the tree, unregistered and covered by their own tests. **Suruga-ya** stays browser-only because its readable `robots.txt` disallows `/search/`. A browser-only card cannot be toggled into the selected set, and a selection stored by an older build is filtered on read.
* Version code/name: **204 / 2.2.2**.

## Verified

* Unit tests: `.\gradlew.bat :app:testDebugUnitTest` — **259 tests, 0 failures** (3 live checks skipped by design).
* Compose UI tests: **none executed.** The project has no `androidx.compose.ui:ui-test-junit4` dependency and no emulator or device was attached (`adb devices` empty), so selection toggling, the glow/dim styling and the independence of the link control are covered by pure-logic tests (`SearchRoutingTest`, `ProviderLinksTest`) and by compilation — not by an interaction test.
* Live providers (`.\\gradlew.bat :app:testDebugUnitTest -DonlineCheck=1 --tests "*LiveCatalogueTest" --rerun-tasks`) — **3 tests, 0 failures, 0 skipped**: Open Library and DLsite returned real records for a real query; Google Books was found or correctly reported `RATE_LIMITED`. Melonbooks left the live suite with its registration, so the live run makes no request to it.
* Build: `.\gradlew.bat :app:assembleDebug` — **BUILD SUCCESSFUL** (`app-debug.apk`).

## Known limitations

* **Suruga-ya remains browser-only.** Its `robots.txt` (HTTP 200) contains `User-agent: *` → `Disallow: /search/` with `Crawl-delay: 30`; the search page answers, but its own rules exclude the endpoint, so the app never queries it and offers `Open ↗` instead.
* **Melonbooks is browser-only in this build.** Its card and its link stay, but the app never queries it: the adapter and parser remain in the tree, unregistered, and only their offline fixture tests run.
* **DLsite is first-page-only (30 works)** and its paged URLs are disallowed by its robots file; **Google Books caps one request at 40** and can answer 429 without a key. A selected maximum is therefore never a promise.
* **UI interactions and visual styling were not verified on a device or emulator** — none was available and the project has no Compose UI-test infrastructure.
* **Visual image-similarity search remains unimplemented.** Every online result is a text match on OCR'd or typed text; no provider performs an image search.

## v2.2.2 patch — image name, copyable OCR text, batch management, provider correction

Still **2.2.2** (`versionCode 204`). This patch fixes and extends the same release; it adds no new
release version and no tag.

### Fixed

* **The selected image's name is shown.** After a picker selection the status line shows the file's
  own display name with its extension (`book_cover.jpg`, `scan_001.png`, `cover.webp`), read through
  `ContentResolver` + `OpenableColumns.DISPLAY_NAME`. The name is set only after a successful decode,
  so a failed pick never shows a name next to the previous cover. A camera capture has no file, so it
  clears the name back to the `No image chosen` placeholder. The pure mapping is `ImageFiles.labelFor`
  (unit-tested): provider name, then the URI's last segment, then a MIME-based fallback, never an
  opaque content id.
* **Read the Cover can be copied from.** The field stays read-only, but its text is now inside a
  `SelectionContainer`, so a long-press selects part or all of it and the system Copy command works.
  Selecting or copying never changes the OCR text, the query or the catalogue state.
* **Melonbooks is browser-only and unselectable.** Its card, note and `Open` link remain; its body is
  no longer clickable and `SearchRouting` drops the id from any stored selection. The adapter and
  parser stay in the tree, unregistered, with their fixture tests.

### Added

* **Multi-selection and batch actions** for the Wishlist lists, each action one atomic repository
  write (`WishlistRepository`):

  | List | Copy | Move | Delete |
  |---|---|---|---|
  | Bookmarks | into any folder, bookmark kept | not offered (a bookmark *is* a Bookmarks membership; moving it would un-bookmark it) | removes the bookmark only |
  | Saved items in a folder | into any folder | to another folder, source membership removed | removes it from that folder only |
  | Sessions (profiles) | new session, same items, ` (copy)` suffix | to another folder | session and its entries |
  | Session results | into a folder | into a folder, entry removed from the session | removes the entry, never a comparison row |
  | Folders | new folder with copies of its sessions and direct items | not offered (the model is flat, a folder has no parent, so no cycle is possible) | folder, its sessions and memberships |

  Selection is navigation state only: `Select` on a list, a count plus Select all / Clear / Done, a
  per-row check mark, and a Copy / Move / Delete bar. Leaving the screen, switching tab or pressing
  Back drops it. Deletion asks first and names the count and the scope. Undo is **not** offered:
  there is no safe reversed write in this store.
* **Membership integrity is now pinned by tests:** deleting a bookmark keeps the wishlist entry,
  removing an item from a folder keeps its bookmark and other folder memberships, moving a result out
  of a session keeps its bookmark, and copying never duplicates an item or a membership.
* **Doujin no Mori / Doujinshi.info / MyAnimeList / nhentai cards** (browser-only, unselectable). Each
  shortcut carries the query; none of them can be selected or queried.

### Verified

* Unit tests: **259 tests, 0 failures** (3 live checks skipped by design), including 19 new
  repository batch tests and 8 image-label tests.
* Build: `.\gradlew.bat :app:assembleDebug` — BUILD SUCCESSFUL.
* Compose interaction tests: still **none executed** — the project has no `androidx.compose.ui:ui-test-junit4`
  dependency and no emulator/device is attached. Selection styling, the batch bar and text selection
  are therefore covered by compilation and by pure-logic tests, not by an interaction test.

### Known limitations

* **Doujin no Mori is browser-only** because its own `robots.txt` disallows `/comics/free-search`.
  Doujinshi.info (HTTP 403), nhentai (HTTP 403) and MyAnimeList (no unauthenticated API) are
  browser-only for their own measured reasons.
* **E-Hentai, Melonbooks and Suruga-ya are browser-only.** E-Hentai is an adult catalogue behind the
  site's own age acknowledgement and its `robots.txt` disallows `/s/` and `/codegen.php`; the app
  never parses or stores those results.
* **A copied session or folder does not copy the captured scan thumbnail** — that file belongs to the
  original session and is deleted with it. Results, titles, queries and membership are preserved.

## v2.2.2 continuation — three more catalogue methods, batch UI completed

Still **2.2.2** (`versionCode 204`); no new version and no tag.

### Providers

* **Doujin no Mori (`dm`)** — the entry previously named "DoujinMori" pointed at `doujinmori.com` /
  `doujin-mori.com`, which do not exist; that was a naming error, not evidence about the site. It is
  now named correctly, uses `doujinnomori.com`, and its shortcut is the verified
  `https://doujinnomori.com/comics/free-search?keyword=<percent-encoded query>`. The page answers 200
  and is server-rendered, but the site's own `robots.txt` says `Disallow: /comics/free-search` — the
  search route itself — so it stays browser-only and unselectable. The id stays `dm`; a browser-only
  id can never be in a saved selection (it is dropped on read), so no migration is needed.
* **Doujinshi.info (`di`)** — browser-only. `/works?query=` is the site's own route and its
  `robots.txt` allows everything, but every request from an automated client answers **HTTP 403**. The
  shortcut carries the query; the user handles the site's "show adult content" control themselves.
* **MyAnimeList (`mal`)** — browser-only. `/topmanga` answers **404**, so the card uses the site's real
  manga search, `/manga.php?q=<query>&cat=manga`. No scraping: there is no unauthenticated API and the
  terms do not permit automated collection.
* **nhentai (`nh`)** — browser-only. Home, `robots.txt` and `/search/?q=` all answer **HTTP 403**
  (Cloudflare) and the catalogue is adult content; the card opens the site's own search page, where its
  age gate applies. The app makes no request, sets no cookie and adds no adult fixture.

All ten cards keep the existing theme: the three in-app providers glow blue when selected, the seven
browser-only ones are dimmed with a warm border, a `Browser only` chip and a body that is not a toggle.
Every card keeps its own `Open` link, which never changes the selection.

### Batch management: the UI is now complete

The earlier state had the repository operations and a selection state, but the only way in was a small
text link, there was no explanation of the actions, the action bar had no Done, and a stale id could
survive a delete. That is fixed:

* a labelled **Select** button (with a checkbox glyph) in the header of every selectable list, plus
  **long-press on a row** to enter selection mode with that row already selected;
* a pinned panel showing `n selected`, a one-line explanation of what Copy/Move/Delete will do for
  that list, and **Select all / Clear / Done**;
* a pinned action bar with the count, **Copy / Move / Delete** and **Done** — only actions the model
  supports are drawn (no Move for folders or bookmarks);
* `SelectionLogic` (new, unit-tested) owns toggle / select-all / clear / reconcile and per-kind
  capability checks; `publish()` reconciles the live selection against the visible rows, so a deleted
  row can never be acted on;
* `requestBatchMove` refuses a kind without a destination instead of silently copying;
* the failure path is pinned: with `records.json` read-only, a batch reports `changed = 0, failed = n`
  and a reload still shows the old data.

### Verified

* Unit tests: **259 tests, 0 failures** (3 live checks skipped by design), including 13 selection-logic
  tests and the 19 repository batch tests.
* Build: `.\gradlew.bat :app:assembleDebug` — BUILD SUCCESSFUL.
* Compose UI tests: **none executed** — no `ui-test-junit4` dependency and no device/emulator. The
  exact manual steps are in `USER_GUIDE.md` under "Batch actions".

### Known limitations

* The batch workflow's visible interactions were **not** exercised on a device here; the state rules
  and every repository operation are unit-tested and the manual checklist is documented.
* Doujinshi.info, nhentai and MyAnimeList cannot be searched in-app (403 / no API / terms).
* A result selected inside a session moves into a **folder**, not into another session: a session is
  the record of one search, and re-homing a result would rewrite that record.
* A copied session or folder does not copy the captured scan thumbnail (it belongs to the original
  session).
