package com.bookcovermatcher.core.online

import com.bookcovermatcher.core.wishlist.ItemIdentity
import com.bookcovermatcher.core.wishlist.MatchType

/**
 * Merges the results of every provider the search ran into one list.
 *
 * Today there is exactly one provider — Open Library, a library catalogue — so this is mostly the
 * seam that keeps the rest of the app from assuming there will only ever be one. It does four
 * things, and each of them is tested:
 *
 *  1. **Identity.** Two providers (or one provider twice) that return the same product collapse to
 *     one card, using [ItemIdentity] — a provider key first, a normalised URL otherwise.
 *  2. **Ranking.** A documented preference order ([rankOf]); the best rank wins when duplicates
 *     disagree. No provider currently claims anything better than [RANK_CATALOGUE], and nothing
 *     here invents a shopping or visual-match rank.
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
     * A provider key is stable (`ol:OL27482W`, `gb:<volumeId>`), so it is the identity of a result.
     * A provider without a key falls back to its normalised URL inside [ItemIdentity].
     */
    fun itemKey(result: ProductSearchResult): String {
        val provider = result.providerId.takeIf { it.isNotBlank() }
            ?: if (result.source.equals(OpenLibraryResponseParser.SOURCE_NAME, ignoreCase = true)) "ol" else ""
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

    fun rankOf(result: ProductSearchResult): Int = RANK_CATALOGUE

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
