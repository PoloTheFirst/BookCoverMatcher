package com.bookcovermatcher.online

import com.bookcovermatcher.core.online.GoogleBooksResponseParser
import com.bookcovermatcher.core.online.OnlineFailure
import com.bookcovermatcher.core.online.ProductSearchDataSource
import com.bookcovermatcher.core.online.ProductSearchOutcome
import com.bookcovermatcher.core.online.ProductSourceException
import java.net.URLEncoder

/**
 * Product discovery through the **Google Books Volumes API**.
 *
 * A second catalogue next to Open Library, chosen because it is free for search, needs no API key,
 * returns structured records and covers, and links to a page that exists. It is still a text
 * catalogue search: the cover was OCR'd and the text is what is searched. It is not visual search.
 *
 * One request per user action; a 429 is reported, never retried behind the user's back.
 */
class GoogleBooksSource(
    private val http: HttpJsonClient = HttpJsonClient(),
) : ProductSearchDataSource {

    override val name: String get() = GoogleBooksResponseParser.SOURCE_NAME

    override fun search(query: String, limit: Int): ProductSearchOutcome {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return ProductSearchOutcome.Empty(trimmed)
        val capped = limit.coerceIn(1, MAX_LIMIT)
        return try {
            val response = http.getJson(buildUrl(trimmed, capped), acceptLanguage = "en")
            val parsed = try {
                GoogleBooksResponseParser.parse(response.body, capped)
            } catch (e: Exception) {
                throw ProductSourceException(
                    OnlineFailure.BAD_RESPONSE,
                    "Google Books sent something unexpected: ${e.message ?: e.javaClass.simpleName}",
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
        "$ENDPOINT?q=" + URLEncoder.encode(query, "UTF-8") +
            "&maxResults=$limit&printType=books&langRestrict=en"

    companion object {
        const val ENDPOINT = "https://www.googleapis.com/books/v1/volumes"

        /** Google caps `maxResults` at 40; the app keeps the same small limit as Open Library. */
        const val MAX_LIMIT = 10
    }
}
