package com.bookcovermatcher.core.online

/** Why an online request could not be completed. Named so the UI can say something specific. */
enum class OnlineFailure {
    /** No route to the catalogue (airplane mode, DNS failure, Wi-Fi off). */
    OFFLINE,

    /** The catalogue did not answer in time. */
    TIMEOUT,

    /** HTTP 429 / 503: the catalogue is throttling us. */
    RATE_LIMITED,

    /** HTTP 5xx from the catalogue. */
    SERVER_ERROR,

    /** HTTP 200 that is not the JSON we expected, or a response we cannot read. */
    BAD_RESPONSE,

    /**
     * The site refused an automated client (HTTP 403 and friends, or a bot-protection page). The app
     * does not try to defeat that: it says so and offers the provider's own search page in the
     * browser instead.
     */
    BLOCKED,

    /** The feature is switched off or has no configured source. */
    NOT_CONFIGURED,

    /** A provider failed in a way that has no better category. */
    INTERNAL,
}

/**
 * The outcome of one product-search request: results, nothing, or a named failure.
 *
 * "No results" is deliberately not a failure — an empty catalogue hit is a normal, successful
 * answer and must not be shown as an error.
 */
sealed interface ProductSearchOutcome {
    class Found(
        val query: String,
        val results: List<ProductSearchResult>,
        /** Total items the catalogue knows about, when it reports it. */
        val totalAvailable: Int?,
    ) : ProductSearchOutcome

    /** The request worked and the catalogue has nothing for this query. */
    class Empty(val query: String) : ProductSearchOutcome

    class Failed(val failure: OnlineFailure, val message: String) : ProductSearchOutcome
}

/** A catalogue implementation failed for a reason that has a name. */
class ProductSourceException(
    val failure: OnlineFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The contract the online-search feature searches through.
 *
 * Kept synchronous on purpose, like [com.bookcovermatcher.core.search.CatalogBuilder]: the caller
 * decides which dispatcher to run it on and how to cancel it. Implementations live outside
 * `core` because they need a socket; the parsing they use lives here.
 */
interface ProductSearchDataSource {
    /** Shown in the UI so the user knows where the results come from. */
    val name: String

    /**
     * Free-text search. Must return [ProductSearchOutcome.Empty] when nothing matched and throw
     * [ProductSourceException] when the request itself failed.
     */
    fun search(query: String, limit: Int): ProductSearchOutcome
}
