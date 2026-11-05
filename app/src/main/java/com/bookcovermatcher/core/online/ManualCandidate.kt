// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.online

/**
 * A listing the **user** supplied after doing the search themselves — the fallback for every site the app
 * does not query (and for IQDB, which it will not query at all).
 *
 * Every field is optional except one identifying field (a title or a product code), because the user may
 * only have a URL, or only a title, or a title and a circle. Nothing is invented to fill a gap: an empty
 * field stays empty and is reported as unknown downstream.
 */
data class ManualListing(
    /** The listing's page, pasted by the user. Opened verbatim; never fetched by the app. */
    val url: String = "",
    val title: String = "",
    val circle: String = "",
    val author: String = "",
    /** A product code (`RJ01234567`) or the site's own identifier. */
    val identifier: String = "",
    /** Which site the user found it on, e.g. `Melonbooks`. Free text, shown as provenance. */
    val providerLabel: String = "",
    val note: String = "",
) {
    val isBlank: Boolean
        get() = url.isBlank() && title.isBlank() && circle.isBlank() && author.isBlank() &&
            identifier.isBlank() && note.isBlank()
}

/** The outcome of validating a [ManualListing]: a normalised candidate, or the reasons it cannot be used. */
sealed interface ManualListingResult {
    class Valid(val candidate: ProductSearchResult, val listing: ManualListing) : ManualListingResult

    class Invalid(val errors: List<String>) : ManualListingResult
}

/**
 * Validates and normalises a manual listing into the **same** [ProductSearchResult] every automated provider
 * produces, so the whole evidence/matching pipeline treats it identically.
 *
 * Rules, all testable:
 *
 *  * a title **or** a product code is required — without either there is nothing to compare;
 *  * a URL must be `http(s)` with a host; anything else is rejected rather than opened;
 *  * an `RJ…`-style code is canonicalised with [OnlineQuery.normalizeCode]; other identifiers are only
 *    trimmed and upper-cased, never rewritten;
 *  * text is whitespace-collapsed and length-capped, but Japanese characters, `・`, `～` and full-width
 *    forms are preserved exactly as typed;
 *  * the result is stamped with the `manual` provider id, so no screen can present it as an automated hit.
 *
 * Plain Kotlin/JVM; unit-tested.
 */
object ManualCandidates {

    /** Provider id of every manually supplied candidate. Never a real provider's id. */
    const val PROVIDER_ID = "manual"

    /** Default provenance shown on the card when the user did not name a site. */
    const val SOURCE_NAME = "Manual listing"

    const val MAX_TITLE = 300
    const val MAX_NAME = 160
    const val MAX_URL = 2000
    const val MAX_NOTE = 500

    /** `RJ` + 6–8 digits, optionally spaced or hyphenated: the one code shape this app understands. */
    private val RJ = Regex("(?i)^RJ[\\s_-]?(\\d{6,8})$")

    private val HTTP = Regex("(?i)^https?://")

    fun validate(listing: ManualListing): ManualListingResult {
        val errors = ArrayList<String>(3)
        val url = OnlineQuery.collapse(listing.url).take(MAX_URL)
        val title = OnlineQuery.collapse(listing.title).take(MAX_TITLE)
        val circle = OnlineQuery.collapse(listing.circle).take(MAX_NAME)
        val author = OnlineQuery.collapse(listing.author).take(MAX_NAME)
        val identifier = normalizeIdentifier(listing.identifier)
        val providerLabel = OnlineQuery.collapse(listing.providerLabel).take(MAX_NAME)
        val note = OnlineQuery.collapse(listing.note).take(MAX_NOTE)

        if (url.isNotEmpty()) {
            if (!HTTP.containsMatchIn(url)) {
                errors += "The listing URL must start with http:// or https://."
            } else if (com.bookcovermatcher.core.wishlist.ItemIdentity.normaliseUrl(url) == null) {
                errors += "That is not a valid web address, so the app cannot save it."
            }
        }
        if (title.isEmpty() && identifier.isEmpty()) {
            errors += "Add a title or a product ID \u2014 without one there is nothing to match."
        }

        if (errors.isNotEmpty()) return ManualListingResult.Invalid(errors)

        val clean = ManualListing(
            url = url,
            title = title,
            circle = circle,
            author = author,
            identifier = identifier,
            providerLabel = providerLabel,
            note = note,
        )
        return ManualListingResult.Valid(toResult(clean), clean)
    }

    /**
     * The candidate itself. `detailUrl` is the user's own page when they gave one and **empty** otherwise:
     * the app never invents a URL to open, and the UI checks it before offering an "open" action.
     */
    fun toResult(listing: ManualListing): ProductSearchResult {
        val label = listing.providerLabel.ifBlank { SOURCE_NAME }
        return ProductSearchResult(
            id = idOf(listing),
            title = listing.title.ifBlank { listing.identifier },
            authors = listOf(listing.author).filter { it.isNotBlank() },
            circle = listing.circle.takeIf { it.isNotBlank() },
            publishedYear = null,
            publishers = emptyList(),
            pageCount = null,
            languages = emptyList(),
            coverUrl = null,
            detailUrl = listing.url,
            source = label,
            providerId = PROVIDER_ID,
            description = listing.note.takeIf { it.isNotBlank() },
            identifier = listing.identifier.takeIf { it.isNotBlank() },
            priceText = null,
            // A listing with a circle is a shop-style record: the card then says "price not shown" instead
            // of claiming a publisher and a year that nobody supplied.
            isMarketplace = listing.circle.isNotBlank(),
        )
    }

    /** Stable id: the code when there is one, else the normalised URL, else the normalised title. */
    fun idOf(listing: ManualListing): String {
        val code = normalizeIdentifier(listing.identifier)
        if (code.isNotBlank()) return code.lowercase()
        val url = com.bookcovermatcher.core.wishlist.ItemIdentity.normaliseUrl(listing.url)
        if (url != null) return url
        return "manual:" + OnlineQuery.normalizeText(listing.title)
    }

    /** `rj 0123 4567` → `RJ01234567`; anything else is trimmed and upper-cased but not reinterpreted. */
    fun normalizeIdentifier(raw: String): String {
        val folded = OnlineQuery.foldWidth(raw).trim()
        if (folded.isEmpty()) return ""
        // Separators people insert are dropped only to *recognise* an RJ code; a non-RJ identifier keeps its
        // exact spelling (including a hyphen) because the app does not know that site's numbering.
        val compact = folded.replace(Regex("[\\s_-]"), "")
        return if (RJ.matches(compact)) compact.uppercase() else folded.uppercase()
    }
}
