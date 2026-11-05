// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.online

/**
 * Where a provider sits in the release's source policy.
 *
 * [PRIMARY_DOUJIN] is the anchor for doujin identity (DLsite). [SECONDARY_BOOK] is supplementary general-book
 * metadata (Open Library, Google Books): useful, never sufficient on its own to call a doujin match, and
 * switchable off by the user — when it is off, its requests are not issued at all.
 */
enum class ProviderTier { PRIMARY_DOUJIN, SECONDARY_BOOK }

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
 * **No metadata provider in this build supports [SearchField.IMAGE_REFERENCE].** Submitting an image to a
 * provider and asking it to find visually similar products is reverse-image discovery, which is a separate
 * subsystem with its own two providers (`ReverseImageProviders`); keeping the flag false here is what stops
 * the text-search screens from implying they can do it.
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
    /** Primary doujin anchor or supplementary general-book source. */
    val tier: ProviderTier = ProviderTier.SECONDARY_BOOK,
) {
    fun supports(field: SearchField): Boolean = field in fields

    /** True for a source the user may switch off. */
    val secondary: Boolean get() = tier == ProviderTier.SECONDARY_BOOK

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
        tier = ProviderTier.SECONDARY_BOOK,
    )

    val GOOGLE_BOOKS = ProviderCapability(
        providerId = ProviderCatalog.GOOGLE_BOOKS.id,
        fields = setOf(SearchField.TITLE, SearchField.AUTHOR, SearchField.CODE),
        pageSize = 20,
        summary = "Searches Google Books by title, author or ISBN. It has no circle field and no image search.",
        tier = ProviderTier.SECONDARY_BOOK,
    )

    val DLSITE = ProviderCapability(
        providerId = ProviderCatalog.DLSITE.id,
        fields = setOf(SearchField.TITLE, SearchField.CIRCLE, SearchField.CODE),
        pageSize = 30,
        summary = "Searches DLsite by keyword: a title, a circle name or an RJ code. It has no author field " +
            "and no image search.",
        tier = ProviderTier.PRIMARY_DOUJIN,
    )

    /** Every capability of this build, in catalogue order. */
    val ALL: List<ProviderCapability> = listOf(OPEN_LIBRARY, GOOGLE_BOOKS, DLSITE)

    /** The capability of [providerId], or null when this build does not query that provider. */
    fun of(providerId: String): ProviderCapability? =
        ALL.firstOrNull { it.providerId == providerId.trim().lowercase() }

    /** True when [providerId] is a supplementary general-book source the user may switch off. */
    fun isSecondary(providerId: String): Boolean = of(providerId)?.secondary == true

    /** Every secondary (general-book) provider, in catalogue order. */
    fun secondaryProviders(): List<ProviderSpec> =
        ProviderCatalog.AUTOMATED.filter { isSecondary(it.id) }

    /** Every primary (doujin) provider, in catalogue order. */
    fun primaryProviders(): List<ProviderSpec> =
        ProviderCatalog.AUTOMATED.filter { !isSecondary(it.id) }

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

    /**
     * The providers of [selected] that can act on [query], in catalogue order.
     *
     * @param includeSecondary false when the user switched the general-book sources off: they are then not
     *        returned at all, so no request is issued and no result is merged. Switching them off must not
     *        look like "they were searched and found nothing".
     */
    fun eligible(
        selected: Collection<String>,
        query: OnlineQuery,
        includeSecondary: Boolean = true,
    ): List<ProviderSpec> = ProviderCatalog.AUTOMATED.filter { spec ->
        spec.id in selected &&
            canSearch(spec.id, query) &&
            (includeSecondary || !isSecondary(spec.id))
    }

    /**
     * The providers of [selected] that must be reported as "cannot search this" (or "switched off") for
     * [query], so the panel can explain every provider it did not use instead of silently dropping it.
     */
    fun skipped(
        selected: Collection<String>,
        query: OnlineQuery,
        includeSecondary: Boolean = true,
    ): List<ProviderSpec> = ProviderCatalog.AUTOMATED.filter { spec ->
        spec.id in selected &&
            (!canSearch(spec.id, query) || (!includeSecondary && isSecondary(spec.id)))
    }
}
