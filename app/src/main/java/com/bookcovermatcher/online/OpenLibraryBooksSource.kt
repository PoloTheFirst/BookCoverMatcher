package com.bookcovermatcher.online

import com.bookcovermatcher.core.online.OnlineFailure
import com.bookcovermatcher.core.online.OpenLibraryResponseParser
import com.bookcovermatcher.core.online.ProductSearchDataSource
import com.bookcovermatcher.core.online.ProductSearchOutcome
import com.bookcovermatcher.core.online.ProductSourceException
import java.net.URLEncoder

/**
 * Product discovery through the **Open Library Books API**.
 *
 * Why this source:
 *  * it is free and needs no API key, so the app ships no secret and asks for no account;
 *  * it returns *structured* records (title, authors, year, publisher, cover, page count);
 *  * it is a library catalogue rather than a shop, so it never shows a price we would have to
 *    invent, and every result links to a page that exists on openlibrary.org;
 *  * its data is published under the Open Data License / public domain.
 *
 * Rate limits: Open Library asks public clients to keep it light. One request per user action and
 * a hard cap of [MAX_LIMIT] results is well inside that; a 429 is reported as
 * [OnlineFailure.RATE_LIMITED] rather than retried behind the user's back.
 *
 * The request is synchronous; run it on [kotlinx.coroutines.Dispatchers.IO].
 */
class OpenLibraryBooksSource(
    private val http: HttpJsonClient = HttpJsonClient(),
) : ProductSearchDataSource {

    override val name: String get() = OpenLibraryResponseParser.SOURCE_NAME

    /** The Books API accepts a large `limit`; the app keeps its own ceiling at the panel's maximum. */
    override val maxResults: Int get() = MAX_LIMIT

    override fun search(query: String, limit: Int): ProductSearchOutcome {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return ProductSearchOutcome.Empty(trimmed)

        val capped = limit.coerceIn(1, MAX_LIMIT)
        val url = buildUrl(trimmed, capped)

        return try {
            val response = http.getJson(url, acceptLanguage = "en")
            val parsed = try {
                OpenLibraryResponseParser.parse(response.body, capped)
            } catch (e: Exception) {
                throw ProductSourceException(
                    OnlineFailure.BAD_RESPONSE,
                    "The catalogue sent something unexpected: ${e.message ?: e.javaClass.simpleName}",
                    e,
                )
            }
            val (items, total) = parsed
            if (items.isEmpty()) ProductSearchOutcome.Empty(trimmed)
            else ProductSearchOutcome.Found(trimmed, items, total)
        } catch (e: ProductSourceException) {
            ProductSearchOutcome.Failed(e.failure, e.message ?: e.failure.name)
        }
    }

    private fun buildUrl(query: String, limit: Int): String =
        "$ENDPOINT?limit=$limit&fields=${OpenLibraryResponseParser.FIELDS}" +
            "&q=" + URLEncoder.encode(query, "UTF-8")

    companion object {
        const val ENDPOINT = "https://openlibrary.org/search.json"

        /** The API allows more; the panel's own maximum is 50, so nothing larger is ever requested. */
        const val MAX_LIMIT = 50
    }
}
