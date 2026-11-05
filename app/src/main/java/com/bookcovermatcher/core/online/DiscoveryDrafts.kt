// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.online

import com.bookcovermatcher.core.online.reverse.ReverseImageProviders

/**
 * The rules that turn a reverse-image candidate into something the collection workflow can store.
 *
 * Kept here (plain Kotlin) rather than in the Compose layer so the important decisions are unit-testable:
 *
 *  * **One identity per candidate.** The candidate's own key (`id:RJ01234567`, `url:https://…`, `row:iqdb:…`) is
 *    the store identity, so bookmarking the same result twice — in this search or a later one — resolves to the
 *    same item and cannot create a duplicate.
 *  * **Provider attribution is preserved.** A candidate's provider list becomes a readable label
 *    (`SauceNAO`, `IQDB`, `SauceNAO + IQDB`), because "who found it" is evidence the wishlist keeps.
 *  * **Rejected is not eligible.** The user has explicitly said the candidate is not the book; a bulk operation
 *    must never re-add it. Dismissed candidates are already gone from the list.
 */
object DiscoveryDrafts {

    /** The identity a candidate keeps in the wishlist store: its candidate key, canonicalised. */
    fun fingerprintOf(candidateKey: String): String = candidateKey.trim().lowercase()

    /** `SauceNAO`, `IQDB` or `SauceNAO + IQDB`, in provider priority order. */
    fun providerLabel(providerIds: Collection<String>): String {
        val known = providerIds
            .map { it.trim().lowercase() }
            .distinct()
            .sortedBy { ReverseImageProviders.priorityOf(it) }
            .mapNotNull { id ->
                when (id) {
                    ReverseImageProviders.SAUCE_NAO_ID -> "SauceNAO"
                    ReverseImageProviders.IQDB_ID -> "IQDB"
                    else -> null
                }
            }
        return known.joinToString(" + ").ifEmpty { "Discovery" }
    }

    /**
     * True when a candidate may be stored or bulk-stored.
     *
     * `SUGGESTED` is eligible: an unconfirmed suggestion is still a result the user may want to keep. `CONFIRMED` is
     * obviously eligible. `REJECTED` is not.
     */
    fun isEligible(decision: CandidateDecision): Boolean = decision != CandidateDecision.REJECTED

    /**
     * True when there is at least one fact worth storing.
     *
     * The store refuses a draft with no title (`validateDraft`), and inventing one would be worse than skipping: a
     * result with no title, no product id and no URL has nothing to record, so it stays an on-screen suggestion.
     */
    fun isStorable(title: String, productId: String, sourceUrl: String?): Boolean =
        title.isNotBlank() || productId.isNotBlank() || !sourceUrl.isNullOrBlank()
}
