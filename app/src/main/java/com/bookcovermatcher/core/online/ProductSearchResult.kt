package com.bookcovermatcher.core.online

/**
 * One structured product returned by an online catalogue.
 *
 * Everything here comes from the catalogue's own response: the app never invents a title, a price
 * or a link. [detailUrl] is always the catalogue's own page for the item, so tapping a result
 * opens a page that really exists.
 *
 * Plain Kotlin/JVM (no `android.*`) so it is unit-testable on a desktop JVM.
 */
class ProductSearchResult(
    /** Identifier inside the catalogue, e.g. an Open Library work key `OL27482W`. */
    val id: String,
    val title: String,
    val authors: List<String>,
    val publishedYear: Int?,
    val publishers: List<String>,
    val pageCount: Int?,
    val languages: List<String>,
    /** Thumbnail of the cover, or null when the catalogue has no cover for this item. */
    val coverUrl: String?,
    /** The catalogue's own page for this item. Always an absolute `https` URL. */
    val detailUrl: String,
    /** Human name of the catalogue the item came from, e.g. `"Open Library"`. */
    val source: String,
) {
    val authorLine: String
        get() = if (authors.isEmpty()) "Unknown author" else authors.joinToString(", ")

    val yearText: String
        get() = publishedYear?.toString() ?: "year unknown"

    val publisherLine: String
        get() = publishers.joinToString(", ").ifEmpty { "publisher unknown" }

    /** Short caption under the title: `1937 · George Allen & Unwin`. */
    val metaLine: String
        get() = listOf(yearText, publisherLine).joinToString(" · ")
}
