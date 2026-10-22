package com.bookcovermatcher.core.online

import com.bookcovermatcher.core.wishlist.MiniJson

/**
 * Reads a Google Books *Volumes* search response (`https://www.googleapis.com/books/v1/volumes`)
 * into [ProductSearchResult]s.
 *
 * Google Books is a second **catalogue**, not a visual-search engine and not a shop: it returns
 * structured bibliographic records, a cover image and a page on books.google.com. Nothing here
 * invents a price or a stock status. Free, no API key for search, same honest `Text match` label as
 * Open Library.
 *
 * Plain Kotlin/JVM: unit-tested against captured responses.
 */
object GoogleBooksResponseParser {
    const val SOURCE_NAME = "Google Books"
    const val PROVIDER_ID = "gb"
    const val SITE = "https://books.google.com/books?id="

    private val VOLUME_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    private const val MAX_AUTHORS = 4

    class ParseException(message: String) : RuntimeException(message)

    /**
     * @return results (at most [limit]) and Google's own total, when it reports one.
     * @throws ParseException when the body is not the documented shape.
     */
    fun parse(json: String, limit: Int): Pair<List<ProductSearchResult>, Int?> {
        if (limit <= 0) return emptyList<ProductSearchResult>() to null
        val root = try {
            MiniJson.parse(json)
        } catch (e: Exception) {
            throw ParseException("The Google Books reply was not valid JSON: ${e.message}")
        }
        val map = root as? Map<*, *> ?: throw ParseException("The response is not a JSON object.")
        // A search with no hits omits "items" entirely; that is an empty catalogue, not an error.
        // An "items" field of the wrong type, however, is a malformed reply.
        val rawItems = map["items"]
        if (rawItems != null && rawItems !is List<*>) {
            throw ParseException("The response has an \"items\" field that is not an array.")
        }
        val items = rawItems as? List<*> ?: return emptyList<ProductSearchResult>() to intOf(map["totalItems"])
        val out = ArrayList<ProductSearchResult>(minOf(limit, items.size))
        for (raw in items) {
            if (out.size >= limit) break
            parseVolume(raw)?.let { out.add(it) }
        }
        return out to intOf(map["totalItems"])
    }

    private fun parseVolume(raw: Any?): ProductSearchResult? {
        val volume = raw as? Map<*, *> ?: return null
        val id = (volume["id"] as? String)?.trim()?.takeIf { VOLUME_ID.matches(it) } ?: return null
        val info = volume["volumeInfo"] as? Map<*, *> ?: return null
        val title = (info["title"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        val links = info["imageLinks"] as? Map<*, *>
        val thumbnail = (links?.get("thumbnail") as? String) ?: (links?.get("smallThumbnail") as? String)

        val detail = (info["canonicalVolumeLink"] as? String)
            ?.takeIf { it.startsWith("https://") }
            ?: (info["infoLink"] as? String)?.takeIf { it.startsWith("https://") }
            ?: (SITE + id)

        val publisher = (info["publisher"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val language = (info["language"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val identifier = (info["industryIdentifiers"] as? List<*>)
            ?.asSequence()
            ?.mapNotNull { it as? Map<*, *> }
            ?.mapNotNull { (it["identifier"] as? String)?.trim()?.takeIf(String::isNotEmpty) }
            ?.firstOrNull()

        return ProductSearchResult(
            id = id,
            title = title,
            authors = stringList(info["authors"], MAX_AUTHORS),
            publishedYear = yearOf(info["publishedDate"] as? String),
            publishers = listOfNotNull(publisher),
            pageCount = intOf(info["pageCount"])?.takeIf { it > 0 },
            languages = listOfNotNull(language),
            // Google may send http:// for covers; the same host is safe over https.
            coverUrl = thumbnail?.trim()?.replaceFirst("http://", "https://")?.takeIf { it.startsWith("https://") },
            detailUrl = detail,
            source = SOURCE_NAME,
            providerId = PROVIDER_ID,
            description = (info["description"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
            identifier = identifier,
        )
    }

    private fun yearOf(date: String?): Int? = date?.trim()?.take(4)?.toIntOrNull()?.takeIf { it in 1000..2100 }

    private fun intOf(value: Any?): Int? = when (value) {
        is Double -> if (value.isFinite()) value.toInt() else null
        is Int -> value
        is String -> value.trim().toIntOrNull()
        else -> null
    }

    private fun stringList(value: Any?, max: Int): List<String> {
        val items = when (value) {
            is List<*> -> value
            is String -> listOf(value)
            else -> return emptyList()
        }
        return items.asSequence()
            .filterIsInstance<String>()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(max)
            .toList()
    }
}
