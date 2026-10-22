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
 */
class ProviderSpec(
    val id: String,
    val name: String,
    val host: String,
    val access: ProviderAccess,
    /** One sentence, shown under the provider's name. Never a marketing claim. */
    val note: String,
    private val searchTemplate: String,
) {
    /** The provider's own search page for [query], to open in the user's browser. */
    fun searchUrl(query: String): String =
        searchTemplate.replace("{q}", URLEncoder.encode(query.trim(), "UTF-8"))
}

/**
 * The provider list of this build, in the order the panel shows it.
 *
 * **Automated** providers are queried by the app, each through its own adapter:
 *  * `ol` **Open Library** — public Books API (JSON), no key.
 *  * `gb` **Google Books** — public Volumes API (JSON), no key, may answer 429.
 *  * `dl` **DLsite** — public books search page (HTML), read by [DlsiteResponseParser].
 *
 * **Browser-only** providers are *not* queried, because they refuse automated clients. Measured on
 * 2026-10-22 from a desktop network with both a browser user agent and the app's own user agent:
 *
 * ```
 * GET https://www.melonbooks.co.jp/robots.txt                        -> HTTP 403
 * GET https://www.melonbooks.co.jp/search/search.php?mode=search&name=東方 -> HTTP 403
 * GET https://www.suruga-ya.jp/robots.txt                            -> HTTP 403
 * GET https://www.suruga-ya.jp/search?category=&search_word=東方      -> HTTP 403
 * ```
 *
 * A 403 on `robots.txt` itself means even the site's rules cannot be read, let alone obeyed. The
 * app does not attempt to look like a browser, solve a challenge or otherwise defeat that
 * protection; it hands the query to the real browser, where the user is a person the site is happy
 * to serve. This is recorded in `README.md` §6 and `ONLINE_SEARCH.md`.
 *
 * Plain Kotlin/JVM: the query encoding and the access split are unit-tested.
 */
object ProviderCatalog {

    val OPEN_LIBRARY = ProviderSpec(
        id = OpenLibraryResponseParser.PROVIDER_ID,
        name = OpenLibraryResponseParser.SOURCE_NAME,
        host = "openlibrary.org",
        access = ProviderAccess.AUTOMATED,
        note = "Library catalogue. Public Books API, no key.",
        searchTemplate = "https://openlibrary.org/search?q={q}",
    )

    val GOOGLE_BOOKS = ProviderSpec(
        id = GoogleBooksResponseParser.PROVIDER_ID,
        name = GoogleBooksResponseParser.SOURCE_NAME,
        host = "books.google.com",
        access = ProviderAccess.AUTOMATED,
        note = "Book catalogue. Public Volumes API, no key; can answer 429.",
        searchTemplate = "https://www.google.com/search?tbm=bks&q={q}",
    )

    val MELONBOOKS = ProviderSpec(
        id = "mb",
        name = "Melonbooks",
        host = "melonbooks.co.jp",
        access = ProviderAccess.BROWSER_ONLY,
        note = "Doujinshi shop. Refuses automated clients (HTTP 403); opens in your browser.",
        searchTemplate = "https://www.melonbooks.co.jp/search/search.php?mode=search&name={q}",
    )

    val SURUGAYA = ProviderSpec(
        id = "sy",
        name = "Suruga-ya",
        host = "suruga-ya.jp",
        access = ProviderAccess.BROWSER_ONLY,
        note = "Secondhand shop. Refuses automated clients (HTTP 403); opens in your browser.",
        searchTemplate = "https://www.suruga-ya.jp/search?category=&search_word={q}",
    )

    val DLSITE = ProviderSpec(
        id = DlsiteResponseParser.PROVIDER_ID,
        name = DlsiteResponseParser.SOURCE_NAME,
        host = "dlsite.com",
        access = ProviderAccess.AUTOMATED,
        note = "Doujinshi and digital works. Public books search page, one request per search.",
        searchTemplate = "https://www.dlsite.com/books/fsr/=/keyword/{q}",
    )

    /** Display order: the two catalogues, then the three Japanese shops. */
    val ALL: List<ProviderSpec> = listOf(OPEN_LIBRARY, GOOGLE_BOOKS, MELONBOOKS, SURUGAYA, DLSITE)

    /** Only these are ever queried by the app. */
    val AUTOMATED: List<ProviderSpec> = ALL.filter { it.access == ProviderAccess.AUTOMATED }

    /** Only these get a "search in browser" row. */
    val BROWSER_ONLY: List<ProviderSpec> = ALL.filter { it.access == ProviderAccess.BROWSER_ONLY }

    fun byId(id: String): ProviderSpec? = ALL.firstOrNull { it.id.equals(id, ignoreCase = true) }
}
