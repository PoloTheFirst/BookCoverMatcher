package com.bookcovermatcher.core.online

import com.bookcovermatcher.core.wishlist.ItemIdentity
import com.bookcovermatcher.core.wishlist.MatchType

/**
 * Merges the results of every provider a search ran into one list.
 *
 * Three providers are searched automatically (Open Library, Google Books, DLsite) and two are
 * browser-only (Melonbooks, Suruga-ya — see [ProviderCatalog]); this class only ever sees the
 * results of the automated ones. It does four things, and each of them is tested:
 *
 *  1. **Identity.** Two providers (or one provider twice) that return the same product collapse to
 *     one card, using [ItemIdentity] — a provider key first, a normalised URL otherwise. A DLsite
 *     work and a library record of a similar title are *not* the same product and stay apart.
 *  2. **Ranking.** A documented preference order ([rankOf]); the best rank wins when duplicates
 *     disagree. A shop listing ranks as [RANK_MARKETPLACE], a catalogue record as
 *     [RANK_CATALOGUE], and nothing here invents a visual-match rank.
 *  3. **Promotion.** Items already bookmarked are moved to the front of the live list (Outcome 1)
 *     without reordering the workbook top-5, which is ranked by cosine elsewhere.
 *  4. **The count.** The returned list *is* what the panel draws, so "N results" can never disagree
 *     with the number of cards.
 */
object OnlineAggregator {

    /** Relevant shopping / marketplace hits. No provider in this build returns these. */
    const val RANK_MARKETPLACE = 1

    /** Genuine image-similarity hits. Only a provider that really performed a visual search may use it. */
    const val RANK_VISUAL = 2

    /** Publisher, creator, circle or community pages. */
    const val RANK_CREATOR = 3

    /** Library catalogues and general web results — where Open Library lives. */
    const val RANK_CATALOGUE = 4

    /** Related or uncertain matches. */
    const val RANK_RELATED = 5

    /**
     * Identity of a result: its provider key (`ol:OL27482W`, `gb:<volumeId>`, `dl:rj01730150`)
     * first, a normalised URL otherwise. Two providers never collapse into one card, so a DLsite
     * listing and a library record of "the same" book stay two results.
     */
    fun itemKey(result: ProductSearchResult): String {
        val provider = result.providerId.takeIf { it.isNotBlank() }
            ?: if (result.source.equals(OpenLibraryResponseParser.SOURCE_NAME, ignoreCase = true)) {
                OpenLibraryResponseParser.PROVIDER_ID
            } else {
                ""
            }
        return if (provider.isNotBlank()) {
            ItemIdentity.fingerprintFor(providerKey = "$provider:${result.id}", url = result.detailUrl, title = result.title)
        } else {
            ItemIdentity.fingerprintFor(url = result.detailUrl, title = result.title)
        }
    }

    /**
     * What the current stack can honestly claim about how a result was found: the cover was read
     * with OCR and its text was searched. Never [MatchType.VISUAL] — no provider here does image
     * similarity.
     */
    fun matchTypeOf(result: ProductSearchResult): MatchType = MatchType.TEXT_QUERY

    /**
     * How a result is ordered in the merged list. A shop listing (DLsite) is a marketplace hit; a
     * library catalogue record ranks below it. Nothing here can claim [RANK_VISUAL]: no provider in
     * this build performs an image search.
     */
    fun rankOf(result: ProductSearchResult): Int =
        if (result.providerId.lowercase() in MARKETPLACE_PROVIDER_IDS) RANK_MARKETPLACE else RANK_CATALOGUE

    /** `dl` today; the browser-only shops keep their ids reserved for the same rank. */
    private val MARKETPLACE_PROVIDER_IDS: Set<String> =
        (ProviderCatalog.BROWSER_ONLY.map { it.id } + DlsiteResponseParser.PROVIDER_ID)
            .map { it.lowercase() }
            .toSet()

    /**
     * @param batches one list per provider, in the order they were searched
     * @param promotedKeys identities of bookmarked items, shown first
     */
    fun merge(
        batches: List<List<ProductSearchResult>>,
        promotedKeys: Set<String> = emptySet(),
        limit: Int = 10,
    ): List<ProductSearchResult> {
        if (limit <= 0) return emptyList()
        val seen = LinkedHashMap<String, ProductSearchResult>()
        for (batch in batches) {
            for (result in batch) {
                val key = itemKey(result)
                val existing = seen[key]
                if (existing == null || rankOf(result) < rankOf(existing)) seen[key] = result
            }
        }
        val all = seen.values.toList()
        val promoted = all.filter { itemKey(it) in promotedKeys }
        val rest = all.filterNot { itemKey(it) in promotedKeys }
        return (promoted + rest).take(limit)
    }
}
