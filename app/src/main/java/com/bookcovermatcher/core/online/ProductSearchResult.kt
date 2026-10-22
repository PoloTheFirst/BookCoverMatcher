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
    /** Short stable provider id (`ol`, `gb`) used to build the item's identity. */
    val providerId: String = "",
    /** Provider description, when it sends one. Never invented. */
    val description: String? = null,
    /** Provider identifier (ISBN, volume id), when it sends one. */
    val identifier: String? = null,
    /** The price the shop itself printed (`924円`), or null when the source has none. */
    val priceText: String? = null,
    /**
     * True for a shop listing with a price and a circle (DLsite), false for a bibliographic
     * catalogue record (Open Library, Google Books). It only changes how missing fields are worded:
     * a listing has no "author" and no "publisher", so it must not claim either.
     */
    val isMarketplace: Boolean = false,
) {
    val authorLine: String
        get() = when {
            authors.isNotEmpty() -> authors.joinToString(", ")
            isMarketplace -> "circle not shown"
            else -> "Unknown author"
        }

    val yearText: String
        get() = publishedYear?.toString() ?: "year unknown"

    val publisherLine: String
        get() = publishers.joinToString(", ").ifEmpty { "publisher unknown" }

    /**
     * Short caption under the title. A shop listing shows its real price; a catalogue record shows
     * `1937 · George Allen & Unwin`. Nothing is ever filled in with a guess.
     */
    val metaLine: String
        get() = if (isMarketplace) {
            priceText ?: "price not shown"
        } else {
            listOf(yearText, publisherLine).joinToString(" · ")
        }
}
