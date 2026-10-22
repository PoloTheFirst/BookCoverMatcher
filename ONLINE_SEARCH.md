# Online search, OCR and bookmarks — implementation notes (v2.2.0)

This document describes what the online feature actually does. It deliberately separates four
capabilities that are easy to confuse:

| Capability | Implementation | Output |
|---|---|---|
| **OCR** | `chrome-lens-py` 3.8.2 inside Chaquopy, through `lens_bridge.py` | Recognised text, language, translation, glyph heights |
| **Text-based provider search** | `OpenLibraryBooksSource` + `GoogleBooksSource` + `DlsiteSource` | Structured records/listing with a real detail URL |
| **Visual image search** | **Not implemented** | No service, no credentials, no claim |
| **Catalog lookup** | Open Library and Google Books are catalogues; DLsite is a shop | A price only where the shop prints one |

OCR + text search is **not** visual cover matching. The UI labels catalogue results `Text match`
and the code never sets `MatchType.VISUAL`.

---

## 1. Request flow

```
Camera / gallery
  → PixelImage
  → AndroidImageCodec.encodeJpeg(OCR_MAX_EDGE)
  → OnlineSearchController.onCapture
      → SessionCache (SHA-256 of the JPEG)
      → ChromeLensPyBridge.recognise
          → PythonRuntime (Chaquopy, Python 3.12)
          → lens_bridge.ocr_image
          → chrome_lens_py.LensAPI.process_image(output_format="blocks")
          → flat envelope {ok, text, block_heights, detected_language, translated_text, …}
      → OcrText
  → OcrQueryExtractor.suggest (title/author)
  → OpenLibraryBooksSource.search + GoogleBooksSource.search + DlsiteSource.search
      (concurrent on Dispatchers.IO, 20 s deadline each)
  → OnlineAggregator.merge (provider key → dedupe → bookmarked promotion → limit)
  → CatalogueUi.Found
  → ProductResultCard → Bookmark / + Wishlist / Open website
```

If OCR fails, the query field remains editable; **Search catalogues** works without OCR. That is a
deliberate separation: the catalogue step does not require the Python bridge.

---

## 2. Providers

### Open Library Books API
* Endpoint: `https://openlibrary.org/search.json`
* Free, no API key; structured works/editions; cover ids; detail URLs are built from a validated
  `/works/OL…` or `/books/OL…` key.
* Failures map to `OFFLINE`, `TIMEOUT`, `RATE_LIMITED`, `SERVER_ERROR` or `BAD_RESPONSE`.

### Google Books Volumes API
* Endpoint: `https://www.googleapis.com/books/v1/volumes`
* Free for search, no API key shipped; structured volume records, covers and a books.google.com
  page.
* `http://` cover links are upgraded to `https://` on the same host. A missing/unsafe detail link
  falls back to `https://books.google.com/books?id=<validated id>`.
* **Without a key Google may answer HTTP 429** on shared networks. The outcome is
  `ProductSearchOutcome.Failed(RATE_LIMITED, …)`; the controller still shows the other catalogue's
  results and displays a note. It does not retry silently.

### DLsite (books search page)
* Endpoint: `https://www.dlsite.com/books/fsr/=/keyword/<percent-encoded query>`
* Public HTML page, no key, no cookie, no login. Measured live on 2026-10-22: HTTP 200 with **30 real
  works** for `東方`, and a genuine (empty) result page for a nonsense query — with the app's own user
  agent, not a spoofed browser one.
* Parsed by `core/online/DlsiteResponseParser` (plain Kotlin, fixture-tested, no HTML dependency):
  * id from `data-list_item_product_id` (`RJ…`), validated by regex;
  * title from `<dd class="work_name">`'s anchor (`title` attribute, else its text), entity-decoded;
  * circle from `<dd class="maker_name">`, kept as the author line;
  * the **current** price from the first `work_price_base`/`work_price_suffix` pair (the struck-through
    `<span class="strike">` price is the old one and is not used);
  * the 240×240 cover from the listing, upgraded to `https`.
* The listing has no stock, description or rating field, so those stay `null` and the card shows `—`.
* One request per user search; no pagination, no retry. `robots.txt` asks for `Crawl-delay: 10` and
  disallows the paged result URLs (`/fsr/=/*/per_page/*/page/`), which the adapter never requests.
* A 401/403/451 becomes `OnlineFailure.BLOCKED` — never a retry with a disguised client.

### Melonbooks and Suruga-ya — deliberately **not** queried
Measured on 2026-10-22, with both a Chrome user agent and the app's own:

| Request | Result |
|---|---|
| `GET https://www.melonbooks.co.jp/robots.txt` | **HTTP 403** |
| `GET https://www.melonbooks.co.jp/search/search.php?mode=search&name=東方` | **HTTP 403** |
| `GET https://www.suruga-ya.jp/robots.txt` | **HTTP 403** |
| `GET https://www.suruga-ya.jp/search?category=&search_word=東方` | **HTTP 403** |

A 403 on `robots.txt` itself means the site's rules cannot even be read. Both shops sit behind bot
protection that refuses non-browser clients, so there is no compliant automated route. The app
therefore:

* marks them `ProviderAccess.BROWSER_ONLY` in `ProviderCatalog`;
* never sends them a request (not even a probe);
* shows a row that says so, with **Open ↗** opening the shop's own search page for the query in the
  user's browser;
* never counts them in the result count or in "providers that answered".

If either shop ever publishes a documented search API — or starts serving plain HTTP clients — adding
it is one `ProductSearchDataSource` plus one `ProviderSpec` entry.

---

## 3. Identity and de-duplication

`ItemIdentity` defines "the same thing":

1. A stable provider key wins: `ol:OL27482W`, `gb:<volumeId>`, `dl:rj01730150`.
2. Otherwise a normalised URL: `https` forced, host lowercased, default port removed, fragment
   removed, trailing slash removed, only known tracking parameters stripped. Parameters that can
   change the destination are kept.
3. Workbook matches use `wb:<cell>`; two workbooks that share a cell therefore share an identity
   (documented limitation).

Editions/works with different URLs or titles are **not** merged by title. A DLsite listing and a
library record of a similar title are two results, not one. Shop listings rank as
`RANK_MARKETPLACE`, catalogue records as `RANK_CATALOGUE`; bookmarked items are promoted first. The
merged list is the displayed list, so "N results" always equals the number of cards.

---

## 4. Toggle, cancellation and cache

* Preference store: `bookcover_online` (`OnlineSearchPrefs`). `enabled` defaults to **false**.
* `OnlineSearchController` owns its state. `MainViewModel` mirrors it into `UiState`; this mirror
  is the fix for the release-blocking bug where the panel rendered the default off state forever.
* Disabling:
  * hides results immediately (`CatalogueUi.Idle`, OCR idle);
  * cancels the Kotlin `Job`;
  * `SessionCache.invalidate()` bumps a generation, so a late Kotlin callback is dropped;
  * does not start new requests;
  * never touches saved folders/sessions/items/bookmarks.
* A Python Lens call already running cannot be preempted. Its token becomes stale and its result is
  discarded.
* Cache key: SHA-256 of the cover JPEG; entries are in memory only. A different cover invalidates
  the previous results. `Clear online session cache` removes cache entries only.

---

## 5. OCR bridge contract

`lens_bridge.py` returns a flat dict; `OcrResultParser` is the only Kotlin file that interprets it.
`ChromeLensPyBridge` reads the dict through `PyObject.asMap()` (item lookup, not `getattr`), and
converts the Java `byte[]`/`jarray` with the buffer protocol. Failures are stable categories
(`bad_input`, `image`, `network`, `rate_limit`, `auth`, `timeout`, `api`, `internal`), not string
matching.

A Lens text block can contain a newline. `_flatten` collapses each block to one line so that
`block_heights` stays aligned with `text.splitlines()`; `OcrQueryExtractor` then pairs each height
with its source line **before** filtering noise, so an imprint line cannot shift the geometry onto
the wrong title candidate.

---

## 6. Bookmarks and wishlist sessions (online side)

* `Bookmarks` is a reserved folder in `records.json` (idempotent, not renameable/deletable).
* A bookmark is a `SavedItem` + membership in `Bookmarks`; it has no session and no folder
  requirement.
* A session is created only by:
  * saving an offline comparison (`OFFLINE_MATCH`);
  * saving an online result set (`ONLINE_SEARCH`);
  * the explicit bulk "result profile" action (`RESULT_PROFILE`).
* Individual `+ Wishlist` adds one item as a direct folder membership; it does not create a session.
* Session results are ordered with bookmarked items first. Removing a bookmark leaves folder
  memberships intact; removing a folder membership leaves the bookmark intact.
* All three workflows (wishlist→bookmark, bookmark→wishlist, bookmark→restart→wishlist) are covered
  by JVM tests and were exercised manually for the custom-bookmark case.

Schema details and the full migration rules are in `README.md` §3.

---

## 7. Verification (actual, not planned)

| Item | Command / environment | Result |
|---|---|---|
| JVM suite | `.\gradlew.bat testDebugUnitTest` | 184 tests, 0 failures (3 live checks skipped by design) |
| Live provider checks | `.\gradlew.bat testDebugUnitTest -DonlineCheck=1` | 184 tests, 0 failures; all three live checks **ran**: Open Library and DLsite returned real records, Google Books was found or `RATE_LIMITED` |
| DLsite live parse | `DlsiteSource().search("東方", 3)` | real `RJ…` works with Japanese titles, circles, `924円`-style prices, `img.dlsite.jp` covers and `dlsite.com` product URLs |
| Melonbooks / Suruga-ya | `robots.txt` + search URL, two user agents (2026-10-22) | HTTP 403 on all four requests → browser-handoff only |
| Python bridge | `python tools/lens_bridge_check.py` with chrome-lens-py installed | all checks passed |
| Real OCR, desktop | chrome-lens-py 3.8.2, Open Library Hobbit cover | read `THE HOBBIT` / `JRR TOLKIEN`, heights present |
| Real OCR, device | `connectedDebugAndroidTest` on `Medium_Phone_API_37.0` | passed in ~2.2 s |
| Device catalogues | same run | both sources answered or were correctly classified; Open Library returned records |
| Device controller | same run | `CatalogueUi.Found` published with merged results |

Google Books returned HTTP 429 from the development network without a key; this is classified as
`RATE_LIMITED` and is not hidden. A configured API key/billing is a deployment decision the app does
not make for the user.

### Not verified
* Visual search (not implemented).
* OCR on a physical phone with a real camera (emulator OCR used a bundled image).
* Online result cards driven through the UI end-to-end (controller and sources are covered by
  device tests; card interactions were visually smoke-tested only indirectly).
* Google Books with an API key.
* **Melonbooks and Suruga-ya as searches** — HTTP 403 from this environment; only their generated
  browser links are verified.
* **DLsite on a device** — the live check ran on the desktop JVM through the same adapter.

---

## 8. Configuration and costs

* No account, key or login is required for OCR or any of the three automated providers.
* `chrome-lens-py` uses Google's public Lens `crupload` endpoint and its own built-in key; Google may
  rate-limit it. The app reports `RATE_LIMITED`/`AUTH` honestly.
* Google Books may require a key and billing for high volume; the app does not ship one.
* Open Library asks clients to be polite; the app makes one request per user action and caps results
  at 10.
* DLsite's `robots.txt` asks for a 10-second crawl delay and disallows the paged result URLs. The app
  makes one first-page request per user action, never retries and never paginates.
* Melonbooks and Suruga-ya refuse automated clients. The app sends them no request at all; their
  browser links are generated locally from the query.
* To add a provider, implement `ProductSearchDataSource`, add a `ProviderSpec` to `ProviderCatalog`
  and register the source in `OnlineSearchController`; do not claim it in the UI until it is
  implemented and tested. A provider that cannot be queried belongs in `ProviderCatalog` as
  `BROWSER_ONLY`, with its measured reason.

