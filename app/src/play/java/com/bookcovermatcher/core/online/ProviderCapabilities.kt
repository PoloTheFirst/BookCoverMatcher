// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.online

object ProviderCapabilities {
    val ALL: List<ProviderCapability> = emptyList()

    /** The capability of [providerId], or null when this build does not query that provider. */
    fun of(providerId: String): ProviderCapability? =
        ALL.firstOrNull { it.providerId == providerId.trim().lowercase() }

    /** True when [providerId] is a supplementary general-book source the user may switch off. */
    fun isSecondary(providerId: String): Boolean = of(providerId)?.secondary == true

    /** Every secondary (general-book) provider, in catalogue order. */
    fun secondaryProviders(): List<ProviderSpec> =
        ProviderCatalog.AUTOMATED.filter { isSecondary(it.id) }

    /** Every primary (doujin) provider, in catalogue order. */
    fun primaryProviders(): List<ProviderSpec> =
        ProviderCatalog.AUTOMATED.filter { !isSecondary(it.id) }

    /**
     * True when [providerId] can be given something from [query]. A provider is skipped — not failed —
     * when the query only uses fields it cannot search, which is why an author-only search leaves DLsite
     * out instead of returning its title-keyword noise.
     */
    fun canSearch(providerId: String, query: OnlineQuery): Boolean {
        val capability = of(providerId) ?: return false
        if (query.isBlank) return false
        return !query.isEmptyWithout(capability.ignoredFields(query))
    }

    /**
     * The providers of [selected] that can act on [query], in catalogue order.
     *
     * @param includeSecondary false when the user switched the general-book sources off: they are then not
     *        returned at all, so no request is issued and no result is merged. Switching them off must not
     *        look like "they were searched and found nothing".
     */
    fun eligible(
        selected: Collection<String>,
        query: OnlineQuery,
        includeSecondary: Boolean = true,
    ): List<ProviderSpec> = ProviderCatalog.AUTOMATED.filter { spec ->
        spec.id in selected &&
            canSearch(spec.id, query) &&
            (includeSecondary || !isSecondary(spec.id))
    }

    /**
     * The providers of [selected] that must be reported as "cannot search this" (or "switched off") for
     * [query], so the panel can explain every provider it did not use instead of silently dropping it.
     */
    fun skipped(
        selected: Collection<String>,
        query: OnlineQuery,
        includeSecondary: Boolean = true,
    ): List<ProviderSpec> = ProviderCatalog.AUTOMATED.filter { spec ->
        spec.id in selected &&
            (!canSearch(spec.id, query) || (!includeSecondary && isSecondary(spec.id)))
    }
}
