// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.online.reverse

/**
 * What one reverse-image provider can be said to be doing.
 *
 * The list exists because "no results" is only **one** of many honest answers, and the app must never
 * confuse them:
 *
 *  * [NOT_CONFIGURED] — the provider needs a user-supplied credential and none is stored.
 *  * [READY] — configured and idle; nothing has been asked yet.
 *  * [SEARCHING] — a request is outstanding.
 *  * [SUCCESS] — a genuine provider answer arrived and carried at least one result.
 *  * [NO_RESULTS] — a genuine provider answer arrived and carried nothing. **Not** a failure.
 *  * [TIMEOUT] — the provider did not answer inside its own, provider-specific budget. Never an empty result.
 *  * [RATE_LIMITED] — HTTP 429/503 or the provider's own throttling answer.
 *  * [QUOTA_EXCEEDED] — the provider says the daily/short-window allowance is used up.
 *  * [UNAVAILABLE] — the service could not be reached at all.
 *  * [BLOCKED] — the site refused an automated client. The app does not try to defeat that.
 *  * [CANCELLED] — the user (or a newer query) stopped it before an answer arrived.
 *  * [ERROR] — the provider answered with something that is not a usable result document.
 *  * [MANUAL_ONLY] — this build deliberately does not automate the provider; the browser handoff is used.
 *
 * Plain Kotlin/JVM (no `android.*`), so every state transition is unit-testable.
 */
enum class ReverseImageState {
    NOT_CONFIGURED,
    READY,
    SEARCHING,
    SUCCESS,
    NO_RESULTS,
    TIMEOUT,
    RATE_LIMITED,
    QUOTA_EXCEEDED,
    UNAVAILABLE,
    BLOCKED,
    CANCELLED,
    ERROR,
    MANUAL_ONLY,
    ;

    /** True only when the provider really answered. Everything else must not be reported as "nothing found". */
    val answered: Boolean get() = this == SUCCESS || this == NO_RESULTS

    /** True when the provider was never asked, so no claim about results is allowed at all. */
    val searchedNothing: Boolean
        get() = this == NOT_CONFIGURED || this == MANUAL_ONLY || this == BLOCKED ||
            this == CANCELLED || this == UNAVAILABLE || this == READY

    /** True when the outcome is a provider-side problem the user can act on (wait, reconfigure, browse). */
    val actionable: Boolean
        get() = this == NOT_CONFIGURED || this == TIMEOUT || this == RATE_LIMITED ||
            this == QUOTA_EXCEEDED || this == BLOCKED || this == MANUAL_ONLY
}

/** How a provider is integrated. Kept explicit so the UI can never call a handoff "automated search". */
enum class ReverseImageAutomation {
    /** The app submits the image itself through the provider's documented interface. */
    AUTOMATED,

    /**
     * The app does **not** submit the image. It opens the provider's own search page and lets the user
     * perform the search, then accepts the material the user brings back.
     */
    MANUAL_ONLY,
}

/**
 * The scale a provider's own similarity value is expressed on.
 *
 * Both providers in this release report 0–100 percentages, but the value is a **provider-specific
 * score**, not a calibrated probability, and scores from different providers are never averaged.
 */
enum class SimilarityScale { PERCENT_0_100 }

/**
 * One image to submit for reverse-image discovery.
 *
 * [bytes] are always the image the app is holding (a captured cover, a cached cover or a picked file):
 * the documented SauceNAO interface accepts an upload, and an upload cannot go stale the way a URL can.
 */
class ReverseImageRequest(
    val bytes: ByteArray,
    val fileName: String = "cover.jpg",
    val mimeType: String = "image/jpeg",
    /** Only used by a provider whose documented interface accepts a URL instead of an upload. */
    val imageUrl: String? = null,
    val maxResults: Int = DEFAULT_MAX_RESULTS,
    /** 0–100 floor in the provider's own scale, when the provider documents one. */
    val minSimilarity: Int? = null,
) {
    val sizeBytes: Int get() = bytes.size

    companion object {
        const val DEFAULT_MAX_RESULTS = 6
        const val MAX_RESULTS = 30

        /** 8 MB: IQDB's own documented upload ceiling, kept as the app-wide upper bound. */
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

        /** Refuses an empty or absurd upload before a socket is opened. */
        fun isUsableSize(sizeBytes: Int): Boolean = sizeBytes in 1..MAX_IMAGE_BYTES
    }
}

/**
 * One normalised reverse-image result.
 *
 * Everything here comes from the provider's own answer: the app never invents a title, a circle or a
 * similarity. [providerSimilarity] is reproduced exactly as reported and stays attached to [providerId],
 * because two providers' numbers are not comparable.
 */
class ReverseImageHit(
    val providerId: String,
    val providerName: String,
    /** The provider's own identifier for this row, when it sends one. */
    val providerResultId: String? = null,
    /** The page the provider says the image comes from. Preserved verbatim. */
    val sourceUrl: String? = null,
    /** The provider's own thumbnail/scan URL, when it sends one. */
    val imageUrl: String? = null,
    val title: String = "",
    val circle: String = "",
    val author: String = "",
    /** A product identifier (e.g. an `RJ…` code) extracted from the provider's own fields. */
    val productId: String = "",
    /** The provider's score, on [similarityScale]. Never a probability, never re-scaled. */
    val providerSimilarity: Double? = null,
    val similarityScale: SimilarityScale = SimilarityScale.PERCENT_0_100,
    /** The provider's index/database name (`Index #5: Danbooru`), when it sends one. */
    val indexName: String? = null,
    /** Every other scalar the provider sent, as text. Never interpreted, only shown. */
    val metadata: Map<String, String> = emptyMap(),
    /** When the search that produced this row ran. */
    val searchedAtMillis: Long = 0L,
) {
    val hasIdentityEvidence: Boolean
        get() = productId.isNotBlank() || !sourceUrl.isNullOrBlank()
}

/** The provider's own remaining-allowance report, when it sends one. */
data class ReverseImageQuota(
    val shortLimit: Int? = null,
    val longLimit: Int? = null,
    val shortRemaining: Int? = null,
    val longRemaining: Int? = null,
) {
    val exhausted: Boolean
        get() = (shortRemaining != null && shortRemaining <= 0) || (longRemaining != null && longRemaining <= 0)

    /** One line for the UI, e.g. `3 of 4 short-window searches left`. Nothing invented when absent. */
    fun describe(): String? {
        val remaining = longRemaining ?: shortRemaining ?: return null
        val limit = longLimit ?: shortLimit
        return if (limit != null) "$remaining of $limit left" else "$remaining left"
    }
}

/** What one provider answered. [Failed] carries a *state*, so a timeout is never an empty result. */
sealed interface ReverseImageOutcome {
    class Found(val hits: List<ReverseImageHit>, val quota: ReverseImageQuota? = null) : ReverseImageOutcome

    class NoResults(val quota: ReverseImageQuota? = null) : ReverseImageOutcome

    class Failed(
        val state: ReverseImageState,
        val message: String,
        val quota: ReverseImageQuota? = null,
    ) : ReverseImageOutcome
}

/**
 * One reverse-image provider.
 *
 * Deliberately synchronous, like [com.bookcovermatcher.core.online.ProductSearchDataSource]: the caller
 * picks the dispatcher and owns cancellation. Implementations live outside `core` when they need a
 * socket; everything they parse lives here.
 */
interface ReverseImageSource {
    /** Short stable id (`saucenao`, `iqdb`), used for provenance. */
    val id: String

    val displayName: String

    /** 1 = tried first. SauceNAO is 1, IQDB is 2. */
    val priority: Int

    val automation: ReverseImageAutomation

    /**
     * This provider's own request budget. A slow provider gets a longer budget than a fast one; a
     * provider that exceeds it is reported as [ReverseImageState.TIMEOUT], never as "no results".
     */
    val timeoutMs: Long

    /** The provider's own search page, for the browser handoff. Always an absolute `https` URL. */
    val searchPageUrl: String?

    /** What the provider can do *right now* (configured, manual-only, ready), without any request. */
    fun readiness(): ReverseImageState

    /**
     * Submits [request]. Must return [ReverseImageOutcome.Failed] rather than throwing; the runner still
     * guards against a throwing implementation, because a provider adapter is a network boundary.
     */
    fun search(request: ReverseImageRequest): ReverseImageOutcome
}

/** What one provider did in one search, including the fact that it did nothing. */
data class ReverseImageRun(
    val providerId: String,
    val displayName: String,
    val priority: Int,
    val state: ReverseImageState,
    val hits: List<ReverseImageHit> = emptyList(),
    val message: String? = null,
    val elapsedMs: Long = 0L,
    val quota: ReverseImageQuota? = null,
) {
    /** Only a genuine answer may contribute results. */
    val contributed: Boolean get() = state == ReverseImageState.SUCCESS && hits.isNotEmpty()
}

/**
 * The two providers of this release plus the order they must be tried in, kept in one place so no
 * caller can re-order them by accident.
 */
object ReverseImageProviders {
    const val SAUCE_NAO_ID = "saucenao"
    const val IQDB_ID = "iqdb"

    /** 1 — tried first, always. */
    const val SAUCE_NAO_PRIORITY = 1

    /** 2 — tried second, even when SauceNAO already answered. */
    const val IQDB_PRIORITY = 2

    val SAUCE_NAO_SEARCH_PAGE = "https://saucenao.com/"
    val IQDB_SEARCH_PAGE = "https://iqdb.org/"

    /** Provider priority order. SauceNAO is first by specification, not by list-order luck. */
    val ORDER: List<String> = listOf(SAUCE_NAO_ID, IQDB_ID)

    fun priorityOf(providerId: String): Int = when (providerId.trim().lowercase()) {
        SAUCE_NAO_ID -> SAUCE_NAO_PRIORITY
        IQDB_ID -> IQDB_PRIORITY
        else -> Int.MAX_VALUE
    }
}
