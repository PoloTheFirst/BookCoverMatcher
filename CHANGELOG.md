# Toranoana card + browser-only links that follow the query field

**v2.2.4** adds **Toranoana** as an eighth browser-only card and makes every card's `Open ↗` link follow
the query field, so a browser-only shop opens with the text just typed — no dummy search first.

## v2.2.4 (2026-10-25)

`records.json` stays at version 3, the selection format is unchanged, and no existing provider, search
workflow, result, bookmark or profile is touched.

### Fixed

* **A browser-only card used a stale keyword.** Cards' `Open ↗` links were built when the provider rows
  were last rebuilt — at start-up, on a platform toggle, or when a search ran — so typing a title and
  opening Melonbooks/Suruga-ya/Toranoana used the previous query (often empty) unless the user first
  ran a dummy in-app search. `OnlineSearchController.setQuery` now rebuilds every row's link through
  `List<ProviderStatusUi>.withQuery(query)` on each edit, which is a pure function of (provider, current
  text): the text field is the single source of truth, and the rows keep the states/details of the last
  search that actually ran. No request is made while typing, and a search that finishes late re-applies
  the *current* text to the links, so a late answer can never drag them back to the query it ran for.
  `ProviderStatusUi.state` and `ProductsUi` (results, errors, running indicator, cache) are untouched.
* Covered by `ProviderRowLinksTest` (8 tests): typing without searching, editing after a search, a late
  answer, blank/whitespace queries, edge trimming with internal spaces preserved, Japanese and reserved
  characters encoded once, a removed provider id, and every shipped card carrying a link.

### Added

* **Toranoana (`tr`) — browser-only card.** Its `Open ↗` link opens
  `https://ec.toranoana.jp/tora_r/ec/app/catalog/list?searchWord=<query>` in the user's browser, with
  the query percent-encoded once by `ProviderLinks` (spaces as `%20`, Japanese text, `&`, `+`, `%`, `#`
  escaped). A blank query opens the storefront page `https://ec.toranoana.jp/tora_r/ec/` instead of an
  empty search.
* The card can never be selected, never counts as a searched provider and never contributes a result:
  `SearchRouting.shortcuts()` lists it, `AUTOMATED`/`SELECTABLE` do not.
* Version code/name: **206 / 2.2.4**.

### Why browser-only, and what the diagnostics actually showed

Correction to an earlier note in this patch: `robots.txt` does **not** forbid Toranoana's search route.
Measured 2026-10-25 with the app's own user agent and no cookies:

| Request | Result |
|---|---|
| `GET https://ec.toranoana.jp/robots.txt` | **200**, `text/plain`, 283 B, `Last-Modified: 2024-05-31` — a genuine robots file (no redirect, no challenge page). `User-agent: *` with `Disallow: /api/`, `/app/`, `/ec/app/`, `/cms/`, `/cems/`, `/log/`, `/pc/`; **no `Crawl-delay`** |
| `GET /tora_r/ec/app/catalog/list?searchWord=<japanese>` | **200**, `text/html;charset=UTF-8`, **no redirect** (final URL = requested URL), 50 server-rendered `catalog-item` blocks, ~790 KB |
| same route, `Blue%20Archive` and `Blue+Archive` | **200** both ways, ~709 KB, 50 blocks |
| `GET /tora_r/ec/` | **200** — the storefront landing page |
| `GET .../catalog/list?searchWord=` (empty value) | **200** but 5.4 MB — the whole catalogue, not a search |

No robots prefix matches `/tora_r/...`, so the search route is outside those rules. Toranoana is still
browser-only, for reasons that are not about robots:

* **No documented public API.** The only route is an undocumented HTML page of the web storefront: no
  versioning, no stability contract, session cookies issued on every response, and a 5.4 MB unpaginated
  catalogue when the parameter is empty. It is a browser page, not a supported API, and this app does
  not scrape one on that basis.
* **18+ catalogue.** Results are doujinshi/adult material behind the site's own age acknowledgement;
  the app keeps age-gated adult catalogues browser-only (the E-Hentai/nhentai policy), so the site's
  gate applies instead of being skipped by an in-app search.

The app therefore sends Toranoana **no request at all**: no adapter, no parser, no cache entry, no
retry, no cookie. No CAPTCHA, age gate or WAF challenge is bypassed and no identity is spoofed.

### Verified

* Link construction, unit-tested (`ProviderLinksTest`): the exact URL for `Blue Archive`, Japanese
  text, reserved characters (`A&B`, `C++`, `100%`, `hash#tag`, `a=b?c`, a double space), exactly-once
  encoding (the `searchWord` value decodes back to the typed query), the blank-query landing page, and
  `BROWSER_ONLY`/not-selectable routing (`SearchOptionsTest`, `ProviderCatalogTest`).
* Live probe of the search URL with the app's own user agent, no cookies (2026-10-25):
  `searchWord=Blue%20Archive` → **200**, ~709 KB, 50 result blocks; `searchWord=Blue+Archive` → **200**
  as well (either space form is accepted); a two-word Japanese query → **200**, ~790 KB;
  `GET /` and `GET /tora_r/ec/` → **200**; `?searchWord=` with an empty value → **200** but 5.4 MB of
  the whole catalogue, which is why a blank query opens the landing page.
* `.\gradlew.bat :app:testDebugUnitTest` — **295 tests, 0 failures** (4 opt-in live checks skipped by
  design).
* `.\gradlew.bat :app:assembleDebug` — BUILD SUCCESSFUL.

### Known limitations

* No Android device or emulator was available, so the new card was verified by unit tests, compilation
  and an HTTP probe of its link target — not by tapping it on hardware.
* **No in-app Toranoana search.** It publishes no documented public API (only an undocumented,
  age-gated storefront page), so its results are only ever seen in the browser; the app cannot bookmark
  or match them.
* **No lookup of a single Toranoana product URL** (deferred): the app has no product-URL enrichment
  entry point, and the shop publishes no documented metadata endpoint — adding one would mean scraping
  storefront pages for the same reasons the search page is not queried, so it was not attempted.
* The search page is not paginated inside the app either; pagination happens in the browser, where the
  user controls it.
