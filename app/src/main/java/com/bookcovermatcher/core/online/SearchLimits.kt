package com.bookcovermatcher.core.online

/**
 * How many online results the user may ask for.
 *
 * This is **online only**. The offline workbook comparison keeps its own fixed limit
 * ([com.bookcovermatcher.core.Config.TOP_MATCHES] = 5) and is deliberately not configurable here:
 * it is the comparison result, not a result list to browse.
 *
 * A provider may return fewer than requested — it has its own page size and no pagination is
 * performed — so the value is a *maximum*, never a promise. Each adapter caps it further through
 * [forProvider] using the provider's own [ProductSearchDataSource.maxResults].
 *
 * Plain Kotlin/JVM so the validation is unit-tested.
 */
object SearchLimits {

    /** The choices the panel offers, ascending. */
    val OPTIONS: List<Int> = listOf(5, 10, 20, 30, 50)

    /** Backward-compatible default: v2.2.1 always asked for five. */
    const val DEFAULT: Int = 5

    /** Largest value the app will ever ask for. */
    val MAX: Int = OPTIONS.last()

    /**
     * A value that is safe to use and to persist. Anything that is not one of [OPTIONS] — a negative
     * number, a stale value from a future build, a corrupted preference — becomes [DEFAULT].
     */
    fun sanitize(value: Int): Int = if (value in OPTIONS) value else DEFAULT

    /** What one provider may be asked for: the user's choice, capped by that provider's own limit. */
    fun forProvider(requested: Int, providerMax: Int): Int =
        minOf(sanitize(requested), providerMax.coerceAtLeast(1))

    /** The choices worth showing for [providerMax] (used by the docs/tests, not to hide options). */
    fun offeredFor(providerMax: Int): List<Int> = OPTIONS.filter { it <= providerMax }
}
