package com.bookcovermatcher.online

import com.bookcovermatcher.core.online.DlsiteResponseParser
import com.bookcovermatcher.core.online.OnlineFailure
import com.bookcovermatcher.core.online.ProductSearchDataSource
import com.bookcovermatcher.core.online.ProductSearchOutcome
import com.bookcovermatcher.core.online.ProductSourceException

/**
 * Product discovery through **DLsite**'s public books search page.
 *
 * Why this source: the workbook workflow exists for Japanese doujinshi catalogues, and DLsite is the
 * largest digital doujinshi shop. Its keyword search is served as ordinary HTML to any client that
 * identifies itself, so no key, account or cookie is needed — measured live on 2026-10-22: the app's
 * own user agent got HTTP 200 with 30 real works for `東方` and a real (empty) result page for a
 * nonsense query.
 *
 * Honesty rules this class follows:
 *  * one request per user action, no pagination, no retry loop (the site's `robots.txt` asks for a
 *    10-second crawl delay and disallows the paged URLs);
 *  * only the five fields the listing really contains are returned — title, circle, price, cover,
 *    product URL. No stock status, rating or description is invented;
 *  * a 403/451 is reported as [OnlineFailure.BLOCKED] instead of being retried with a fake browser
 *    identity.
 *
 * The request is synchronous; run it on [kotlinx.coroutines.Dispatchers.IO].
 */
class DlsiteSource(
    private val http: HttpJsonClient = HttpJsonClient(readTimeoutMs = 25_000, maxBytes = 2 * 1024 * 1024),
) : ProductSearchDataSource {

    override val name: String get() = DlsiteResponseParser.SOURCE_NAME

    /** One page of 30 works: the app never paginates DLsite, so this is the ceiling. */
    override val maxResults: Int get() = MAX_LIMIT

    override fun search(query: String, limit: Int): ProductSearchOutcome {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return ProductSearchOutcome.Empty(trimmed)
        val capped = limit.coerceIn(1, MAX_LIMIT)

        return try {
            val page = http.getHtml(DlsiteResponseParser.searchUrl(trimmed), acceptLanguage = "ja")
            val items = try {
                DlsiteResponseParser.parse(page.body, capped)
            } catch (e: Exception) {
                throw ProductSourceException(
                    OnlineFailure.BAD_RESPONSE,
                    "DLsite sent something unexpected: ${e.message ?: e.javaClass.simpleName}",
                    e,
                )
            }
            if (items.isEmpty()) ProductSearchOutcome.Empty(trimmed)
            else ProductSearchOutcome.Found(trimmed, items, null)
        } catch (e: ProductSourceException) {
            ProductSearchOutcome.Failed(e.failure, e.message ?: e.failure.name)
        }
    }

    companion object {
        /** DLsite lists 30 works per page and paged URLs are disallowed by its robots file. */
        const val MAX_LIMIT = 30
    }
}
