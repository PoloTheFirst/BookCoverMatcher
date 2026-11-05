# Online search, OCR and bookmarks â€” implementation notes (v2.4.0)

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
  â†’ PixelImage
  â†’ AndroidImageCodec.encodeJpeg(OCR_MAX_EDGE)
  â†’ OnlineSearchController.onCapture
      â†’ SessionCache (SHA-256 of the JPEG)
      â†’ ChromeLensPyBridge.recognise
          â†’ PythonRuntime (Chaquopy, Python 3.12)
          â†’ lens_bridge.ocr_image
          â†’ chrome_lens_py.LensAPI.process_image(output_format="blocks")
          â†’ flat envelope {ok, text, block_heights, detected_language, translated_text, â€¦}
      â†’ OcrText
  â†’ OcrQueryExtractor.suggest (title/author)
  â†’ OpenLibraryBooksSource.search + GoogleBooksSource.search + DlsiteSource.search
      (concurrent on Dispatchers.IO, 20 s deadline each)
  â†’ OnlineAggregator.merge (provider key â†’ dedupe â†’ bookmarked promotion â†’ limit)
  â†’ CatalogueUi.Found
  â†’ ProductResultCard â†’ Bookmark / + Wishlist / Open website
```

If OCR fails, the query field remains editable; **Search catalogues** works without OCR. That is a
deliberate separation: the catalogue step does not require the Python bridge.

---

## 2. Providers

### Open Library Books API
* Endpoint: `https://openlibrary.org/search.json`
* Free, no API key; structured works/editions; cover ids; detail URLs are built from a validated
  `/works/OLâ€¦` or `/books/OLâ€¦` key.
* Failures map to `OFFLINE`, `TIMEOUT`, `RATE_LIMITED`, `SERVER_ERROR` or `BAD_RESPONSE`.

### Google Books Volumes API
* Endpoint: `https://www.googleapis.com/books/v1/volumes`
* Free for search, no API key shipped; structured volume records, covers and a books.google.com
  page.
* `http://` cover links are upgraded to `https://` on the same host. A missing/unsafe detail link
  falls back to `https://books.google.com/books?id=<validated id>`.
* **Without a key Google may answer HTTP 429** on shared networks. The outcome is
  `ProductSearchOutcome.Failed(RATE_LIMITED, â€¦)`; the controller still shows the other catalogue's
  results and displays a note. It does not retry silently.

### DLsite (books search page)
* Endpoint: `https://www.dlsite.com/books/fsr/=/keyword/<form-encoded query>`
* **Encoding is `+` for a space, not `%20`.** The keyword is a path segment, but DLsite parses it as its
  own form value and rejects `%20` outright: measured 2026-10-23 with the app's user agent,
  `keyword/Blue+Archive` â†’ **HTTP 200 with 30 works**, `keyword/Blue%20Archive` â†’ **HTTP 403** (a
  239-byte error page). That is why every multi-word query used to fail while single words worked.
  `QueryForm.PLUS` on the `ProviderSpec` plus `ProviderLinks.encode(query, form)` keep the rule in one
  place; a `+` typed by the user is still `%2B`, so the two cannot be confused.
* Public HTML page, no key, no cookie, no login. Measured live on 2026-10-22: HTTP 200 with **30 real
  works** for `æ±æ–¹`, and a genuine (empty) result page for a nonsense query â€” with the app's own user
  agent, not a spoofed browser one.
* Parsed by `core/online/DlsiteResponseParser` (plain Kotlin, fixture-tested, no HTML dependency):
  * id from `data-list_item_product_id` (`RJâ€¦`), validated by regex;
  * title from `<dd class="work_name">`'s anchor (`title` attribute, else its text), entity-decoded;
  * circle from `<dd class="maker_name">`, kept as the author line;
  * the **current** price from the first `work_price_base`/`work_price_suffix` pair (the struck-through
    `<span class="strike">` price is the old one and is not used);
  * the 240Ã—240 cover from the listing, upgraded to `https`.
* The listing has no stock, description or rating field, so those stay `null` and the card shows `â€”`.
* One request per user search; no pagination, no retry. `robots.txt` asks for `Crawl-delay: 10` and
  disallows the paged result URLs (`/fsr/=/*/per_page/*/page/`), which the adapter never requests.
* A 401/403/451 becomes `OnlineFailure.BLOCKED` â€” never a retry with a disguised client.

### Melonbooks - browser-only by product decision
* Search page: `https://www.melonbooks.co.jp/search/search.php?mode=search&name=<percent-encoded query>`
* Access evidence (2026-10-23, app user agent, no cookies):
  * `GET /robots.txt` -> **HTTP 404** (RFC 9309: no rules published);
  * `GET /search/search.php?mode=search&name=...` -> **HTTP 200**, UTF-8, ~588 KB, 100 products.
* **The app does not query it.** This is an explicit product decision recorded in the v2.2.2 patch: the
  card stays, its `Open` link stays, and it can never enter the selected set. The adapter
  (`MelonbooksSource`) and parser (`MelonbooksResponseParser`) remain in the tree, unregistered and
  covered by offline fixture tests only.

### Suruga-ya - robots.txt disallows /search/
Its `robots.txt` is readable and excludes the endpoint:

```
HTTP 200  https://www.suruga-ya.jp/robots.txt
User-agent: *
Crawl-delay: 30
Disallow: /search/      (...plus /core/, /mypage/, several facet parameters)
```

The search page itself answers, but the shop's own rules exclude `/search/`. The app does not exploit
the missing trailing slash: it marks Suruga-ya `BROWSER_ONLY`, sends it **no** request and offers its
`Open` link. It is never counted as an answered provider, whatever the selection state.

### Doujin no Mori - browser-only (robots disallows the search route)
* The provider was previously recorded here as "DoujinMori" at `doujinmori.com` / `doujin-mori.com`;
  those domains do not exist. The intended site is `https://doujinnomori.com/`, which answers.
* Verified search route (user-supplied, then measured here):
  `https://doujinnomori.com/comics/free-search?keyword=<percent-encoded query>` -> HTTP 200,
  server-rendered (the HTML already contains the work links and images).
* `robots.txt` (HTTP 200) says `User-agent:*` / `Disallow:/comics/free-search`. That is exactly the
  route the search uses, so **the app does not query it**, even though the page would be parseable.
* The card keeps its `Open` link for that URL (query percent-encoded by `ProviderLinks`), opens in the
  user browser, and can never be selected. The site own "show adult content" preference is a
  user-facing setting the user changes there; the app sets no cookie and bypasses nothing.
* The provider id stays `dm`; it has never been selectable, so no saved selection can contain it, and
  `SearchRouting.sanitize` drops it on read. No migration is needed.

### Doujinshi.info - browser-only (HTTP 403 to automated clients)
* Search route: `https://www.doujinshi.info/works?query=<percent-encoded query>`.
* `robots.txt` (HTTP 200) is permissive (`User-agent: *` / `Allow: /`, content signal `search=yes`),
  but every page request from this client answers **HTTP 403** (the site bot protection), including
  the home page and the search page. Nothing can be read reliably, so no adapter exists.
* Browser-only card: the `Open` link carries the query, and the user solves whatever the site asks for
  (including its own "show adult content" control) in the browser.

### MyAnimeList - browser-only (no unauthenticated API)
* The URL in the request, `https://myanimelist.net/topmanga`, answers **HTTP 404** - it is not a
  search endpoint. The site manga search is
  `https://myanimelist.net/manga.php?q=<percent-encoded query>&cat=manga` (HTTP 200).
* `robots.txt` disallows several paths (`/includes/`, `/comments.php`, `/mymessages.php`, ...) and the
  site terms do not permit automated collection; there is no unauthenticated public API (the
  documented API needs a registered client and OAuth). The app therefore does not scrape it.
* Browser-only card: the `Open` link goes to that manga search. A ranking page is never used as a
  stand-in for query results.

### nhentai - browser-only (HTTP 403, adult catalogue)
* Home, `/robots.txt` and `/search/?q=<query>` all answer **HTTP 403** (Cloudflare) to this client, so
  the query URL cannot be verified from here and nothing can be parsed. The catalogue is adult
  content, which the app does not store or display.
* Browser-only card: `https://nhentai.net/search/?q=<percent-encoded query>` opens in the user
  browser, where the site own age gate applies. The app sends no request, sets no cookie and keeps
  no adult fixture in the test suite.
### E-Hentai - browser-only (adult catalogue, age gate)
* Search page answers: `GET https://e-hentai.org/?f_search=<query>` -> HTTP 200, and the HTML contains
  gallery rows.
* `robots.txt` (HTTP 200) disallows `/s/`, `/codegen.php`, `/archiver.php`, `/favorites.php`,
  `/gallerypopups.php`, `/report.php` and `/stats.php`. `/s/` is where the images live and
  `/codegen.php` is the challenge endpoint, so automated image access is off limits by the site's own
  rules.
* The catalogue is adult content behind the site's own age acknowledgement. The app therefore does not
  parse, store or display those results: the card opens the site's own search page in the browser, where
  the user answers the age gate. The app sets no cookie, bypasses no age gate, and the test suite
  contains no adult fixture.

### Toranoana - browser-only (no documented API; 18+ catalogue)
* Search page: `https://ec.toranoana.jp/tora_r/ec/app/catalog/list?searchWord=<percent-encoded query>`
  (a blank query opens the storefront, `https://ec.toranoana.jp/tora_r/ec/`).
* **Diagnostics (2026-10-25, app user agent, no cookies)** - the route is *not* robots-disallowed:
  * `GET /robots.txt` -> **HTTP 200**, `text/plain`, 283 bytes, `Last-Modified: 2024-05-31`, served by
    nginx: a genuine robots file, not an error or challenge page, and **no redirect**. Rules:
    `User-agent: *` with `Disallow: /api/`, `/app/`, `/ec/app/`, `/cms/`, `/cems/`, `/log/`, `/pc/`;
    no `Crawl-delay`. None of those prefixes matches `/tora_r/ec/app/catalog/list`, so no rule covers
    the search route.
  * `GET /tora_r/ec/app/catalog/list?searchWord=<japanese>` -> **200**, `text/html;charset=UTF-8`, no
    redirect (final URL equals the requested URL), ~790 KB, **50 server-rendered `catalog-item`
    blocks**; the response also issues session cookies (`APP-ID`, `TSESSIONID`, ...), none of which the
    app stores.
  * Same route with `Blue%20Archive` and with `Blue+Archive` -> **200** both ways (~709 KB, 50 blocks),
    so the ordinary query-string form `%20` is kept.
  * `GET /` and `GET /tora_r/ec/` -> **200**; `?searchWord=` with an empty value -> **200** but 5.4 MB
    of the whole catalogue, which is why a blank query opens the landing page.
* **The app still queries nothing here**, for reasons unrelated to robots:
  * there is **no documented public API**; the only route is an undocumented HTML page of the web
    storefront - no versioning, no stability contract, session cookies on every response, and a 5.4 MB
    unpaginated catalogue when the parameter is empty. A browser page is not a supported API, so the
    app does not scrape it;
  * the catalogue is doujinshi/adult material behind the site's own age acknowledgement, and the app
    keeps age-gated adult catalogues browser-only (the E-Hentai/nhentai policy), so the gate is
    answered on the site.
* Result: the card is `BROWSER_ONLY`, its id (`tr`) can never be selected, and it never contributes to
  the online result count. A single-product metadata lookup is **not** implemented (deferred): it would
  mean fetching storefront pages programmatically for the same reasons, and the app has no product-URL
  enrichment entry point.

### Platform selection and the result maximum
* `SearchRouting` owns the rules: `DEFAULT_SELECTION` = the three automated ids, `sanitize` drops
  unknown **and browser-only** ids, `toggle` refuses a non-selectable id, `automated` returns the
  selected in-app providers, `shortcuts` returns every browser-only card, `key` is the cache key.
  Selection is persisted in `bookcover_online` -> `online_providers`; unset means the default, an
  empty set stays empty.
* `SearchLimits` owns the count (options 5/10/20/30/50, default 5, validated on read, `forProvider`).
  Persisted as `online_limit`. The **offline** comparison keeps `Config.TOP_MATCHES = 5`.
* The controller reads query + selection + limit, queries **only selected automated** platforms with
  `min(limit, adapter.maxResults)`, merges/deduplicates, then applies `limit`. With no automated
  platform selected it issues no request and reports `CatalogueUi.BrowserOnly`, naming the shortcuts.
* The cache entry stores query, limit and selection key; a hit requires all three, so a five-result
  answer can never satisfy a twenty-result request. Changing either cancels an in-flight search.
* Every card link comes from `ProviderLinks` (validated https + own host, the platform's own query form
  â€” `%20`, or `+` for DLsite â€” landing page for a blank query) and is independent of the selection.
  A `ProviderSpec` with no configured page is refused by `ProviderLinks` instead of producing a dead
  button (unit-tested); every shipped card does have a verified page.
* **The links follow the query field, not the last search** (v2.2.4): `OnlineSearchController.setQuery`
  rebuilds every row's `searchUrl` through `List<ProviderStatusUi>.withQuery(text)` on each edit, so a
  browser-only card can be opened with freshly typed text without a dummy in-app search, and a search
  that completes late re-applies the *current* text instead of reverting the links. Editing launches
  nothing: a search still starts only from the Search button (`applyResults` never writes `query`).

---

## 3. Identity and de-duplication

`ItemIdentity` defines "the same thing":

1. A stable provider key wins: `ol:OL27482W`, `gb:<volumeId>`, `mb:3897050`, `dl:rj01730150`.
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
* A saved bookmark is drawn with the Search result card (**v2.3.1**): `ui/ResultCardParts` supplies the
  expand control, the description block, the detail rows and the action chips, and
  `core/wishlist/BookmarkPresentation` derives the author line, the price/identifier caption, the source
  chip and the match-type wording from the stored fields (`â€”` for a value the source never sent; the
  circle never becomes an artist). The `Bookmarks` drawer's open flag is `UiState.bookmarkPanelOpen`,
  and the filter text, sort and expanded cards are kept per tab by `rememberSaveableStateHolder`.
* All three workflows (wishlistâ†’bookmark, bookmarkâ†’wishlist, bookmarkâ†’restartâ†’wishlist) are covered
  by JVM tests and were exercised manually for the custom-bookmark case.

Schema details and the full migration rules are in `README.md` Â§3.

---

## 7. Verification (actual, not planned)

| Item | Command / environment | Result |
|---|---|---|
| JVM suite | `.\gradlew.bat testDebugUnitTest` | 470 tests, 0 failures (4 live checks skipped by design) â€” measured 2026-10-30 |
| Live provider checks | `.\gradlew.bat testDebugUnitTest -DonlineCheck=1 --tests "*LiveCatalogueTest" --rerun-tasks` | 329 tests, 0 failures; **4/4 live checks ran**: Open Library and DLsite returned real records, Google Books was found or `RATE_LIMITED`. Melonbooks is not in the live suite any more |
| Compose UI tests | none | **not run**: no `ui-test-junit4` dependency and no emulator/device; selection rules, link independence, the cap and the batch repository calls are unit-tested (`SelectionLogicTest`, `SearchRoutingTest`, `ProviderLinksTest`, `OnlineResultLimitTest`, `BatchOperationsTest`) |
| DLsite live parse | `DlsiteSource().search("æ±æ–¹", 3)` | real `RJâ€¦` works with Japanese titles, circles, `924å††`-style prices, `img.dlsite.jp` covers and `dlsite.com` product URLs |
| Provider access probe | `robots.txt` + search URL, app user agent (2026-10-23) | Melonbooks robots **404**, search **200** (not queried); Suruga-ya robots **200** with `Disallow: /search/`; DLsite robots **200**, paged URLs disallowed; E-Hentai robots **200**, `/s/` and `/codegen.php` disallowed; Doujin no Mori robots **200** with `Disallow: /comics/free-search`, search **200**; Doujinshi.info robots **200** but every page **403**; MyAnimeList `/topmanga` **404**, `/manga.php?q=` **200**; nhentai **403** everywhere |
| Toranoana access probe | `robots.txt` + search URL, app user agent (2026-10-25) | robots **200** `text/plain` (283 B, no redirect, no `Crawl-delay`), rules `/api/ /app/ /ec/app/ /cms/ /cems/ /log/ /pc/` â€” **none matches `/tora_r/ec/app/catalog/list`**; search **200** `text/html` with no redirect, 50 `catalog-item` blocks, with `%20` and with `+`; landing **200**; **not queried** by the app (no documented API; 18+ catalogue) |
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
* **Suruga-ya as an in-app search** - deliberately not implemented (its `robots.txt` disallows
  `/search/`); only its generated browser link is verified.
* **Melonbooks as an in-app search** - not implemented in this build; its adapter and parser exist but
  are not registered. Only DLsite runs live among the Japanese shops.
* **Doujin no Mori as an in-app search** - not implemented: its `robots.txt` disallows the search route.
* **Doujinshi.info / nhentai** - not implemented: both answer HTTP 403 to automated clients.
* **MyAnimeList** - not implemented: no unauthenticated API and terms forbid scraping.
* **E-Hentai** - browser-only: adult content behind the site age gate, `/s/` disallowed by robots.
* **Toranoana** - browser-only: `robots.txt` disallows `/api/`, `/app/` and `/ec/app/` (where its
  search runs) and the catalogue is 18+; only its generated browser link is verified.
* **Toranoana product-URL lookup** - not implemented (deferred): it would fetch product pages on the
  same disallowed paths, and there is no product-URL enrichment entry point in the app.
* **DLsite on a device** - the live checks ran on the desktop JVM through the same adapter.
* **Compose UI interaction** (card toggle, glow/dim, link independence) â€” no Compose UI-test
  dependency and no emulator/device was available; the state transitions those clicks drive are
  unit-tested instead.

---

## 8. Configuration and costs

* No account, key or login is required for OCR or any of the three automated providers.
* `chrome-lens-py` uses Google's public Lens `crupload` endpoint and its own built-in key; Google may
  rate-limit it. The app reports `RATE_LIMITED`/`AUTH` honestly.
* Google Books may require a key and billing for high volume; the app does not ship one.
* Open Library asks clients to be polite; the app makes one request per user action and never asks for
  more than 50.
* DLsite's `robots.txt` asks for a 10-second crawl delay and disallows the paged result URLs. The app
  makes one first-page request per user action, never retries and never paginates.
* Melonbooks publishes no `robots.txt` (HTTP 404); its search page answers, but this build keeps it
  browser-only by product decision and sends it no request at all.
* Suruga-ya's `robots.txt` disallows `/search/`. The app sends it no request; its browser link is
  generated locally from the query.
* Toranoana's `robots.txt` disallows `/api/`, `/app/` and `/ec/app/`, so the app sends it no request
  either; only the browser link is built locally from the query. Nothing is cached for it.
* To add a provider, implement `ProductSearchDataSource`, add a `ProviderSpec` to `ProviderCatalog`
  and register the source in `OnlineSearchController`; do not claim it in the UI until it is
  implemented and tested. A provider that cannot be queried belongs in `ProviderCatalog` as
  `BROWSER_ONLY`, with its measured reason.

## 9. v2.5.0-dev.1 â€” the query, capability and matching core

Three new plain-Kotlin files under `core/online` are the foundation of the v2.5.0 work. **They are not
wired to the UI in this build**; the section exists so the next development round extends them instead of
re-inventing them.

### `OnlineQuery` â€” what the user typed, split into attributes

```
OnlineQuery.parse("RJ01234567 title:Blue Archive circle:Yostar")
  â†’ code = RJ01234567, title = "Blue Archive", circle = "Yostar", original = <the raw text>
```

* Prefixes: `title:`/`t:`, `circle:`/`c:`, `author:`/`a:`, `code:`/`id:`/`r:`; anything else is a title.
* A bare code (`RJ` + 6â€“10 digits, also `RJ 01234567` / `RJ-01234567`) is lifted out of the text wherever it
  appears and normalised by `normalizeCode` (fold, upper-case, strip spaces and hyphens).
* `foldWidth` maps U+FF01â€“U+FF5E to ASCII and U+3000 to a space; kana, kanji, `ã€œ` and `ãƒ»` are untouched.
* `keyword()` is the one string a keyword provider (DLsite) receives; `without(field)` drops a part a
  provider cannot use, and `isEmptyWithout(...)` says whether anything would be left to search.

### `ProviderCapabilities` â€” what each provider can be asked

| Provider id | Title | Circle | Author | Code | Image |
|---|---|---|---|---|---|
| `ol` Open Library | yes | no | yes | yes (ISBN) | **no** |
| `gb` Google Books | yes | no | yes | yes (ISBN) | **no** |
| `dl` DLsite | yes | yes | no | yes (`RJâ€¦`) | **no** |

`canSearch(id, query)` is false when the query only uses fields the provider ignores, and
`eligible(...)`/`skipped(...)` split a selection into "searches this" and "cannot search this", so a
provider is reported as unable rather than failed. **No provider supports `IMAGE_REFERENCE`:** reverse-image
discovery is not implemented and is not implied.

### `CandidateMatcher` â€” evidence, not one opaque score

```
CandidateMatcher.compare(candidate, localProduct, imageSimilarity = 0.93)
  â†’ ProductMatch(score 0..100, verdict, evidence = [identifier, title, circle, author, cover])
```

* Weights: identifier 0.45, title 0.30, circle 0.15, author 0.10, cover 0.35 â€” documented constants.
* Only dimensions **both** sides supply are scored; the rest are `EvidenceState.UNKNOWN` and their weight is
  dropped before renormalising, so a provider without a circle field cannot be penalised for it.
* Verdicts: an exact identifier is `LIKELY_MATCH`; any contradiction is `NO_MATCH`; a title alone never
  exceeds `POSSIBLE_MATCH`; nothing comparable is `INSUFFICIENT_EVIDENCE`.
* `rank(candidate, locals)` orders by verdict quality (not enum order), then score, then record key;
  `ambiguous(ranked)` returns the records that cannot be told apart so the UI can ask instead of choosing.
* `similarity(a, b)` is exact, then containment (scaled), then character-bigram Dice; it works on Japanese
  as well as Latin text because it never splits on spaces alone.

Cover similarity is an **input**: computing it belongs to the existing OpenCLIP/ONNX pipeline, which this
core does not call yet.



---

## 10. v2.5.0-dev.2 — reverse-image discovery, manual fallback and decisions

This section records the second dev iteration. Everything below exists in **217 / 2.5.0-dev.2** and is covered
by deterministic tests; §10.7 states exactly what was and was not measured.

### 10.1 The four image capabilities, kept apart

| Capability | What it really is | Who does it |
|---|---|---|
| OCR text extraction | reading printed text off the cover | `chrome-lens-py` bridge (unchanged) |
| External reverse-image discovery | submitting the image to a visual-search service | **SauceNAO** (automated); **IQDB** manual only |
| Local cover comparison | comparing two covers with the app's own embeddings | existing OpenCLIP/ONNX pipeline (not called from this panel yet) |
| Manual listing | the user supplies a URL / metadata / cover | `ManualCandidates` + the same evidence pipeline |

OCR is never described as visual search, and a local OpenCLIP comparison is never described as external
discovery. The reverse-image section of the panel is labelled `C ·`, after `A · Read the cover` (OCR) and
`B · Search the catalogues` (metadata providers).

### 10.2 Provider order and integration status

| Priority | Provider | Integration | Request budget | State when it cannot run |
|---|---|---|---|---|
| 1 | SauceNAO | automated, documented JSON API | 30 s | `NOT_CONFIGURED` (no key), `BLOCKED`, `RATE_LIMITED`, `QUOTA_EXCEEDED`, `TIMEOUT`, `UNAVAILABLE`, `ERROR` |
| 2 | IQDB | **manual only** (no API; robots-disallowed routes) | 90 s declared, unused | `MANUAL_ONLY` |
| local | OpenCLIP | existing pipeline | — | — |

`ReverseImageSearchRunner` sorts by that priority regardless of list order, and **always continues to IQDB after
SauceNAO has answered**: skipping the second provider because the first succeeded would silently halve the
evidence.

### 10.3 SauceNAO: request, response, credential

* **Endpoint and method.** `POST https://saucenao.com/search.php`, `multipart/form-data`.
* **Parts.** `output_type=2` (JSON), `db=999` (all indexes), `numres` (1–30, the user's result maximum capped),
  optional `minsim` (0–100), `api_key`, and the image in the `file` part.
* **Why multipart.** The key is a form **part**, so it never appears in a URL — which is where credentials leak
  (proxy logs, `Referer` headers, crash reports). `MultipartForm` builds the body in `core`, so the exact bytes
  that leave the device are unit-tested.
* **Response.** The documented `header`/`results` envelope. `header.status == 0` is success; a non-zero status
  is a named failure carrying the provider's own number (and `QUOTA_EXCEEDED` when the reported allowance is
  exhausted). `header.short_limit`/`long_limit`/`short_remaining`/`long_remaining` are preserved and shown as
  "quota: N of M left".
* **Per row.** `header.similarity` (kept verbatim, never treated as a probability), `header.thumbnail`,
  `header.index_name`, `data.ext_urls[0]` as the source URL, `data.title`/`eng_name`/`jp_name`,
  `data.company` as the circle, `data.creator`/`author_name`/`member_name` as the author, every other scalar in
  `data` as metadata, and an `RJ…` code extracted from the title/URL when the provider sent one.
* **No retries.** A 429/503 or an exhausted allowance is reported, never re-attempted.
* **Credential storage.** `SauceNaoKeyStore` keeps the key AES-256-GCM-sealed under a key generated in
  `AndroidKeyStore`; only the ciphertext lives in `bookcover_online_secrets`, which is a separate file from the
  ordinary `bookcover_online` settings. The UI shows `••••••••ab12` with *Replace* and *Remove* actions, and the key
  is never shown again. A key that cannot be decrypted (restored device, cleared data) is deleted and treated as
  "not configured". Saving is verified by reading the value back through the decrypt path, and a storage failure is
  reported as such instead of as a mistyped key.
* **The IV must come from the cipher.** An `AndroidKeyStore` AES/GCM key forbids a caller-supplied nonce
  (`CALLER_NONCE_PROHIBITED`), so `AesGcmSecretBox.seal` encrypts with no IV parameter and stores `cipher.iv` in the
  envelope. v2.5.0-dev.2 passed its own IV and therefore could never store a key at all — see §11.1.


### 10.4 IQDB: verified access, and why this build is manual-only

Measured 2026-11-05, in this session:

* `https://iqdb.org/` is an HTML form: one `File or Source URL` field, JPEG/PNG/GIF, 8192 KB and 7500×7500
  limits. It publishes **no API** — no endpoint, parameter set, versioning or response contract.
* `https://iqdb.org/robots.txt` (verbatim) disallows, for `User-agent: *`: `/danbooru/`, `/dou/`,
  `/konachan/`, `/moe.imouto/`, `/3dbooru/`, `/4scrape/`, `/e-shuushuu/`, `/sankaku/`, `/db-search`,
  `/kc-search`, `/mi-search`, `/multi-search`, `/index` and `/?url=`. That is every route an automated query
  would use, including the URL-submission form.

So `IqdbProtocol.AUTOMATION_SUPPORTED` is **false** and no image is ever posted to IQDB. What the app does:

* the IQDB row reports **`MANUAL_ONLY`** with the reason, and its *Open ↗* button opens IQDB's own page;
* the user performs the search in the browser, then either **pastes a result line**
  (`92% https://danbooru.donmai.us/posts/123`) or pastes a saved result page into *Paste an IQDB result*;
* `IqdbResultParser` normalises what was pasted — each row keeps its own percentage and source URL, and a row
  with no URL is dropped — and the rows enter the same candidate list, flagged as user-supplied.

Consequences, stated plainly: no IQDB throttling, quota or timeout is ever incurred, and **no IQDB result is ever
presented as an automated search result**. If a permitted automated route appears later, it replaces
`IqdbReverseImageSource.search`; the classification lives in one place so it cannot be switched on silently.

### 10.5 Aggregation, provenance and failure isolation

* Only providers whose state is `SUCCESS` contribute rows. A `TIMEOUT` never masquerades as "nothing found".
* Deduplication happens **only on evidence of identity**: a shared product code (`id:RJ01234567`) or a shared
  normalised URL. Two rows with similar titles stay apart — they may be two editions or two products.
* A candidate found by both providers keeps **both** references, both similarity values and both URLs, and is
  shown as `SauceNAO 95.1% · IQDB 92%`. There is **no combined probability** anywhere in the model: two sources
  agreeing is stronger *provenance*, not a higher certainty, and it never establishes identity on its own.
* Ordering is by provider priority of the best reference, then that provider's own similarity. Provider scores
  are never averaged across providers.
* A provider failure is contained: the runner records each provider separately and merges only successes, so
  SauceNAO's results survive an IQDB failure and vice versa.
* Cancellation is cooperative and honest: the runner asks before each provider, so a cancel sends nothing for
  the outstanding one and records it `CANCELLED`. Because a blocking HTTP call cannot be preempted, a late answer
  is dropped by the controller's generation check instead of being applied to a request the user abandoned.

### 10.6 Decisions, collection status, secondary sources, cache

* **A candidate is a suggestion.** `CandidateDecision.SUGGESTED` is the default and the card says
  "Suggestion — not confirmed by you". Confirming or rejecting writes a `CandidateDecisionRecord` through
  `CandidateDecisionStore` (one JSON file per wishlist profile, atomic-ish write, unreadable file → empty map).
  A decision is idempotent by candidate identity and can be revisited; a blank key decides nothing.
* **Deciding is not owning and not wanting.** `CollectionStatus` keeps `owned`, `catalogued` and `wanted`
  independent, with `null` for "not stated". `CollectionStatusOf` derives *only* catalogue presence from the
  comparison (`MATCHED` / `AMBIGUOUS` / `ABSENT` / `UNKNOWN`) and an empty workbook can never yield `ABSENT`.
  `LocalProducts.of(CatalogEntry)` reads only mapped, present cells, so an unmapped circle is unknown rather than
  contradicting.
* **Secondary-source switch.** `ProviderTier.SECONDARY_BOOK` covers Open Library and Google Books; `DLsite` is
  `PRIMARY_DOUJIN` and is never affected. Switching the general-book sources off removes them from the eligible
  set **before** any request, marks their rows "switched off", and produces `CatalogueUi.SwitchedOff` when they
  were the only selected providers — never an empty result.
* **Bounded cover caching.** `BoundedImageCache` is a byte-budgeted LRU (1 MB, 512 KB per entry) with refusal
  counters, `invalidate(key)`, `clear()` and a `describe()` line. Saved data is never in it.

### 10.7 Verification — what was measured, and what was not

| Item | Status |
|---|---|
| IQDB page and `robots.txt` | **verified live 2026-11-05** |
| SauceNAO request/response schema | implemented from the published API description; the documentation host answered **HTTP 403** to the tooling used here, so it was **not re-verified live** |
| SauceNAO live search | **not run** — no key, and no live network request was made this session |
| IQDB automated search | **not implemented, by design** |
| IQDB parser | tested against sanitised fixtures only |
| Test suite | **869 tests / 106 classes / 0 failures / 7 skipped**, no live network |
| Debug build | `:app:assembleDebug` **BUILD SUCCESSFUL** at `217 / 2.5.0-dev.2` |

Follow-up: run an opt-in live SauceNAO check with a real key (`-DonlineCheck=1` style, kept out of the default
suite), then surface the per-candidate `CollectionStatus`/OpenCLIP evidence on the card.


---

## 11. v2.5.0-dev.3 — the SauceNAO key fix, and the IQDB UI round

### 11.1 Why SauceNAO could never be configured (and the fix)

The report chain in v2.5.0-dev.2 was "key saved, `READY`, request sent". None of it happened, and nothing in the UI
said so. The chain was:

1. `saveSauceNaoKey` → `SauceNaoKeyStore.save` → `AesGcmSecretBox.seal(key, plain, iv)`.
2. `seal` called `Cipher.init(ENCRYPT_MODE, keystoreKey, GCMParameterSpec(128, iv))` — a **caller-supplied nonce**.
3. The device's Keystore rejected it: `keystore2 … enforcements.rs: NONCE is present, although CALLER_NONCE is not
   present`, `Error::Km(r#CALLER_NONCE_PROHIBITED) for AppUid(…)`.
4. `save` caught the exception and returned `false`; the UI said "That does not look like a SauceNAO key; nothing
   was saved." The secrets file was never created.
5. `readiness()` therefore always answered `NOT_CONFIGURED`, and the runner skipped the provider before any socket
   was opened. The row was telling the truth about a state the code had put it in.

Fixes, in order of importance:

* **Let the cipher choose the IV.** `seal` now calls `cipher.init(ENCRYPT_MODE, key)` with no IV and prefixes
  `cipher.iv` to the ciphertext. This is the documented way to use a Keystore AES/GCM key; decryption still passes
  the stored IV, which is allowed. Unit-tested (`SecretBoxTest`).
* **Never collapse two failures into one message.** `save` returns `SauceNaoKeySaveResult`
  (`Saved` / `Rejected(MALFORMED)` / `Rejected(STORAGE_FAILED)`); `SauceNaoKeyRules.describe` gives a different,
  key-free sentence for each. A user with a perfectly good key now learns that the *device* refused to store it.
* **Round-trip before claiming success.** After writing (with `commit()`), `save` reads the value back through
  `key()`. If it cannot be read, the save is `STORAGE_FAILED` — the check that would have caught this defect at the
  button press.
* **Discard the stale run.** `saveSauceNaoKey` removes any recorded SauceNAO run before rebuilding the rows, so a
  pre-configuration `NOT_CONFIGURED` run cannot keep the row lying after a successful save.
* **`Replace` in the UI.** A stored key offers *Replace* (opens the field) next to *Remove*, so replacing never
  requires deleting the working key first.

### 11.2 Live integration record (2026-11-05, API 37 emulator; key redacted)

`API key: [REDACTED]` was entered through the app's own field, then the project's own test cover
(`app/src/androidTest/assets/cover.jpg`) was picked through the app's image chooser and submitted with
*Search by this cover*.

| Step | Observed |
|---|---|
| Save | hint `••••••••19a3`; `bookcover_online_secrets.xml` contains base64 ciphertext only |
| Provider row | `NEEDS KEY` → **`READY`** |
| Request | `POST https://saucenao.com/search.php`, HTTP **200**, `header.status` **0** |
| Parse | 8 normalised hits, similarities `40.27`, `38.26`, `38.16`, `37.83`, `36.81`, … |
| Quota | `quota: 99 of 100 left` |
| UI | `RESULTS`, `8 candidate(s) · 8 undecided`; titles/authors from the provider (e.g. `[Endless Requiem (yasha)] …`) |
| IQDB | still `MANUAL ONLY`, unaffected |
| Replacement | `19a3` → `3333` → `19a3`, `READY` throughout |
| Secrets | full key, first 8 and last 4 characters absent from `logcat`; prefs hold ciphertext only |

Not re-executed: a second live call after the replacement (reinstall cleared the captured cover). Readiness and
decryption were verified after the replacement instead.

### 11.3 IQDB UI round

* **The suggestion line is white.** `Suggestion — not confirmed by you` was drawn in `Palette.Navy700`, i.e. the
  card's own colour. The decision line now uses `Color.White` for `SUGGESTED`, green/red for confirmed/rejected.
* **A rubbish bin on every IQDB card.** Top-right, `contentDescription = "Dismiss IQDB result"`, 36 dp target. It
  removes **only that card** from the current list: no other card, no SauceNAO result, no decision record, no
  wishlist entry, no cached image. It is not shown where SauceNAO also reported the candidate
  (`ReverseCandidateUi.isIqdbOnly`), so the primary provider's evidence cannot be dismissed by accident.
  Dismissal is in-memory only, so a later search may show the result again.
* **The paste box is always expanded.** No toggle, no collapsed state; the field and *Add results* render directly.

### 11.4 The Online tab no longer creates listings

The v2.5.0-dev.2 "Listing Candidate" flow (`Add listing`, a seven-field form, its controller state and
`core/online/ManualCandidate.kt`) duplicated the Wishlist's bookmark behaviour and is **removed**. The Wishlist
keeps its own manual bookmark form, deduplication and profile isolation untouched, and no stored data was migrated
or deleted. The IQDB paste box remains the only way user-supplied material enters the Online tab, and those rows are
still labelled as user-supplied.

### 11.5 Verification for this round

| Item | Status |
|---|---|
| Unit suite | **876 tests / 108 classes / 0 failures / 7 skipped** |
| Debug build | `:app:assembleDebug` **BUILD SUCCESSFUL** (`218 / 2.5.0-dev.3`) |
| Runtime | emulator `emulator-5554`: key save/replace, readiness, live search, paste, dismiss, always-expanded box |
| Logcat | no fatal exception; no key material |

