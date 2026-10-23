package com.bookcovermatcher.core.online

import java.net.URLEncoder

/** How a provider can honestly be used from the app. */
enum class ProviderAccess {
    /** The app queries it and shows its results. */
    AUTOMATED,

    /**
     * The app does **not** query it. The provider refuses automated clients, so the app offers its
     * own search page in the user's browser instead. It is never counted as a searched provider.
     */
    BROWSER_ONLY,
}

/**
 * One provider, as the UI and the controller need to describe it: what it is called, which host it
 * lives on, whether the app may query it, and how to open its own search page.
 *
 * [searchTemplate]/[landingUrl] are null for a provider with **no verified public search page** in
 * this build (DoujinMori): the card still exists so the provider is not silently missing, but it
 * offers no link and [ProviderLinks] refuses to build one.
 *
 * [selectable] is derived, never stored: only a provider the app can actually query may be put into
 * the selected set, so a browser-only card can never be mistaken for an in-app search.
 */
class ProviderSpec(
    val id: String,
    val name: String,
    val host: String,
    val access: ProviderAccess,
    /** One sentence, shown under the provider's name. Never a marketing claim. */
    val note: String,
    /** `{q}` is replaced with the percent-encoded query. Null = no verified search page. */
    val searchTemplate: String?,
    /** Opened instead when the query is blank. Null = no verified landing page. */
    val landingUrl: String?,
) {
    /** Only automated providers can be selected for an in-app search. */
    val selectable: Boolean get() = access == ProviderAccess.AUTOMATED

    /** True when this build has a verified public page to open for this provider. */
    val hasLink: Boolean get() = !searchTemplate.isNullOrBlank() && !landingUrl.isNullOrBlank()

    /** The provider's own search page for [query] (its landing page for a blank query). */
    fun searchUrl(query: String): String = ProviderLinks.searchUrl(this, query)
}

/**
 * The provider list of this build, in the order the panel shows it.
 *
 * **Automated** providers are queried by the app, each through its own adapter, and are the only
 * ones a user can select:
 *  * `ol` **Open Library** — public Books API (JSON), no key.
 *  * `gb` **Google Books** — public Volumes API (JSON), no key, may answer 429.
 *  * `dl` **DLsite** — public books search page (HTML), read by [DlsiteResponseParser].
 *
 * **Browser-only** providers are *not* queried and are *not* selectable — each for a measured reason:
 *  * `mb` **Melonbooks** — product decision (v2.2.2 patch); the page answers, the app does not use it.
 *  * `sy` **Suruga-ya** — `robots.txt`: `Disallow: /search/`, `Crawl-delay: 30`.
 *  * `dm` **Doujin no Mori** — `robots.txt` says `Disallow: /comics/free-search`, exactly the route its
 *    search uses, so automated search is off limits even though the page answers 200.
 *  * `eh` **E-Hentai** — adult catalogue behind the site's own age acknowledgement; robots disallows
 *    `/s/`, `/codegen.php` and more.
 *  * `di` **Doujinshi.info** — every request from this client answers HTTP 403 (bot protection), so
 *    its pages cannot be read reliably; the card opens the site's own search page.
 *  * `mal` **MyAnimeList** — no unauthenticated API, and its terms disallow automated collection; the
 *    card opens the site's own manga search page (`/topmanga` itself answers 404).
 *  * `nh` **nhentai** — every request answers HTTP 403 (Cloudflare) and the catalogue is adult
 *    content; the card opens the site's own search page.
 *
 * Access evidence measured on 2026-10-23 (see `ONLINE_SEARCH.md` §2 for the full table):
 *
 * ```
 * Melonbooks     GET /robots.txt                      -> 404 (no rules published)
 * Melonbooks     GET /search/search.php?mode=search   -> 200
 * Suruga-ya      GET /robots.txt                      -> 200, Disallow: /search/, Crawl-delay: 30
 * DLsite         GET /robots.txt                      -> 200, Crawl-delay: 10, paged URLs disallowed
 * DLsite         GET /books/fsr/=/keyword/            -> 200, 30 works
 * E-Hentai       GET /robots.txt                      -> 200, Disallow: /s/, /codegen.php
 * Doujin no Mori GET /robots.txt                      -> 200, Disallow: /comics/free-search
 * Doujin no Mori GET /comics/free-search?keyword=     -> 200 (server-rendered, but excluded by robots)
 * Doujinshi.info GET /robots.txt                      -> 200, Allow: / (content-signal search=yes)
 * Doujinshi.info GET /works?query=                    -> 403 (bot protection)
 * MyAnimeList    GET /topmanga                        -> 404 (the requested URL does not exist)
 * MyAnimeList    GET /manga.php?q=                    -> 200
 * nhentai        GET /  and  /robots.txt  and  /search -> 403 (Cloudflare)
 * ```
 *
 * Every automated adapter makes **one** request per user-initiated search, never paginates and never
 * retries a blocked endpoint. A provider that cannot be queried honestly is a card and a link, never
 * a fabricated result.
 *
 * Plain Kotlin/JVM: the query encoding, link validation and the access split are unit-tested.
 */
object ProviderCatalog {

    val OPEN_LIBRARY = ProviderSpec(
        id = OpenLibraryResponseParser.PROVIDER_ID,
        name = OpenLibraryResponseParser.SOURCE_NAME,
        host = "openlibrary.org",
        access = ProviderAccess.AUTOMATED,
        note = "Library catalogue. Public Books API, no key.",
        searchTemplate = "https://openlibrary.org/search?q={q}",
        landingUrl = "https://openlibrary.org/search",
    )

    val GOOGLE_BOOKS = ProviderSpec(
        id = GoogleBooksResponseParser.PROVIDER_ID,
        name = GoogleBooksResponseParser.SOURCE_NAME,
        host = "google.com",
        access = ProviderAccess.AUTOMATED,
        note = "Book catalogue. Public Volumes API, no key; can answer 429.",
        searchTemplate = "https://www.google.com/search?tbm=bks&q={q}",
        landingUrl = "https://books.google.com/",
    )

    /**
     * Melonbooks is **browser-only and unselectable** by explicit product decision (v2.2.2 patch),
     * even though its search page is reachable and has no published crawl rules. The adapter and its
     * parser stay in the tree for reference and are covered by tests, but nothing registers them, so
     * the app never queries the shop.
     */
    val MELONBOOKS = ProviderSpec(
        id = "mb",
        name = "Melonbooks",
        host = "melonbooks.co.jp",
        access = ProviderAccess.BROWSER_ONLY,
        note = "Doujinshi shop. Browser only: opens the shop's own search page, never queried in-app.",
        searchTemplate = "https://www.melonbooks.co.jp/search/search.php?mode=search&name={q}",
        landingUrl = "https://www.melonbooks.co.jp/",
    )

    val SURUGAYA = ProviderSpec(
        id = "sy",
        name = "Suruga-ya",
        host = "suruga-ya.jp",
        access = ProviderAccess.BROWSER_ONLY,
        note = "Secondhand shop. Its robots.txt disallows /search/, so results open in your browser.",
        searchTemplate = "https://www.suruga-ya.jp/search?category=&search_word={q}",
        landingUrl = "https://www.suruga-ya.jp/",
    )

    /**
     * **Doujin no Mori** (`doujinnomori.com`).
     *
     * The provider recorded here before this patch was named "DoujinMori" and pointed at
     * `doujinmori.com` / `doujin-mori.com` — domains that do not resolve. That was a naming error, not
     * evidence about this site. The real site is reachable and its search page is server-rendered, but
     * its own `robots.txt` says `Disallow: /comics/free-search` — the exact route the search uses — so
     * the app does not query it. The card opens that search page in the browser, where the user can
     * also use the site's own "show adult content" control themselves.
     *
     * The id stays `dm`: it has never been selectable, so no saved selection can contain it, and
     * `SearchRouting.sanitize` drops it on read regardless.
     */
    val DOUJIN_NO_MORI = ProviderSpec(
        id = "dm",
        name = "Doujin no Mori",
        host = "doujinnomori.com",
        access = ProviderAccess.BROWSER_ONLY,
        note = "Doujinshi shop. Browser only: its robots.txt disallows the /comics/free-search route.",
        searchTemplate = "https://doujinnomori.com/comics/free-search?keyword={q}",
        landingUrl = "https://doujinnomori.com/",
    )

    /**
     * E-Hentai's search page answers, but the catalogue is adult content behind the site's own age
     * acknowledgement, and its robots.txt disallows `/s/`, `/codegen.php`, `/archiver.php` and
     * `/gallerypopups.php`. The app therefore never parses or shows those results: the card opens the
     * site's own search page in the browser, where the user answers the age gate themselves.
     */
    val EHENTAI = ProviderSpec(
        id = "eh",
        name = "E-Hentai",
        host = "e-hentai.org",
        access = ProviderAccess.BROWSER_ONLY,
        note = "Adult doujin gallery. Browser only: opens the site's search page, age gate handled there.",
        searchTemplate = "https://e-hentai.org/?f_search={q}",
        landingUrl = "https://e-hentai.org/",
    )

    /**
     * Doujinshi.info — `robots.txt` allows everything (`Allow: /`, `search=yes`), but every page
     * request from this client answers **HTTP 403** (the site's bot protection), so nothing can be
     * parsed reliably. Browser-only, with the search URL the site itself uses.
     */
    val DOUJINSHI_INFO = ProviderSpec(
        id = "di",
        name = "Doujinshi.info",
        host = "doujinshi.info",
        access = ProviderAccess.BROWSER_ONLY,
        note = "Browser only: automated requests are refused (HTTP 403), so its search opens in your browser.",
        searchTemplate = "https://www.doujinshi.info/works?query={q}",
        landingUrl = "https://www.doujinshi.info/",
    )

    /**
     * MyAnimeList — no unauthenticated API, and its terms disallow automated collection, so the app
     * never scrapes it. `/topmanga` (the URL in the request) answers **404**; the site's own manga
     * search is `/manga.php?q=...&cat=manga`, which is what the card opens.
     */
    val MYANIMELIST = ProviderSpec(
        id = "mal",
        name = "MyAnimeList",
        host = "myanimelist.net",
        access = ProviderAccess.BROWSER_ONLY,
        note = "Manga catalogue. Browser only: no unauthenticated API; its manga search opens in your browser.",
        searchTemplate = "https://myanimelist.net/manga.php?q={q}&cat=manga",
        landingUrl = "https://myanimelist.net/manga.php",
    )

    /**
     * nhentai — every request (home, robots.txt, search) answers **HTTP 403** (Cloudflare), and the
     * catalogue is adult content. Browser-only: the card opens the site's own search page, where the
     * user handles the age gate. No request is ever made by the app.
     */
    val NHENTAI = ProviderSpec(
        id = "nh",
        name = "nhentai",
        host = "nhentai.net",
        access = ProviderAccess.BROWSER_ONLY,
        note = "Adult gallery. Browser only: automated requests are refused (HTTP 403), age gate handled there.",
        searchTemplate = "https://nhentai.net/search/?q={q}",
        landingUrl = "https://nhentai.net/",
    )

    val DLSITE = ProviderSpec(
        id = DlsiteResponseParser.PROVIDER_ID,
        name = DlsiteResponseParser.SOURCE_NAME,
        host = "dlsite.com",
        access = ProviderAccess.AUTOMATED,
        note = "Doujinshi and digital works. Public books search page, first page only.",
        searchTemplate = "https://www.dlsite.com/books/fsr/=/keyword/{q}",
        landingUrl = "https://www.dlsite.com/books/",
    )

    /**
     * Display order: the three in-app catalogues first, then every browser-only shortcut, in the
     * order the panel shows them.
     */
    val ALL: List<ProviderSpec> = listOf(
        OPEN_LIBRARY,
        GOOGLE_BOOKS,
        DLSITE,
        MELONBOOKS,
        SURUGAYA,
        DOUJIN_NO_MORI,
        EHENTAI,
        DOUJINSHI_INFO,
        MYANIMELIST,
        NHENTAI,
    )

    /** Only these are ever queried by the app (and only these can be selected). */
    val AUTOMATED: List<ProviderSpec> = ALL.filter { it.access == ProviderAccess.AUTOMATED }

    /** Selectable providers — derived from [ProviderSpec.selectable], never stored separately. */
    val SELECTABLE: List<ProviderSpec> = ALL.filter { it.selectable }

    /** Only these get a "search in browser" card, and they can never be selected. */
    val BROWSER_ONLY: List<ProviderSpec> = ALL.filter { it.access == ProviderAccess.BROWSER_ONLY }

    fun byId(id: String): ProviderSpec? = ALL.firstOrNull { it.id.equals(id, ignoreCase = true) }
}
