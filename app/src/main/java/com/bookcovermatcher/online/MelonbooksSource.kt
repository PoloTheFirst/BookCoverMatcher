package com.bookcovermatcher.online

import com.bookcovermatcher.core.online.MelonbooksResponseParser
import com.bookcovermatcher.core.online.OnlineFailure
import com.bookcovermatcher.core.online.ProductSearchDataSource
import com.bookcovermatcher.core.online.ProductSearchOutcome
import com.bookcovermatcher.core.online.ProductSourceException

/**
 * Product discovery through the **Melonbooks** public search page.
 *
 * Access facts this class depends on (measured 2026-10-23, `ONLINE_SEARCH.md` §2):
 *  * `robots.txt` → **HTTP 404**, i.e. no crawl rules published (RFC 9309 treats 4xx as "allowed");
 *  * the search page → **HTTP 200**, UTF-8, served to this app's own user agent with no cookie.
 *
 * It therefore behaves like the other adapters: **one** request per user-initiated search, first page
 * only, no pagination, no retry, and a 401/403/451 is reported as [OnlineFailure.BLOCKED] rather than
 * being disguised as a browser request.
 *
 * The request is synchronous; run it on [kotlinx.coroutines.Dispatchers.IO].
 */
class MelonbooksSource(
    private val http: HttpJsonClient = HttpJsonClient(readTimeoutMs = 25_000, maxBytes = 3 * 1024 * 1024),
) : ProductSearchDataSource {

    override val name: String get() = MelonbooksResponseParser.SOURCE_NAME

    override val maxResults: Int get() = MAX_LIMIT

    override fun search(query: String, limit: Int): ProductSearchOutcome {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return ProductSearchOutcome.Empty(trimmed)
        val capped = limit.coerceIn(1, MAX_LIMIT)

        return try {
            val page = http.getHtml(MelonbooksResponseParser.searchUrl(trimmed), acceptLanguage = "ja")
            val items = try {
                MelonbooksResponseParser.parse(page.body, capped)
            } catch (e: Exception) {
                throw ProductSourceException(
                    OnlineFailure.BAD_RESPONSE,
                    "Melonbooks sent something unexpected: ${e.message ?: e.javaClass.simpleName}",
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
        /** The shop lists 100 products per page; the app never asks for or shows more than 50. */
        const val MAX_LIMIT = 50
    }
}
