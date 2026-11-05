package com.bookcovermatcher

/**
 * The two in-memory candidate lists the reverse-image section draws — pasted rows first, then the rows this search
 * returned — with the rules that are allowed to edit them.
 *
 * Pure, so the awkward cases can be unit-tested without a provider, a socket or a device:
 *
 *  * **dismissing** removes exactly one card and nothing else: no other SauceNAO card, no IQDB card, no wishlist or
 *    catalogue record, and no cached picture. It is a view operation, so the same result may come back in a later
 *    search;
 *  * **patching** rewrites one card in place and returns `null` when the key is gone. That single rule is what stops
 *    a late picture (or any other asynchronous answer) from reinserting a card the user dismissed while it was
 *    travelling;
 *  * **`visible`** keeps the established order, so a pasted row stays at the top after a search publishes its own.
 */
data class CandidateLists(
    val pasted: List<ReverseCandidateUi> = emptyList(),
    val searched: List<ReverseCandidateUi> = emptyList(),
) {

    /** What the section shows, in display order. */
    val visible: List<ReverseCandidateUi> get() = pasted + searched

    val size: Int get() = pasted.size + searched.size

    fun find(key: String): ReverseCandidateUi? = visible.firstOrNull { it.key == key }

    /** @return the lists without [key], or null when no card has that key */
    fun dismiss(key: String): CandidateLists? {
        if (key.isBlank()) return null
        if (pasted.none { it.key == key } && searched.none { it.key == key }) return null
        return copy(
            pasted = pasted.filterNot { it.key == key },
            searched = searched.filterNot { it.key == key },
        )
    }

    /**
     * @return the lists with the card [key] rewritten, or null when it is no longer present
     *
     * Null is the whole point: a card that was dismissed while an image was loading is simply not here any more, and
     * the caller must not put it back.
     */
    fun patch(key: String, transform: (ReverseCandidateUi) -> ReverseCandidateUi): CandidateLists? {
        if (pasted.none { it.key == key } && searched.none { it.key == key }) return null
        return copy(
            pasted = pasted.map { if (it.key == key) transform(it) else it },
            searched = searched.map { if (it.key == key) transform(it) else it },
        )
    }

    /** The eligible candidates a bulk collection action may act on, in display order. */
    fun eligibleForCollection(predicate: (ReverseCandidateUi) -> Boolean): List<ReverseCandidateUi> =
        visible.filter(predicate)
}
