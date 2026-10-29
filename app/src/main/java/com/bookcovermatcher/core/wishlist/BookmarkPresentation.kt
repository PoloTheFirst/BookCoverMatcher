package com.bookcovermatcher.core.wishlist

/**
 * The text a saved bookmark card shows, derived in one place.
 *
 * The Search tab draws a live catalogue result through `ProductResultCard`, which reads its lines
 * from the provider's structured response (`ProductSearchResult.authorLine` / `metaLine`). A saved
 * bookmark keeps the same fields in the store, but the Wishlist card used to flatten them into one
 * `circle … · id … · ¥770` caption, so the same record read differently on the two tabs and the
 * circle could look like an artist.
 *
 * These functions rebuild the Search card's lines from the stored fields. The rules are the same on
 * both cards: the circle is never mixed into the artist list, a value the source never sent is
 * drawn as [MISSING] instead of a guess, and the match chip uses the Search card's wording.
 *
 * Plain Kotlin (no `android.*`), so the whole mapping is unit-tested on a desktop JVM.
 */
object BookmarkPresentation {

    /** What every result card prints for a value its source never sent. */
    const val MISSING = "\u2014"

    /** Wording for a record the app could not verify against a provider. Never "Text match". */
    private const val UNVERIFIED = "Unverified match"

    /**
     * The line under the title: the genuine artists, joined the way the Search card joins them.
     *
     * A shop listing can have a circle and no parsed artist. The circle is a **display** fallback
     * only: `authors` and `circle` stay two separate stored fields (see `ItemDraft`), and the
     * expanded card draws them as two separate rows.
     */
    fun authorLine(authors: List<String>, circle: String?): String {
        val artists = artists(authors)
        if (artists.isNotEmpty()) return artists.joinToString(", ")
        return circle?.trim()?.takeIf { it.isNotEmpty() } ?: MISSING
    }

    /** The expanded `Artist` row, or null when the record has no artist at all. */
    fun artistLine(authors: List<String>): String? =
        artists(authors).joinToString(", ").ifEmpty { null }

    /**
     * The caption under [authorLine]: the price the shop itself printed, else the identifier the
     * source sent. Both missing is [MISSING], never "¥0" or a guess.
     */
    fun metaLine(price: String?, identifier: String?): String =
        price?.trim()?.takeIf { it.isNotEmpty() }
            ?: identifier?.trim()?.takeIf { it.isNotEmpty() }
            ?: MISSING

    /** The first chip: the catalogue's name, then its domain, then the item's own kind label. */
    fun sourceLabel(provider: String?, domain: String?, kindLabel: String): String =
        provider?.trim()?.takeIf { it.isNotEmpty() }
            ?: domain?.trim()?.takeIf { it.isNotEmpty() }
            ?: kindLabel

    /**
     * The second chip, worded exactly like the Search card's `matchType`. A typed bookmark is
     * `Manual`; a record the app never verified says so rather than claiming a text match.
     */
    fun matchTypeLabel(matchType: MatchType, verified: Boolean): String = when (matchType) {
        MatchType.WORKBOOK -> "Workbook match"
        MatchType.TEXT_QUERY -> if (verified) "Text match" else UNVERIFIED
        MatchType.VISUAL -> if (verified) "Visual match" else UNVERIFIED
        MatchType.MANUAL -> "Manual"
    }

    /** Trimmed, non-blank, de-duplicated — order preserved so the first artist stays first. */
    private fun artists(authors: List<String>): List<String> =
        authors.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
