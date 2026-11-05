// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.online.reverse

import com.bookcovermatcher.core.online.OnlineQuery
import com.bookcovermatcher.core.wishlist.ItemIdentity

/**
 * One provider's reference to a candidate, kept intact so the UI can say *who* found it and *with what
 * number*.
 *
 * [similarity] is always the number that provider reported, on that provider's own scale. Two references
 * from two providers are never combined into one score.
 */
class ReverseImageReference(
    val providerId: String,
    val providerName: String,
    val providerResultId: String? = null,
    val similarity: Double? = null,
    val similarityScale: SimilarityScale = SimilarityScale.PERCENT_0_100,
    val sourceUrl: String? = null,
    val imageUrl: String? = null,
    val indexName: String? = null,
    val searchedAtMillis: Long = 0L,
) {
    /** 1 for SauceNAO, 2 for IQDB: used for ordering, never for scoring. */
    val priority: Int get() = ReverseImageProviders.priorityOf(providerId)

    /** `SauceNAO 95.1%` — attribution, not a probability. */
    fun label(): String {
        val value = similarity?.let { if (it == Math.rint(it)) it.toLong().toString() else it.toString() }
        return if (value != null) "$providerName $value%" else providerName
    }
}

/**
 * One candidate discovered by reverse-image discovery, with every provider's reference preserved.
 *
 * The rules this class encodes:
 *
 *  * **Both providers are kept.** A candidate found by SauceNAO and IQDB carries two references, both
 *    similarity values and both URLs.
 *  * **No combined probability.** There is no "score" field, because averaging two provider-specific
 *    numbers would invent a certainty the data does not support. Two sources agreeing is *stronger
 *    provenance*, not a higher probability.
 *  * **Identity is not established here.** A candidate is a suggestion; only an explicit user
 *    confirmation establishes a product identity.
 */
class ReverseImageCandidate(
    /** Stable identity of the candidate, `id:RJ01234567` or `url:https://…`. */
    val key: String,
    val title: String,
    val circle: String,
    val author: String,
    val productId: String,
    val references: List<ReverseImageReference>,
) {
    /** Provider ids in priority order (SauceNAO before IQDB). */
    val providerIds: List<String> = references.map { it.providerId }.distinct()
        .sortedBy { ReverseImageProviders.priorityOf(it) }

    /** True when more than one provider reported this candidate. Evidence, not identity. */
    val foundByMultipleProviders: Boolean get() = providerIds.size > 1

    /** The best reference: the primary provider's first, that provider's highest similarity. */
    val primaryReference: ReverseImageReference = references.minWithOrNull(
        compareBy<ReverseImageReference> { it.priority }.thenByDescending { it.similarity ?: -1.0 },
    ) ?: throw IllegalArgumentException("a candidate needs at least one provider reference")

    val sourceUrl: String? get() = primaryReference.sourceUrl
    val imageUrl: String? get() = references.firstNotNullOfOrNull { it.imageUrl }

    /** That provider's own number, unmodified. */
    fun similarityOf(providerId: String): Double? =
        references.firstOrNull { it.providerId == providerId }?.similarity

    /** `SauceNAO 95.1% · IQDB 92%`, in priority order. Never summed or averaged. */
    fun evidenceLine(): String = references
        .sortedBy { it.priority }
        .joinToString(" \u00B7 ") { it.label() }

    /** True when the candidate carries enough to be identified at all. */
    val identifiable: Boolean get() = productId.isNotBlank() || !sourceUrl.isNullOrBlank() || title.isNotBlank()
}

/**
 * Merges the providers' results into one reviewable list.
 *
 * Deduplication only happens on **evidence that two rows are the same item**: a shared product code, or a
 * shared normalised source URL. Two rows with similar titles are never merged — they may be two editions,
 * two uploads or two different products, and merging them would hide a result the user needed to see.
 */
object ReverseImageAggregator {

    /**
     * @param runs what each provider did, in the order it was tried. A run that did not genuinely answer
     *        contributes nothing, so a timeout can never look like an empty result.
     */
    fun merge(runs: List<ReverseImageRun>, limit: Int = 20, now: Long = 0L): List<ReverseImageCandidate> {
        if (limit <= 0) return emptyList()
        val groups = LinkedHashMap<String, MutableList<ReverseImageHit>>()
        for (run in runs) {
            if (!run.contributed) continue
            run.hits.forEachIndexed { index, hit ->
                val key = identityOf(hit) ?: uniqueKey(hit, index)
                groups.getOrPut(key) { ArrayList() }.add(hit)
            }
        }
        return groups.map { (key, hits) -> candidate(key, hits, now) }
            .sortedWith(
                compareBy<ReverseImageCandidate> { it.primaryReference.priority }
                    .thenByDescending { it.primaryReference.similarity ?: -1.0 }
                    .thenBy { it.key },
            )
            .take(limit)
    }

    /** The identity a row can prove: a product code first, then a normalised source URL. Else null. */
    fun identityOf(hit: ReverseImageHit): String? {
        if (hit.productId.isNotBlank()) return "id:" + OnlineQuery.normalizeCode(hit.productId)
        val url = ItemIdentity.normaliseUrl(hit.sourceUrl)
        return if (url != null) "url:$url" else null
    }

    /** A row with no identity keeps its own slot: it must not be merged with anything by accident. */
    private fun uniqueKey(hit: ReverseImageHit, index: Int): String =
        "row:${hit.providerId}:${hit.providerResultId ?: index}"

    private fun candidate(key: String, hits: List<ReverseImageHit>, now: Long): ReverseImageCandidate {
        val references = hits.map { hit ->
            ReverseImageReference(
                providerId = hit.providerId,
                providerName = hit.providerName,
                providerResultId = hit.providerResultId,
                similarity = hit.providerSimilarity,
                similarityScale = hit.similarityScale,
                sourceUrl = hit.sourceUrl,
                imageUrl = hit.imageUrl,
                indexName = hit.indexName,
                searchedAtMillis = if (hit.searchedAtMillis != 0L) hit.searchedAtMillis else now,
            )
        }
        return ReverseImageCandidate(
            key = key,
            title = hits.firstNotNullOfOrNull { h -> h.title.takeIf { it.isNotBlank() } }.orEmpty(),
            circle = hits.firstNotNullOfOrNull { h -> h.circle.takeIf { it.isNotBlank() } }.orEmpty(),
            author = hits.firstNotNullOfOrNull { h -> h.author.takeIf { it.isNotBlank() } }.orEmpty(),
            productId = hits.firstNotNullOfOrNull { h -> h.productId.takeIf { it.isNotBlank() } }.orEmpty(),
            references = references,
        )
    }
}
