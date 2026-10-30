# BookCover Matcher - User Guide (v2.4.0-dev.4)

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
* **Online (n)** - the OCR text (read-only but selectable), the editable query, the eleven platform cards,
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

**Choose your platforms.** The panel shows eleven cards in `ProviderCatalog` order:

* Searched in the app (selectable): **Open Library**, **Google Books**, **DLsite**.
* Browser-only (never selectable): **Melonbooks**, **Suruga-ya**, **Doujin no Mori**, **E-Hentai**,
  **Doujinshi.info**, **MyAnimeList**, **nhentai**, **Toranoana**.

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
* **Toranoana** - `ec.toranoana.jp`; it publishes no documented public API (only its own storefront
  HTML page, which answers but has no stability contract) and the catalogue is 18+ with the age gate
  answered on the site. Its `robots.txt` does **not** forbid the search route. **Open** searches
  `?searchWord=<your query>` in your browser.

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
* Every folder row has a **pencil** (rename) next to the bin, and so does every **result profile** row:
  the same dialog, the same rules (the name is trimmed; an empty name keeps the current one; two
  profiles may share a name), and the profile keeps its id, folder, results and bookmarks.

### Bookmarking a result that is inside a profile
Open a profile and each result row offers **★ Bookmark**. It saves that work in `Bookmarks` **and
leaves it in the profile**, because a profile entry and a bookmark are two independent references to
the same work: nothing is copied, nothing is duplicated, and removing one never removes the other.
Tapping **★ Bookmarked** again removes only the bookmark.

### Bookmarks
**Create bookmark** accepts a required title and an `https://` URL (other fields are optional and
stored exactly as typed); an optional image can be chosen, replaced or removed in the same form.
Bookmarks:

* survive restarts and do not depend on the current image, OCR output or session cache;
* are filtered by title/domain and sorted by Added, Title or Domain from the drawer under the list
  header. The strip itself is transparent; pull the blue grip (or tap the strip) to hide or show the
  panel. It animates open and closed, remembers whether it was open, and the filter text, the sort
  choice and any expanded card survive a tab switch;
* are drawn with the same card as the Search results: cover, title, artist line, price or identifier,
  source and match-type chips, and a chevron that expands the description, price, availability,
  identifier, artist, circle, source and the original URL. The pencil (**Edit bookmark**) and the red
  bin sit at the end of the tag row;
* can be opened in the browser with **Open website**;
* can join a folder with **+ Add to wishlist** at any time, including after a restart.

### Batch actions: select several items and act once

This is the "shopping app" workflow: select many rows, then apply one action.

| Where | How to start selection |
|---|---|
| **Wishlist > Folders** | the **Select folders** button under *Create folder*, or long-press a folder card |
| **Wishlist > Bookmarks** | the **Select bookmarks** button under the filter (open the drawer if it is closed), or long-press a bookmark |
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
* The **Workbook comments & images** entry (legacy note text and pictures, offline).
* Storage counts and **Clear online session cache**.

### Workbook comments & images

**More → Open workbook comments** reads the legacy Excel notes of the workbook you chose on Home. It
works completely offline and never changes the workbook.

* **Comments** lists each commented cell. The marker says what it holds: **T** text only, **▣** one or
  more images, **!** an image reference that could not be resolved. The sheet chips narrow the list; tap
  a row for the author, the full note text and the pictures.
* **All images** shows every picture stored in the workbook, including the ones no comment uses (marked
  *No association found by this previewer*).
* Tap a thumbnail for a full-screen view. **Export** saves the image's original bytes to a location you
  choose; the workbook itself is never touched.
* Reopening the same workbook reuses the cached result (it says *cached* next to the name); **Rescan**
  reads it again.
* Threaded comments are the newer kind and are not notes: the screen reports when they exist, but only
  legacy note pictures are shown.

### Finding the worksheet cell of an image (v2.4.0-dev.1)

Every picture and every catalogue record can be traced back to where it lives in the workbook, but only
when the workbook itself proves the link.

* **From an image.** In **All images**, a linked row shows **Go to source** (the list) or **Source ↗** (the
  grid); the full-screen view has the same action. The app opens the worksheet at the cell the picture's
  note is attached to.
* **From a comment.** Open a comment and press **Go to this cell**.
* **From the catalogue.** A record has **Go to row**; the worksheet opens at that row, with the row marked
  as a row — the cell shown next to it is the row's first field, not a claim that the record is a cell.

What the view shows: the worksheet's own column letters, the verified row highlighted, the verified cell
marked, the catalogue header when the sheet is a table, and six rows of context either side. **Back to the
list** (or the system Back) closes it and leaves the section, the worksheet filter, your search text, the
filters, the sort order and any selection exactly as they were.

When there is nothing honest to show, the app says so instead of inventing a place:

* **No association found by this previewer** — the picture exists but no note points at it, so there is no
  source location and no button.
* **Unresolved reference** — a note points at a picture that is not in the workbook. The comment still
  shows the note's cell, but the picture itself has no location.
* **A note with several locations** lists all of them and lets you choose.
* **The workbook is no longer available** — if the file was moved or deleted after the preview opened, the
  view says so instead of drawing an empty grid.

### Checking a workbook: Insights (v2.4.0-dev.2)

The preview has a fourth tab, **Insights**, with five screens, in this order: **Health, Analytics, Gallery,
Timeline, Fields**. All of them read the same records, so a problem you see in *Health* is the same record you
see in *Gallery* and on the *Timeline*. Nothing here changes your workbook, and everything works offline.

The **Insights** entry in the tab row shows two lines: the name, and the number of confirmed problems
underneath (`3 errors`, `0 errors`, `reading…` while the report is being built, `—` before it exists). The
count never disappears, so you can always tell whether a workbook has been checked.

* **Health** checks the workbook and lists what is wrong: worksheets that could not be read, records with no
  title, missing optional values, a URL or a date that cannot be read, the same product ID twice, rows that
  look duplicated, a note that points at a picture the workbook does not contain, pictures nothing points
  at, and records with no verified cover. Each card says which worksheet row or picture it is about, why,
  and what to do. **Errors** and **Warnings** are confirmed problems; **Info** is missing optional data or a
  coverage note. The chips filter by severity and by area, and **Go to source** opens the row through the
  source view from an earlier release. The report never repairs anything.
* **Analytics** counts records, worksheets, covers, missing values, duplicate IDs and possible duplicates,
  and groups them by Circle, Artist, tag, worksheet and publication year. The rules are printed on the
  screen: one record is one row of the sheet, a blank cell counts as *missing* and never as zero, and
  possible duplicates are shown but not subtracted from the totals.
* **Gallery** shows one card per record with its cover, title, Circle, Artist, product ID and source row.
  Filter for *With cover*, *No cover*, *Missing title* or *Missing metadata*, search the text, and sort by
  title, Circle, Artist, source order or cover. A record with no cover shows *No cover* — nothing is filled
  in to make the card look complete. **Open** shows the cover full screen; **Source** opens the row. The
  search bar sits in its own band above the filter chips, separated by a hairline.
* **Timeline** groups records by year, newest or oldest first, and keeps records whose date is absent or
  unreadable in **No date known** — they never disappear. **Published** and **Acquired** read the two columns
  you assigned (see below) and never borrow each other's; **Imported** uses the date the saved copy was
  imported. **Go to row** opens the source view for that record. It can be searched and limited to a year
  range, and it starts at the top when you switch between Published and Acquired.
* **Fields** maps the worksheet's own columns to the app's fields: Title, Circle, Artist, Product ID, URL,
  Price, Description, Publication date and Tags. Tap a field, pick the column, and the preview under the
  list shows what the records will look like. Orange issues mean a column is missing, appears twice, or is
  used by two fields. **Apply mapping** uses it for the reports; **Save profile…** stores it by name so the
  same layout can be reused on another workbook. A profile remembers column *names*, so inserting a column
  in Excel does not break it, and Circle and Artist stay two different fields.

### Filtering Health, Analytics and the Timeline (v2.4.0-dev.4)

**Which worksheets a report covers.** *Health* and *Analytics* each begin with a **Worksheets:** bar — *All
worksheets*, a single name such as *LIST*, or *2 of 3 worksheets*. Tap it to open the picker: every worksheet
of the open workbook is listed with its record count (and marked hidden or "no catalogue table" where that
applies). Tick any combination and press **Use these worksheets**; **All worksheets** clears the selection.
The numbers are then recalculated for that selection — tiles, error/warning/info counts, category chips,
duplicate groups, the distributions, the year breakdown and the cover totals — not just filtered on screen.
Health and Analytics keep separate selections, and a worksheet that no longer exists is dropped from the
selection (if nothing is left, the report goes back to *All worksheets*).

**Searching the timeline.** In *Insights → Timeline*, the search field matches a record's title, Circle,
Artist, product ID and worksheet row. **Years: …** opens a list of the years that timeline actually has, and
you can set a *from* year and a *to* year independently (*Any year* clears one). While a filter is active the
row shows *x of y record(s)* and a **Clear filters** action; if nothing matches you get "No record matches
this filter" and the same reset. Each date meaning keeps its own filter, so a search you typed for
*Published* does not narrow *Acquired*. Records whose own date is unknown are never given a year to fit a
range: they are left out and the screen says how many.


### Every column, and the two date columns (v2.4.0-dev.3)

**Seeing all columns.** The *Catalogue* tab now has a **Cards / Table** switch. *Cards* is the record list you
already know. *Table* draws the worksheet itself: every column the sheet uses, in the workbook's own order,
with Excel's column letter above each header, the row number held still on the left, and one sideways scroll
that moves the header row and the values together — so `ツイッター`, `ツイッター (ユーザー名)`, `ミスキー` and
`ミスキー (ユーザー名)` at the right edge of a wide sheet are reachable instead of cut off. Tap a row number to
open that row in the source view. A column whose header cell is blank still appears, labelled with its letter
(*Column C*), because the values below it belong to it.

**Choosing the date columns.** In *Insights → Timeline*, press **Choose date columns…**:

1. Pick the worksheet (only shown when the workbook has more than one catalogue sheet).
2. Choose the row **Published timeline** or **Acquired timeline** — they are independent.
3. Tap the column that holds that date. The dialog shows how many of its values can be read and a few real
   examples (`43465 → 2018-12-31`), and it never chooses a column for you, even when a header says
   `出版日付`.
4. **Save** keeps it for this workbook and this worksheet; **Clear both** removes both; **Cancel** changes
   nothing.

The assignment is remembered for that workbook only, and it survives closing the app. If the column is later
renamed or deleted, the timeline says the column is gone and asks you to choose again instead of reading a
neighbouring column. Excel date serial numbers (a cell that shows a date but stores `43465`) are read as dates
in the columns you picked.

### Saved workbooks (More → Saved workbooks)

Importing a workbook here keeps a private copy inside the app, so it still opens if the original file
moves or is deleted. The list shows the name you gave it, the original filename, its size, when it was
last opened and (after the first open) how many sheets, comments and images it has.

* **Rename** changes your label only; the file is never renamed.
* **Pin** keeps a workbook at the top; the rest are ordered by last opened.
* **Remove** deletes the app's copy and that workbook's parsed cache. The original file you imported is
  not touched.
* **Storage** reports the copies and the regenerable caches separately. **Clear parsed caches** frees the
  caches and keeps every saved workbook.
* **Compare** on two rows (then **Compare** in the selection bar) opens a read-only comparison of the two
  saved workbooks. It matches worksheets by **name**, so a renamed sheet is shown as one removal plus one
  addition rather than silently paired. You get workbook order, per-sheet visibility, each sheet's
  declared used range, comment counts, verified image links, unresolved references, media and unreferenced
  media, plus catalogue record/header differences. Values the parser could not establish are shown as
  *unknown* and never as `0`; if one side cannot be read, that side is marked as failed instead of showing
  a comparison of zeros. Identical totals are not presented as proof that the workbooks are the same, and
  this is not a cell-by-cell diff — the app never writes to either workbook and never runs macros.

### Worksheet picker, images and catalogue

* On **Home** and the **Search → Workbook** tab, *Saved* uses a workbook you already imported
  (More → Saved workbooks) as the scan source; *Choose file* keeps the original external selection.
* **Name** sorting in the Images view is natural: `image2.jpg` comes before `image10.jpg`, and the
  association chips (*All* / *Linked* / *Unreferenced* / *Unresolved*) are mutually exclusive.
* **Sheets** (in the preview header) lists every worksheet with a search box, hidden sheets labelled, so
  you never have to swipe an overflowing tab strip.
* **Images** has a search box (file name, sheet, cell, title, author, circle…), filter chips with counts
  (linked / unreferenced / unresolved), sorting, grid (with S/M/L thumbnail size) or list, and multi-select → **Export…** into a
  folder you pick. A `manifest.csv` is written next to the copies. Unresolved references are listed
  separately and never called unreferenced. A linked image also offers **Go to source** (see *Finding the
  worksheet cell of an image*).
* **Catalogue** finds the workbook's own table from its header row and searches all of its columns; each
  record says whether a verified image is linked to it, and **Go to row** opens its worksheet row.

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
providers the app queries: Open Library, Google Books and DLsite. The eight browser-only cards
(Melonbooks, Suruga-ya, Doujin no Mori, E-Hentai, Doujinshi.info, MyAnimeList, nhentai, Toranoana) are
never contacted by the app - their search pages open in your browser. No account, analytics or ads. The
app refuses non-HTTPS links.

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
| DLsite shows no results | Try a Japanese title; DLsite is searched on its first page only. Multi-word titles work as one phrase (the app sends them the way DLsite's own form does) - fixed in 2.2.3. |
| I cannot find the rename pencil on a profile | Open the folder that holds it: the pencil sits next to the bin on each result-profile row, exactly like folders. |
| Bookmarking a profile result did nothing | If the row already says **★ Bookmarked**, the work is already in `Bookmarks`; tapping again removes only the bookmark, never the profile entry. |
| Bookmark link will not save | Only `https://` URLs with a host are accepted. |