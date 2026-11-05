# BookCover Matcher - User Guide (v2.4.0)

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
### Reverse-image discovery (v2.5.0-dev.3)

Under **C · Reverse-image discovery** the Search tab can look for the *cover itself*, not just the text on it.

1. **SauceNAO is tried first.** It needs your own SauceNAO API key: paste it once and tap **Save key**; the app
   stores it encrypted with an Android Keystore key. Afterwards it shows only `••••••••ab12`, with **Replace** (enter
   a new key without losing the working one) and **Remove**. If saving ever fails, the message says *why* — a key the
   device could not store is reported as a storage problem, not as a bad key. Without a key the row says
   **NEEDS KEY** and nothing is sent.
2. **IQDB is manual-only.** IQDB publishes no API and its `robots.txt` disallows its own search routes, so the app
   does not submit your image to it. Tap **Open ↗** on the IQDB row, search there in your browser, then paste what
   you found into the **Paste an IQDB result** box — it is always visible, one result per line, e.g.
   `92% https://danbooru.donmai.us/posts/123`. Those rows are labelled as yours, not as an automated result.
3. Tap **Search by this cover**. The two rows show their own states as they go — searching, results, nothing found,
   timed out, rate limited, quota used up, blocked, manual only — and SauceNAO's results appear while IQDB is still
   being handled. **Cancel** stops the run; nothing else is sent after it.
4. Each candidate shows what it is, **who found it and with what number** (`SauceNAO 95.1% · IQDB 92%`), and the
   SauceNAO row also shows the provider's remaining quota. The two numbers are never added together or averaged:
   they belong to different services.
5. **Confirm** or **Reject** records *your* decision for that candidate and remembers it (per wishlist profile).
   Until you do, the card says **Suggestion — not confirmed by you**. Confirming does not edit the workbook and does
   not add a wishlist entry — those stay separate, explicit actions.
6. **Removing an IQDB card:** a card that IQDB produced has a **rubbish bin** at its top-right. Tapping it removes
   *that card only* from the list on screen. It deletes nothing else: no other card, no SauceNAO result, no saved
   decision, no bookmark, no wishlist entry. A later search may show the result again.
7. **Found a listing yourself?** Use the **Wishlist** tab: it owns bookmarks and manual listings, keeps them apart
   from online results and prevents duplicates. The Online tab is for searching, not for creating listings.

**General-book sources** — the switch above the search box — turns Open Library and Google Books on or off. Off
means **not queried at all** (not "searched and empty"), and DLsite, the doujin source, is never affected.


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

**A cell in this view behaves like a cell in the table.** It is drawn the way the workbook shows it — a date
column reads `2024-01-01`, a percentage `12.35%`, an ordinary number keeps its digits — and a cell the
workbook links is blue and underlined. Tap any cell (the header cells included) to open the same dialog the
Catalogue table uses: the whole value, **Copy**, **Open Link** when the workbook really stores a destination,
and **Back**. Copy takes what you see, and Open Link goes to that cell's own link; the dialog names the
worksheet and the exact cell (`LIST!F12`) plus the column's header, and it always describes the cell you
tapped, whatever you have scrolled to.

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
  title, Circle, Artist or source order. A record with no cover shows *No cover* — nothing is filled
  in to make the card look complete. **Open** shows the cover full screen; **Source** opens the row. The
  search bar sits in its own band above the filter chips, separated by a hairline.
* **Timeline** groups records by year, newest or oldest first, and keeps records whose date is absent or
  unreadable in **No date known** — they never disappear. **Published** and **Acquired** read the two columns
  you assigned (see below) and never borrow each other's. **Go to row** opens the source view for that record.
  It can be searched and limited to a year range, and it starts at the top when you switch between Published
  and Acquired.
* **Fields** maps the worksheet's own columns to the app's fields: Title, Circle, Artist, Product ID, URL,
  Price, Description, Publication date and Tags. Tap a field, pick the column, and the preview under the
  list shows what the records will look like. Orange issues mean a column is missing, appears twice, or is
  used by two fields. **Apply mapping** uses it for the reports; **Save profile…** stores it by name so the
  same layout can be reused later. A profile remembers column *names*, so inserting a column in Excel does not
  break it, and Circle and Artist stay two different fields. It also remembers **one mapping per worksheet**,
  because two worksheets rarely have the same header row: saving while one worksheet is open keeps every other
  worksheet's own mapping instead of copying the one on screen over them.

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

### Choosing the worksheets a gallery shows, and reading dates (v2.4.0)

**Limiting the gallery to some worksheets.** In *Insights → Gallery*, the filter area starts with
**Worksheets: …**:

1. Tap it. The picker lists the workbook's own worksheets, each with its record count and whether it is
   hidden.
2. Choose **All worksheets**, one worksheet, or any combination. **Use these worksheets** applies the choice;
   **All worksheets** clears it; **Cancel** changes nothing.
3. The bar then reads *Worksheets: LIST* or *Worksheets: 2 of 3 worksheets*, and the count line says how many
   records the grid is showing out of the ones those worksheets hold.

The choice is a filter on the records themselves: *Search*, *With cover*, *No cover*, *Missing title*,
*Missing metadata*, every sort order and the scroll reset all work within it, so *LIST* + *No cover* shows
exactly that worksheet's coverless records. Gallery, Health and Analytics each keep their own choice. A
worksheet you renamed in Excel stays selected; one you deleted is dropped from the selection rather than
leaving an empty grid.

**A blank row inside a catalogue is reported, not hidden.** A sheet ends a table with an empty row, so the
app stops reading there — that is deliberate, because rows below could be an unrelated block. When data
does continue below the blank row, *Catalogue* now says so under the record count ("LIST: 5 rows below
empty row 5 not read"), the Table view repeats it under that worksheet's header, and *Insights → Health*
lists it as a warning with the row numbers. Nothing about the stop rule changed: the record count is
still the rows above the gap.

**The image range a fresh install starts on.** The matcher reads pictures from a cell range; it used to
start at `A1:B20`, which is narrower than the doujin layout (24 columns in `A:X`, covers in the title
column `D`), so a first run could find nothing. It now starts at `A1:Z5000`, and a range you type
yourself is kept. If a scan still finds no images, the message names the range it used and reminds you
that covers usually sit in the title column.

**Dates and percentages in the Catalogue table.** In *More → Workbook comments → Catalogue → Table*, a cell
that Excel formats as a date is shown as a date (`2018-12-31`, or `2018-12-31 14:30` when it also carries a
time) instead of the serial number behind it (`43465`), and a cell formatted as a percentage is shown rounded
to two decimals (`12.35%`) instead of its stored fraction (`0.123456`). A price, a page count, a rating or a
product number is left alone: the app reads each cell's own format, so a bare number is never turned into a
date. Your workbook is only read — nothing is written back, and the stored values keep the digits they had.

**Timeline has two meanings.** *Published* and *Acquired*, each reading the column you chose, with their own
search and year range. There is no *Imported* mode any more.

**The order inside one date.** *Insights → Fields → Default order* lets you pick the column that says what
order your collection is in (a serial number, a shelf position — any header, in any language). The card has
its own buttons: **Change** (or **Choose column** when nothing is mapped yet) opens the same column picker the
other fields use, **Clear** unmaps it, and **Use detected** puts back the column the app found by itself — a
line under the buttons says which column that is. The preview shows what the chosen column holds: how many
values are numbers, how many are text, how many are blank, and a few examples. It is optional, and each
worksheet remembers its own mapping, so it is still in effect next time. When several records share a Published
or Acquired date, the timeline lists them by this column ascending (`2` before `10`); a record with no value
comes last and keeps its worksheet row as the final tie-break. Nothing is filled in for it.

**A mapping belongs to one worksheet.** *Fields* shows the worksheet you pick at the top, and everything you do
there — **Apply mapping**, **Save profile** — changes that worksheet only. The other worksheets keep their own
mappings, so a profile saved while one worksheet is open cannot blank out another worksheet's fields: saving
carries every other worksheet's mapping forward (from the profile you were viewing, or from its own detected
headers), and a blank you clear stays cleared on that worksheet alone.

**Detected and your profiles are views you switch between.** The chips under the picker choose which
configuration is shown *and used*: **Detected** is what the app finds in the workbook by itself, and a saved
profile is what you stored. Picking one does not copy anything into the other — switch to your profile, back to
**Detected**, and back again, and each one is exactly as it was. **Detected** is blue whenever the mapping in
effect is the one the app would have found by itself: applying an unchanged mapping keeps it blue (and stores
nothing), while changing an assignment, clearing one, or viewing a profile that differs turns it grey. Putting
the detected column back — or pressing **Detected** — makes it blue again. Switching also drops edits you applied
but did not save, so use **Save profile** for anything you want to keep.

**Your selections are remembered.** The worksheet filter of Health, Analytics and the Gallery — each one
separately — and the year range of each timeline come back when you reopen the workbook or restart the app.
They are stored per workbook in the app's own folder (`files/mappings/insights-views.json`); a selection that
names a worksheet the workbook no longer has is dropped rather than applied to a different one.

**Numbers show at most two decimals.** A similarity score reads `0.81`, and a workbook cell whose Excel
format declares decimals reads `1234.57` instead of `1234.5678`. Whole numbers, identifiers, serial numbers,
row numbers and dates are never reformatted, and nothing is rounded before it is saved, sorted, filtered or
exported.

**Rescanning while you are in Insights.** The **Rescan** button in the preview header re-reads the workbook
from the file. Every Insights function is rebuilt immediately — Health, Analytics, Gallery, Timeline and
Fields — so you do not have to leave the directory and come back. Your profiles, worksheet selections,
filters and chosen date columns are kept, the panels say they are reading while it runs, and if the file
cannot be read the reason appears in place of the reports. The button is the same blue as **Import** on
*More → Saved workbooks*.

**Working with a table cell.** In *Catalogue → Table*, tap any cell to open a dialog in the middle of the
screen showing that cell's value:

* **Copy** puts the value you see on the clipboard (the displayed text — not the link's address, and an empty
  cell says it is empty).
* **Open Link** appears only when the workbook itself stores a link for that cell. A web address opens in your
  browser; a link that points at another place in the workbook opens that cell in the app's own source view.
* **Back** closes the dialog, and so does a tap outside it.

Cells the workbook links are drawn in the accent colour and underlined, so you can see them before tapping. A
cell whose text merely looks like a web address is *not* a link: only what the file stores counts. Links the
app cannot follow (a local `file://` path, a defined name, an address no installed app handles) are shown with
their destination but offer no **Open Link**. The **source location** view uses this same dialog for the
worksheet cells it draws, so a value, a link and a copy behave identically in both places.

**The worksheet picker** is opened from the section you are in — *Comments*, *Images* or *Catalogue* — rather
than from a single button in the header: Health, Analytics and the Gallery keep their own independent
worksheet scopes, and a shared button suggested otherwise.

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