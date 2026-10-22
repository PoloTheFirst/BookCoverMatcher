# BookCover Matcher — User Guide (v2.2.0)

This guide covers the four tabs and the saved-data rules. For build instructions and architecture,
see `README.md` and `ARCHITECTURE.md`.

## Home

1. **Choose file** (`.xlsx` or `.xlsm`) and enter the cell range that contains the cover pictures.
   You can also choose an image from the device instead of using the camera.
2. **Scan Book Cover** or turn on the crosshair button for **Auto Scan**.
3. **Link & Find Top 5** compares the captured cover with every picture in the selected range.
4. The comparison is shown on **Search**. Nothing is saved yet.

The workbook is only read on the device. Offline matching works in airplane mode.

## Search

### The three result lists
**Search Results** has three tabs and each label carries a live count:

* **Workbook (n)** - the offline comparison: spreadsheet controls, the five match rows and
  **Save this scan as a session**. The count is the number of workbook matches for the current scan.
* **Online (n)** - OCR, the editable query, the provider status list and the results from Open
  Library, Google Books and DLsite. Melonbooks and Suruga-ya are listed with a browser search link
  instead of being queried. The count is the number of unique, displayable results; failures and
  placeholders are not counted.
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
first — a scan never creates a folder by itself.

### Online search
Turn on **Use online services** (or use **More**). The first time, the app starts its embedded Python
runtime and asks whether `chrome-lens-py` is available.

1. The captured cover is read with OCR. The query is filled from the title that was read; edit it if
   needed.
2. **Search catalogues** queries three providers at once: **Open Library**, **Google Books** and
   **DLsite**. The provider panel above the results shows what each one actually did.
3. Result cards show a real cover (or a placeholder), title, provider chip, price for a shop listing,
   source domain, bookmark state and wishlist state. Tap the card body to expand.
4. Expanded actions are separate: **Open website**, **Bookmark**, **+ Wishlist**.
   * **Bookmark** saves the link in `Bookmarks`.
   * **+ Wishlist** adds only that one item to a folder you choose.
   * **Bookmark all results** saves every result on screen to `Bookmarks`; it creates no folder.
   * **Add entire result profile to wishlist** saves the offline top-5 and the online results as one
     session in the chosen folder.
5. **Clear session cache** removes temporary results only.

**Melonbooks** and **Suruga-ya** are listed in the provider panel but are *not* searched: both sites
refuse automated clients (HTTP 403), so the app never disguises itself to get in. Their row says so
and offers **Open ↗**, which opens that shop's own search page for the current title in your browser.

If OCR fails, type a title and search anyway. If one provider is unavailable, the others still return
results and the panel names the failure in that provider's row. Google Books can answer HTTP 429
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

## More

* Online switch, auto-search, OCR language and translation language.
* A plain description of offline matching, OCR, catalogue search, visual search (not implemented)
  and prices/stock (not supplied).
* Storage counts and **Clear online session cache**.

## Three bookmark workflows

1. **Wishlist first, bookmark second** — save results to a folder, then bookmark a result. It stays
   in both places.
2. **Bookmark first, wishlist second** — bookmark a result (no folder is created), then use
   **+ Add to wishlist** and choose/create a folder.
3. **Bookmark first, add later** — restart the app, open **Wishlist → Bookmarks**, choose the
   bookmark and add it to a folder. No image/workbook/session is needed.

## Data and privacy

Everything is stored locally in the app's private storage. Offline matching sends nothing. With
online search on, the cover JPEG goes to Google Lens; the recognised text goes to Open Library,
Google Books and DLsite. Melonbooks and Suruga-ya are never contacted by the app — their search pages
open in your browser. No account, analytics or ads. The app refuses non-HTTPS links.

## Troubleshooting

| Symptom | What to check |
|---|---|
| “chrome-lens-py could not be loaded” | The embedded Python runtime failed; see `adb logcat -s OnlineSearch:V python.stderr:V`. Offline matching still works. |
| Online search is off after restart | The switch is persisted; open **More** and turn it on. The Search panel reflects the same preference. |
| No workbook images | Check the range and that pictures are anchored or stored as cell notes in the first sheet. |
| Google Books 429 | Shared networks without an API key are throttled; Open Library and DLsite results still appear. |
| Melonbooks / Suruga-ya show "manual" | Expected: both sites refuse automated access (HTTP 403). Tap **Open ↗** to search them in your browser. |
| DLsite shows no results | Try a Japanese title; the app searches DLsite's books section, first page only. |
| Bookmark link will not save | Only `https://` URLs with a host are accepted. |
