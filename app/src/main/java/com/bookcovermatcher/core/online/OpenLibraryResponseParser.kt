package com.bookcovermatcher.core.online

import com.bookcovermatcher.core.wishlist.MiniJson

/**
 * Reads an Open Library *Books API* search response (`https://openlibrary.org/search.json`) into
 * [ProductSearchResult]s.
 *
 * Chosen because it is free, needs no API key, returns structured records, and its cover and page
 * URLs are derived from identifiers we control — the app never has to trust a URL that came back
 * in the payload. Reuses [MiniJson] so the feature adds no JSON dependency, exactly like the
 * wishlist does.
 *
 * Plain Kotlin/JVM: unit-tested on a desktop JVM against captured responses.
 */
object OpenLibraryResponseParser {
    const val SOURCE_NAME = "Open Library"

    /** The fields we request. Keep in sync with [parse]. */
    const val FIELDS = "key,title,author_name,first_publish_year,publisher,cover_i,language,number_of_pages"

    const val SITE = "https://openlibrary.org"
    const val COVER_SITE = "https://covers.openlibrary.org"

    /** A work/edition key such as `/works/OL27482W`. Anything else is rejected, not concatenated. */
    private val KEY_PATTERN = Regex("^/(works|books)/(OL[0-9]+[A-Z]?)$")

    private const val MAX_AUTHORS = 4
    private const val MAX_PUBLISHERS = 2
    private const val MAX_LANGUAGES = 3

    class ParseException(message: String) : RuntimeException(message)

    /**
     * @return the results (at most [limit]) and the catalogue's own total, when it reports one.
     * @throws ParseException when [json] is not the shape the API documents.
     */
    fun parse(json: String, limit: Int): Pair<List<ProductSearchResult>, Int?> {
        if (limit <= 0) return emptyList<ProductSearchResult>() to null

        // MiniJson reports malformed input with its own exception type; callers of this parser
        // should only ever have to deal with ParseException.
        val root = try {
            MiniJson.parse(json)
        } catch (e: Exception) {
            throw ParseException("The catalogue reply was not valid JSON: ${e.message}")
        }
        val map = root as? Map<*, *> ?: throw ParseException("The response is not a JSON object.")
        val docs = map["docs"] as? List<*> ?: throw ParseException("The response has no \"docs\" array.")
        val total = (map["numFound"] as? Double)?.let { if (it.isFinite()) it.toInt() else null }

        val out = ArrayList<ProductSearchResult>(minOf(limit, docs.size))
        for (raw in docs) {
            if (out.size >= limit) break
            val doc = raw as? Map<*, *> ?: continue
            parseDoc(doc)?.let { out.add(it) }
        }
        return out to total
    }

    /** Null for records without a usable key or title — they cannot be linked to anything. */
    private fun parseDoc(doc: Map<*, *>): ProductSearchResult? {
        val key = (doc["key"] as? String)?.trim() ?: return null
        if (!KEY_PATTERN.matches(key)) return null

        val title = (doc["title"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val coverId = firstScalar(doc["cover_i"])?.let { asId(it) }

        return ProductSearchResult(
            id = key.substringAfterLast('/'),
            title = title,
            authors = stringList(doc["author_name"], MAX_AUTHORS),
            publishedYear = (doc["first_publish_year"] as? Double)?.let { if (it.isFinite()) it.toInt() else null },
            publishers = stringList(doc["publisher"], MAX_PUBLISHERS),
            pageCount = (doc["number_of_pages"] as? Double)?.let { if (it.isFinite() && it > 0) it.toInt() else null },
            languages = stringList(doc["language"], MAX_LANGUAGES),
            coverUrl = coverId?.let { "$COVER_SITE/b/id/$it-M.jpg" },
            detailUrl = SITE + key,
            source = SOURCE_NAME,
            providerId = "ol",
            identifier = key.substringAfterLast('/'),
        )
    }

    /** A value that the API returns either bare or as a one-element list. */
    private fun firstScalar(value: Any?): Any? = when (value) {
        is List<*> -> value.firstOrNull()
        else -> value
    }

    private fun asId(value: Any?): String? = when (value) {
        is Double -> if (value.isFinite() && value > 0) value.toLong().toString() else null
        is String -> value.takeIf { it.isNotEmpty() && it.all(Char::isLetterOrDigit) }
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
