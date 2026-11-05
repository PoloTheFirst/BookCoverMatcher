package com.bookcovermatcher.core.online

/**
 * What the user has decided about one candidate.
 *
 * The first value is the important one: a candidate starts as [SUGGESTED], which is a **machine-generated
 * suggestion** and never an established identity. Only an explicit tap produces [CONFIRMED] or [REJECTED],
 * and the app keeps that difference visible everywhere.
 */
enum class CandidateDecision {
    /** The app found it; nobody has looked at it yet. */
    SUGGESTED,
    CONFIRMED,
    REJECTED,
}

/**
 * The durable record of one decision.
 *
 * [key] is the candidate's store identity ([OnlineAggregator.itemKey] for provider results,
 * `ReverseImageCandidate.key` or [ItemIdentity] for manual ones), so a decision survives a new search that
 * finds the same item again. [title]/[provider]/[url] are a snapshot for the UI, not a second identity.
 */
data class CandidateDecisionRecord(
    val key: String,
    val decision: CandidateDecision,
    val decidedAtMillis: Long,
    val title: String = "",
    val provider: String = "",
    val url: String? = null,
) {
    val isSuggestion: Boolean get() = decision == CandidateDecision.SUGGESTED
}

/**
 * The decision rules, as pure functions over an immutable map.
 *
 * Keeping them out of the store means the interesting behaviour — "a suggestion is not a confirmation",
 * "confirming twice does not duplicate", "a rejection can be revisited" — is unit-tested without a file or a
 * device.
 */
object CandidateReviews {

    /** One line the UI prints above every unconfirmed candidate. */
    const val SUGGESTION_LABEL = "Suggestion \u2014 not confirmed by you"

    fun stateOf(records: Map<String, CandidateDecisionRecord>, key: String): CandidateDecision =
        records[key]?.decision ?: CandidateDecision.SUGGESTED

    /**
     * Records a decision for [candidate].
     *
     * Idempotent: the same decision for the same key replaces the previous record instead of adding a second
     * one, so a double tap cannot create duplicate history.
     */
    fun apply(
        records: Map<String, CandidateDecisionRecord>,
        candidate: ProductSearchResult,
        decision: CandidateDecision,
        now: Long,
    ): Map<String, CandidateDecisionRecord> {
        val key = OnlineAggregator.itemKey(candidate)
        val updated = LinkedHashMap(records)
        updated[key] = CandidateDecisionRecord(
            key = key,
            decision = decision,
            decidedAtMillis = now,
            title = candidate.title,
            provider = candidate.source,
            url = candidate.detailUrl.takeIf { it.isNotBlank() },
        )
        return updated
    }

    /**
     * Records a decision for a candidate that has no [ProductSearchResult] (a reverse-image candidate or a
     * manual listing identified only by its own key).
     */
    fun applyKey(
        records: Map<String, CandidateDecisionRecord>,
        key: String,
        decision: CandidateDecision,
        now: Long,
        title: String = "",
        provider: String = "",
        url: String? = null,
    ): Map<String, CandidateDecisionRecord> {
        if (key.isBlank()) return records
        val updated = LinkedHashMap(records)
        updated[key] = CandidateDecisionRecord(
            key = key,
            decision = decision,
            decidedAtMillis = now,
            title = title,
            provider = provider,
            url = url?.takeIf { it.isNotBlank() },
        )
        return updated
    }

    /** Keys the user has explicitly confirmed. */
    fun confirmedKeys(records: Map<String, CandidateDecisionRecord>): Set<String> =
        records.values.filter { it.decision == CandidateDecision.CONFIRMED }.map { it.key }.toSet()

    /** Keys the user has explicitly rejected; the UI hides them but the record is kept. */
    fun rejectedKeys(records: Map<String, CandidateDecisionRecord>): Set<String> =
        records.values.filter { it.decision == CandidateDecision.REJECTED }.map { it.key }.toSet()

    /** Candidates still awaiting a decision, in input order — never includes a confirmed or rejected one. */
    fun undecided(
        records: Map<String, CandidateDecisionRecord>,
        candidates: List<ProductSearchResult>,
    ): List<ProductSearchResult> = candidates.filter { stateOf(records, OnlineAggregator.itemKey(it)) == CandidateDecision.SUGGESTED }
}
