package com.bookcovermatcher.core.online

/** What one dimension of the evidence says. [UNKNOWN] is never a negative: it means "not available". */
enum class EvidenceState { MATCH, PARTIAL, MISMATCH, UNKNOWN }

/** How a candidate should be presented. Deliberately not a probability. */
enum class MatchVerdict {
    /** Strong, corroborated evidence — or an exact product identifier. */
    LIKELY_MATCH,

    /** Some evidence agrees and none contradicts it outright; the user should look before confirming. */
    POSSIBLE_MATCH,

    /** The available evidence contradicts a match. */
    NO_MATCH,

    /** Nothing comparable: the provider sent too little metadata. */
    INSUFFICIENT_EVIDENCE,
}

/**
 * One piece of evidence behind a match, kept separate from the score so the UI can explain the result
 * instead of showing an opaque number.
 *
 * [similarity] is the measured 0..1 agreement for a comparable dimension (null for an identifier, which is
 * equal or not) and [weight] is the dimension's weight **when it is available** — a dimension reported as
 * [EvidenceState.UNKNOWN] contributes nothing, which is how a provider without a circle field cannot drag a
 * candidate down.
 */
data class Evidence(
    val dimension: String,
    val state: EvidenceState,
    val detail: String,
    val weight: Double,
    val similarity: Double? = null,
)

/** The local side of a comparison: one record of the workbook the app has open. */
data class LocalProduct(
    val key: String,
    val title: String,
    val circle: String = "",
    val author: String = "",
    val identifier: String = "",
)

/** One candidate↔record comparison: the aggregated score, the verdict and the evidence behind it. */
data class ProductMatch(
    val localKey: String,
    /** 0–100, computed only from available dimensions. Not a calibrated probability. */
    val score: Int,
    val verdict: MatchVerdict,
    val evidence: List<Evidence>,
) {
    val available: List<Evidence> get() = evidence.filter { it.state != EvidenceState.UNKNOWN }

    /** One line per available dimension, exactly what the comparison measured. */
    fun explanation(): List<String> = available.map { "${it.dimension}: ${it.detail}" }
}

/**
 * Matches an online candidate against the workbook's local records, one dimension at a time.
 *
 * Why this exists: the app can already rank local covers by image embedding, but an online result is
 * metadata first — a provider knows a title, often a circle or an author, sometimes a product code, and only
 * sometimes a usable cover. Comparing those separately and *showing* them is what makes a match reviewable;
 * one opaque similarity number is not.
 *
 * Rules encoded here:
 *
 *  * **Missing evidence is not negative.** A dimension either side cannot provide is
 *    [EvidenceState.UNKNOWN], its weight is dropped and the rest renormalized.
 *  * **An exact product identifier is decisive.** Two listings that share `RJ01234567` are the same product
 *    whatever the titles say.
 *  * **A shared title is not identity.** Title-only agreement tops out at a possible match, and a
 *    contradicting identifier or circle produces NO_MATCH regardless of title similarity.
 *  * **No false precision.** [ProductMatch.score] is an integer from documented weights; the thresholds below
 *    are engineering choices, not probabilities.
 *
 * Cover similarity is an optional input, because computing it belongs to the existing OpenCLIP pipeline.
 * Plain Kotlin/JVM; unit-tested.
 */
object CandidateMatcher {

    const val IDENTIFIER_WEIGHT = 0.45
    const val TITLE_WEIGHT = 0.30
    const val CIRCLE_WEIGHT = 0.15
    const val AUTHOR_WEIGHT = 0.10
    const val IMAGE_WEIGHT = 0.35

    /** Text similarity at or above this is presented as a match rather than a partial. */
    const val TEXT_MATCH = 0.92
    const val TEXT_PARTIAL = 0.60

    /** Score thresholds for the verdicts that are not decided by an exact identifier. */
    const val LIKELY_SCORE = 72
    const val POSSIBLE_SCORE = 50

    /** Two results closer than this are reported as ambiguous instead of picking one silently. */
    const val AMBIGUOUS_MARGIN = 8

    fun compare(
        candidate: ProductSearchResult,
        local: LocalProduct,
        imageSimilarity: Double? = null,
    ): ProductMatch {
        val evidence = listOf(
            compareCode(candidate, local),
            compareText("title", TITLE_WEIGHT, candidate.title, local.title, "title"),
            compareText("circle", CIRCLE_WEIGHT, candidate.circle.orEmpty(), local.circle, "circle"),
            compareText("author", AUTHOR_WEIGHT, candidate.authors.joinToString(", "), local.author, "author"),
            compareCover(imageSimilarity),
        )
        return aggregate(local.key, evidence)
    }

    /** Ranks [locals] for [candidate]: verdict first, then score, then the record key (stable). */
    fun rank(
        candidate: ProductSearchResult,
        locals: List<LocalProduct>,
        imageSimilarity: (LocalProduct) -> Double? = { null },
    ): List<ProductMatch> = locals
        .map { compare(candidate, it, imageSimilarity(it)) }
        .sortedWith(
            compareByDescending<ProductMatch> { it.verdict.quality() }
                .thenByDescending { it.score }
                .thenBy { it.localKey },
        )

    /**
     * Ranking order of the verdicts. The enum's own order is declaration order, not quality, so a
     * NO_MATCH must not outrank a POSSIBLE_MATCH just because it is declared later.
     */
    private fun MatchVerdict.quality(): Int = when (this) {
        MatchVerdict.LIKELY_MATCH -> 4
        MatchVerdict.POSSIBLE_MATCH -> 3
        MatchVerdict.INSUFFICIENT_EVIDENCE -> 2
        MatchVerdict.NO_MATCH -> 1
    }

    /**
     * The results a UI must show together because they cannot be told apart: every likely/possible match
     * within [AMBIGUOUS_MARGIN] of the best. One element means the choice is clear; more means the user picks.
     */
    fun ambiguous(ranked: List<ProductMatch>, margin: Int = AMBIGUOUS_MARGIN): List<ProductMatch> {
        val best = ranked.firstOrNull() ?: return emptyList()
        if (!best.isCandidate()) return emptyList()
        return ranked.filter { it.isCandidate() && best.score - it.score <= margin }
    }

    private fun ProductMatch.isCandidate(): Boolean =
        verdict == MatchVerdict.LIKELY_MATCH || verdict == MatchVerdict.POSSIBLE_MATCH

    /** Similarity in 0..1: exact, containment (weaker the longer the extra text), then bigram Dice. */
    fun similarity(a: String, b: String): Double {
        val x = OnlineQuery.normalizeText(a)
        val y = OnlineQuery.normalizeText(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        val (short, long) = if (x.length <= y.length) x to y else y to x
        if (short.length >= 3 && long.contains(short)) {
            return 0.68 + 0.22 * (short.length.toDouble() / long.length)
        }
        fun bigrams(s: String): Set<String> =
            (0 until s.length - 1).map { s.substring(it, it + 2) }.toSet()
        val bx = bigrams(x)
        val by = bigrams(y)
        if (bx.isEmpty() || by.isEmpty()) return 0.0
        return 2.0 * bx.count { it in by } / (bx.size + by.size)
    }

    // ---- dimensions ---------------------------------------------------------------------------

    private fun compareCode(candidate: ProductSearchResult, local: LocalProduct): Evidence {
        val code = OnlineQuery.normalizeCode(candidate.identifier.orEmpty())
        val localCode = OnlineQuery.normalizeCode(local.identifier)
        if (code.isBlank() || localCode.isBlank()) {
            return Evidence(
                dimension = "identifier",
                state = EvidenceState.UNKNOWN,
                detail = if (code.isBlank()) {
                    "${candidate.source} sent no product identifier"
                } else {
                    "this record has no product identifier"
                },
                weight = IDENTIFIER_WEIGHT,
            )
        }
        val same = code == localCode
        return Evidence(
            dimension = "identifier",
            state = if (same) EvidenceState.MATCH else EvidenceState.MISMATCH,
            detail = if (same) "$code is the same" else "$code is not $localCode",
            weight = IDENTIFIER_WEIGHT,
            similarity = if (same) 1.0 else 0.0,
        )
    }

    private fun compareText(
        dimension: String,
        weight: Double,
        candidateValue: String,
        localValue: String,
        label: String,
    ): Evidence {
        if (candidateValue.isBlank()) {
            return Evidence(
                dimension,
                EvidenceState.UNKNOWN,
                "$label not provided by the provider",
                weight,
            )
        }
        if (localValue.isBlank()) {
            return Evidence(dimension, EvidenceState.UNKNOWN, "this record has no $label", weight)
        }
        val s = similarity(candidateValue, localValue)
        val state = when {
            s >= TEXT_MATCH -> EvidenceState.MATCH
            s >= TEXT_PARTIAL -> EvidenceState.PARTIAL
            else -> EvidenceState.MISMATCH
        }
        val word = when (state) {
            EvidenceState.MATCH -> "matches"
            EvidenceState.PARTIAL -> "partly matches"
            EvidenceState.MISMATCH -> "differs"
            EvidenceState.UNKNOWN -> "unknown"
        }
        return Evidence(
            dimension = dimension,
            state = state,
            detail = "$word (${(s * 100).toInt()}%): \u201C$candidateValue\u201D vs \u201C$localValue\u201D",
            weight = weight,
            similarity = s,
        )
    }

    private fun compareCover(imageSimilarity: Double?): Evidence {
        if (imageSimilarity == null) {
            return Evidence(
                dimension = "cover",
                state = EvidenceState.UNKNOWN,
                detail = "no local cover to compare against",
                weight = IMAGE_WEIGHT,
            )
        }
        val clamped = imageSimilarity.coerceIn(0.0, 1.0)
        val state = when {
            clamped >= 0.85 -> EvidenceState.MATCH
            clamped >= 0.60 -> EvidenceState.PARTIAL
            else -> EvidenceState.MISMATCH
        }
        return Evidence(
            dimension = "cover",
            state = state,
            detail = "cover similarity ${(clamped * 100).toInt()}%",
            weight = IMAGE_WEIGHT,
            similarity = clamped,
        )
    }

    /**
     * Turns evidence into one score and a verdict.
     *
     * Only available dimensions count: `score = 100 · Σ(weight · component) / Σ(weight)`, the component being
     * 1.0 for an identifier match, 0.0 for an identifier mismatch, and the measured similarity for the rest.
     */
    private fun aggregate(localKey: String, evidence: List<Evidence>): ProductMatch {
        val available = evidence.filter { it.state != EvidenceState.UNKNOWN }
        if (available.isEmpty()) {
            return ProductMatch(localKey, 0, MatchVerdict.INSUFFICIENT_EVIDENCE, evidence)
        }
        var weighted = 0.0
        var total = 0.0
        for (item in available) {
            val component = when (item.dimension) {
                "identifier" -> if (item.state == EvidenceState.MATCH) 1.0 else 0.0
                else -> item.similarity ?: 0.0
            }
            weighted += item.weight * component
            total += item.weight
        }
        val score = if (total == 0.0) 0 else ((weighted / total) * 100).toInt().coerceIn(0, 100)

        val identifier = evidence.firstOrNull { it.dimension == "identifier" }
        val title = evidence.firstOrNull { it.dimension == "title" }
        val contradicted = evidence.any { it.state == EvidenceState.MISMATCH }
        // A title that agrees is not identity: something else has to corroborate it before the app calls
        // the match likely.
        val corroborated = evidence.any {
            it.dimension != "title" && it.dimension != "identifier" && it.state == EvidenceState.MATCH
        }

        val verdict = when {
            identifier?.state == EvidenceState.MATCH -> MatchVerdict.LIKELY_MATCH
            // A contradicting dimension outranks a similar title: two different circles with the same
            // title are two products.
            contradicted -> MatchVerdict.NO_MATCH
            score >= LIKELY_SCORE && title?.state == EvidenceState.MATCH && corroborated ->
                MatchVerdict.LIKELY_MATCH
            score >= POSSIBLE_SCORE -> MatchVerdict.POSSIBLE_MATCH
            else -> MatchVerdict.NO_MATCH
        }
        return ProductMatch(localKey, score, verdict, evidence)
    }
}
