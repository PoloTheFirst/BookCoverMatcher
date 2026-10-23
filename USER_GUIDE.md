# BookCover Matcher - User Guide (v2.2.2)

This guide covers the four tabs, the saved-data rules and the batch (multi-select) actions. For build
instructions and architecture, see `README.md` and `ARCHITECTURE.md`.

## Home

1. **Choose file** (`.xlsx` or `.xlsm`) and enter the cell range that contains the cover pictures.
   You can also choose an image from the device instead of using the camera.
2. **Scan Book Cover** or turn on the crosshair button for **Auto Scan**.
3. **Link & Find Top 5** compares the captured cover with every picture in the selected range.
4. The comparison is shown on **Search**. Nothing is saved yet.

The workbook is only read on the device. Offline matching works in airplane mode.

The status line under **Choose image** shows the selected file name, e.g. `book_cover.jpg` or
`scan_001.png`. A camera capture has no file name, so the line keeps the `No image chosen`
placeholder.

## Search

### The three result lists
**Search Results** has three tabs and each label carries a live count:

* **Workbook (n)** - the offline comparison: spreadsheet controls, the five match rows and
  **Save this scan as a session**. The count is the number of workbook matches for the current scan.
* **Online (n)** - the OCR text (read-only but selectable), the editable query, the ten platform cards,
  the 5-50 result maximum, and the results from the selected platforms. The count is the number of
  unique, displayable results; failures and placeholders are not counted.
* **Bookmarks (n)** - the results of the current search that are already in the `Bookmarks`
  collection, with **Bookmark all results** and a shortcut to the full collection.

Switching tabs never cancels a request, clears the session cache or changes saved data, and the three
lists are never merged: a workbook row is comparison output with no bookmark or remove control, while
an online record is a catalogue entry with its own actions.

### Offline comparison
The five match rows show the workbook cell, source label, cosine and percent. These rows are a
comparison result, not a shopping list: there is no bookmark or remove button on them.

**Save this scan as a session** opens a folder picker. Choose an existing folder or type a new name.
One session is saved; it is not five loose items. If no folder exists, create one in the picker
first - a scan never creates a folder by itself.

### Online search
Turn on **Use online services** (or use **More**). The first time, the app starts its embedded Python
runtime and asks whether `chrome-lens-py` is available.

**Read the cover.** The OCR text is read-only, but you can copy it: long-press it, drag to select part
or all, then use the system **Copy** command and paste it into the query box.

**Choose your platforms.** The panel shows ten cards in `ProviderCatalog` order:

* Searched in the app (selectable): **Open Library**, **Google Books**, **DLsite**.
* Browser-only (never selectable): **Melonbooks**, **Suruga-ya**, **Doujin no Mori**, **E-Hentai**,
  **Doujinshi.info**, **MyAnimeList**, **nhentai**.

Tap a selectable card body to include or exclude it: a selected card turns blue with a soft glow, an
accent border and a check mark; an unselected one is greyed down but still usable. **Open** on every
card is a separate target: it opens that platform's own search page with your query and never changes
the selection. A browser-only card is drawn dimmed with a warm border and a `Browser only` chip; its
body is not a toggle at all.

Why a card is browser-only (each measured; see `ONLINE_SEARCH.md` section 2):

* **Melonbooks** - browser-only by product decision; the page answers, the app does not use it.
* **Suruga-ya** - its `robots.txt` disallows `/search/` (crawl delay 30 s).
* **Doujin no Mori** - `doujinnomori.com`; its `robots.txt` disallows `/comics/free-search`, the route
  its own search uses. (The earlier "DoujinMori" entry pointed at the wrong domain and is corrected.)
* **E-Hentai** / **nhentai** - adult catalogues; their robots/Cloudflare rules block automated access,
  and the age gate is answered on the site, never by the app.
* **Doujinshi.info** - answers HTTP 403 to automated requests.
* **MyAnimeList** - no unauthenticated API; its terms do not permit automated collection.

**Choose how many results.** `ONLINE RESULTS` offers **5 / 10 / 20 / 30 / 50** (default 5) and
remembers your choice after a restart. It is a maximum: Open Library tops out at 50, Google Books at
40 and DLsite at one page of 30, so you may get fewer - the header says how many cards you actually
have. This does **not** change the offline comparison, which stays at five matches.

1. The captured cover is read with OCR. The query is filled from the title that was read; edit it if
   needed.
2. **Search selected platforms** queries the selected platforms the app can search itself:
   **Open Library**, **Google Books** and **DLsite**. Browser-only cards are never queried; every card
   shows what it did - results, no match, failed, blocked, still searching, browser-only or not
   selected.
3. Result cards show a real cover (or a placeholder), title, provider chip, price for a shop listing,
   source domain, bookmark state and wishlist state. Tap the card body to expand.
4. Expanded actions are separate: **Open website**, **Bookmark**, **+ Wishlist**.
   * **Bookmark** saves the link in `Bookmarks`.
   * **+ Wishlist** adds only that one item to a folder you choose.
   * **Bookmark all results** saves every result on screen to `Bookmarks`; it creates no folder.
   * **Add entire result profile to wishlist** saves the offline top-5 and the online results as one
     session in the chosen folder.
5. **Clear session cache** removes temporary results only.

If OCR fails, type a title and search anyway. If one provider is unavailable, the others still return
results and the panel names the failure on that provider's card. Google Books can answer HTTP 429
without an API key; this is reported, not hidden.
## Wishlist

### Folders
**Create folder**, name it, and open it. A folder shows its **sessions** (each matching/search
operation) and any items saved directly into it. Open a session to see its results, with bookmarked
results first.

* Folder delete removes its sessions; bookmarks and items shared with other folders stay.
* Session delete removes that session's results; bookmarks and other sessions stay.

### Bookmarks
**Create bookmark** accepts a required title and an `https://` URL (other fields are optional and
stored exactly as typed). Bookmarks:

* survive restarts and do not depend on the current image, OCR output or session cache;
* can be filtered by title/domain and sorted by Added, Title or Domain (display only);
* can be opened in the browser with **Open website**;
* can join a folder with **+ Add to wishlist** at any time, including after a restart.

### Batch actions: select several items and act once

This is the "shopping app" workflow: select many rows, then apply one action.

| Where | How to start selection |
|---|---|
| **Wishlist > Folders** | the **Select folders** button under *Create folder*, or long-press a folder card |
| **Wishlist > Bookmarks** | the **Select bookmarks** button under the filter, or long-press a bookmark |
| **Inside a folder** | **Select sessions** / **Select items** above the list, or long-press that row |
| **Inside a session** | **Select results** above the results, or long-press a result |

While selection mode is on:

* the panel at the top shows **n selected**, a one-line explanation, and **Select all / Clear / Done**;
* every eligible row shows a round check mark, and tapping a row toggles it (it never opens it);
* **Select all** selects only the rows currently shown, never a hidden one;
* **Done** (top panel or bottom bar) or the system Back button leaves selection mode;
* the bottom bar repeats the count and holds the actions, so they stay reachable on a phone.

| List | Copy | Move | Delete |
|---|---|---|---|
| Bookmarks | into any wishlist folder; the bookmark stays | not offered (a bookmark *is* a Bookmarks membership) | removes the bookmark only |
| Folders | a copy of the folder with copies of its sessions and direct items | not offered (folders are flat here) | the folder with its sessions and items |
| Sessions (profiles) | a new session with the same results | to another folder | the session and its results |
| Saved items in a folder | into any folder | to another folder | removes it from that folder only |
| Results in a session | into a folder | into a folder, leaving the session | removes it from the session, never a comparison row |

What each action really does:

* **Copy** adds a membership in the destination. The source is unchanged, the same item is never
  duplicated, and an existing membership is skipped and counted in the report.
* **Move** removes only the membership in scope (this folder, or this session) and adds the target
  one. A bookmark, and any other folder or session membership, survives.
* **Delete** asks first, naming the count and the scope, e.g. "Remove 3 bookmark(s)? Only the bookmark
  is removed. Wishlist folders and sessions keep their copy." Cancel changes nothing.
* **Nothing is guessed.** After every batch the list is re-read from storage; a partial result is
  reported ("2 skipped (already there)", "1 failed") and the screen shows exactly what was persisted.
* **No undo.** The store has no safe reverse write, so the confirmation dialog is the safety step.
* Workbook comparison rows are never promoted, moved or deleted one by one.
* A selection holds **one** kind of row at a time (bookmarks, or folders, or sessions, or items, or
  results), so a mixed selection cannot silently skip an incompatible item. Switching lists always
  starts a fresh selection.
* A result selected inside a session moves **into a folder**, not into another session: a session is
  the record of one search and its results are not rewritten by a later action.

Manual check on a device (no emulator was available for this build):

1. Bookmark three results, open **Wishlist > Bookmarks**, tap **Select bookmarks**.
2. Tap all three rows: the panel says *3 selected* and each row shows a check mark.
3. Tap **Copy**, choose a folder, confirm: the folder shows the three items and the bookmarks remain.
4. Select the same three again, tap **Remove**: the dialog names *3 bookmark(s)*; **Cancel** keeps them.
5. Repeat and confirm: the bookmarks disappear, the folder still shows its copies.
6. In **Folders**, select two folders, **Copy**: two new "(copy)" folders appear with the same contents.
7. Inside a folder, select two sessions, **Move** to another folder: they disappear here and appear there.
8. Inside a session, select a result and **Move** it to a folder: the session keeps its other results.
9. Press **Done**: tapping a row opens it again instead of selecting it.
10. Restart the app: the persisted lists match what the screen showed before the restart.

## More

* Online switch, auto-search, OCR language and translation language.
* A plain description of offline matching, OCR, catalogue search, visual search (not implemented),
  the browser-only providers and prices/stock (not supplied).
* Storage counts and **Clear online session cache**.

## Three bookmark workflows

1. **Wishlist first, bookmark second** - save results to a folder, then bookmark a result. It stays
   in both places.
2. **Bookmark first, wishlist second** - bookmark a result (no folder is created), then use
   **+ Add to wishlist** and choose/create a folder.
3. **Bookmark first, add later** - restart the app, open **Wishlist > Bookmarks**, choose the
   bookmark and add it to a folder. No image/workbook/session is needed.

## Data and privacy

Everything is stored locally in the app's private storage. Offline matching sends nothing. With
online search on, the cover JPEG goes to Google Lens and the recognised text goes to the three
providers the app queries: Open Library, Google Books and DLsite. The seven browser-only cards
(Melonbooks, Suruga-ya, Doujin no Mori, E-Hentai, Doujinshi.info, MyAnimeList, nhentai) are never
contacted by the app - their search pages open in your browser. No account, analytics or ads. The app
refuses non-HTTPS links.

## Troubleshooting

| Symptom | What to check |
|---|---|
| "chrome-lens-py could not be loaded" | The embedded Python runtime failed; see `adb logcat -s OnlineSearch:V python.stderr:V`. Offline matching still works. |
| Online search is off after restart | The switch is persisted; open **More** and turn it on. The Search panel reflects the same preference. |
| No workbook images | Check the range and that pictures are anchored or stored as cell notes in the first sheet. |
| Google Books 429 | Shared networks without an API key are throttled; Open Library and DLsite results still appear. |
| A card says "Browser only" | Expected for Melonbooks, Suruga-ya, Doujin no Mori, E-Hentai, Doujinshi.info, MyAnimeList and nhentai. Tap **Open** to search there in your browser; the card cannot be selected. |
| I cannot find the batch actions | They appear only when the list has items. Use **Select folders / Select bookmarks / Select sessions / Select items / Select results**, or long-press any row. **Done** exits. |
| A batch says "n skipped" | Those items were already in the destination, or they are read-only comparison rows. Nothing was overwritten. |
| A batch says "failed" | The write did not happen; the list is re-read from storage, so what you see is what was saved. Try again. |
| I asked for 50 results and got fewer | That is the maximum, not a guarantee - Google Books stops at 40 and DLsite lists one page. The header says how many cards you actually have. |
| Bookmark link will not save | Only `https://` URLs with a host are accepted. |