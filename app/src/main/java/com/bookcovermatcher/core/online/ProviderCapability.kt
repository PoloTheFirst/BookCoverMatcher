// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.online

/**
 * What one provider can honestly be asked, so a screen never promises a dimension the provider ignores.
 *
 * The capabilities are derived from the adapters that actually exist in this build, not from what a
 * provider's marketing says:
 *
 *  * `ol` **Open Library** — its Books API takes a title and an author separately, and sends an ISBN as a
 *    `q` term, so title, author and identifier searches are real. It has no circle and no image search.
 *  * `gb` **Google Books** — the volumes API takes `intitle:`/`inauthor:` and understands an ISBN, so the
 *    same three. It has no circle and no image search.
 *  * `dl` **DLsite** — a keyword search page: one string matches titles **and** circle names, and an
 *    `RJ…` code is accepted as a keyword, so title, circle and code. There is no author field on that
 *    page, and no image search.
 *
 * **No provider in this build supports [SearchField.IMAGE_REFERENCE].** Submitting an image to a
 * provider and asking it to find visually similar products (reverse-image discovery) is a different
 * capability from comparing downloaded covers locally, and none of these providers documents a
 * legitimate endpoint for it. Keeping the flag false is what stops the UI from implying otherwise.
 *
 * Plain Kotlin/JVM; unit-tested.
 */
data class ProviderCapability(
    val providerId: String,
    /** The identifying attributes this provider's own search accepts. */
    val fields: Set<SearchField>,
    /** How many results one request returns before the app would have to paginate (documented, not guessed). */
    val pageSize: Int,
    /** One line for the UI: what this provider does and does not search. */
    val summary: String,
) {
    fun supports(field: SearchField): Boolean = field in fields

    /** [query]'s fields this provider cannot use — what the UI must report as ignored. */
    fun ignoredFields(query: OnlineQuery): List<SearchField> =
        query.fields.filter { it !in fields }
}

/**
 * The capability table for the providers that can be queried in-app.
 *
 * Only ids present in [ProviderCatalog.AUTOMATED] appear here: a browser-only provider is not searched by
 * the app at all, so it has no in-app capability row by design.
 */
object ProviderCapabilities {

    val OPEN_LIBRARY = ProviderCapability(
        providerId = ProviderCatalog.OPEN_LIBRARY.id,
        fields = setOf(SearchField.TITLE, SearchField.AUTHOR, SearchField.CODE),
        pageSize = 20,
        summary = "Searches Open Library by title, author or ISBN. It has no circle field and no image search.",
    )

    val GOOGLE_BOOKS = ProviderCapability(
        providerId = ProviderCatalog.GOOGLE_BOOKS.id,
        fields = setOf(SearchField.TITLE, SearchField.AUTHOR, SearchField.CODE),
        pageSize = 20,
        summary = "Searches Google Books by title, author or ISBN. It has no circle field and no image search.",
    )

    val DLSITE = ProviderCapability(
        providerId = ProviderCatalog.DLSITE.id,
        fields = setOf(SearchField.TITLE, SearchField.CIRCLE, SearchField.CODE),
        pageSize = 30,
        summary = "Searches DLsite by keyword: a title, a circle name or an RJ code. It has no author field " +
            "and no image search.",
    )

    /** Every capability of this build, in catalogue order. */
    val ALL: List<ProviderCapability> = listOf(OPEN_LIBRARY, GOOGLE_BOOKS, DLSITE)

    /** The capability of [providerId], or null when this build does not query that provider. */
    fun of(providerId: String): ProviderCapability? =
        ALL.firstOrNull { it.providerId == providerId.trim().lowercase() }

    /**
     * True when [providerId] can be given something from [query]. A provider is skipped — not failed —
     * when the query only uses fields it cannot search, which is why an author-only search leaves DLsite
     * out instead of returning its title-keyword noise.
     */
    fun canSearch(providerId: String, query: OnlineQuery): Boolean {
        val capability = of(providerId) ?: return false
        if (query.isBlank) return false
        return !query.isEmptyWithout(capability.ignoredFields(query))
    }

    /** The providers of [selected] that can act on [query], in catalogue order. */
    fun eligible(selected: Collection<String>, query: OnlineQuery): List<ProviderSpec> =
        ProviderCatalog.AUTOMATED.filter { it.id in selected && canSearch(it.id, query) }

    /** The providers of [selected] that must be reported as "cannot search this" for [query]. */
    fun skipped(selected: Collection<String>, query: OnlineQuery): List<ProviderSpec> =
        ProviderCatalog.AUTOMATED.filter { it.id in selected && !canSearch(it.id, query) }
}
