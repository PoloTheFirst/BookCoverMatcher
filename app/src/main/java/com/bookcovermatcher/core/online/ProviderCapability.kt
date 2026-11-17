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
