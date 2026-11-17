// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.online

/** How a provider can honestly be used from the app. */
enum class ProviderAccess {
    /** The app queries it and shows its results. */
    AUTOMATED,

    /**
     * The app does **not** query it. The provider refuses automated clients, so the app offers its
     * own search page in the user's browser instead. It is never counted as a searched provider.
     */
    BROWSER_ONLY,
}

/**
 * How a platform expects a space in its own search URL. It is a per-platform fact, measured, not a
 * style choice:
 *
 *  * [PERCENT_20] — `%20`, correct for an ordinary query string (most platforms);
 *  * [PLUS] — the platform's own form encoding, where a space is `+`. **DLsite** needs this: its
 *    `/keyword/<query>` path answers HTTP 403 to `%20` and 200 with results to `+`.
 */
enum class QueryForm { PERCENT_20, PLUS }

/**
 * One provider, as the UI and the controller need to describe it: what it is called, which host it
 * lives on, whether the app may query it, and how to open its own search page.
 *
 * [searchTemplate]/[landingUrl] are null for a provider with **no verified public search page** in
 * this build (DoujinMori): the card still exists so the provider is not silently missing, but it
 * offers no link and [ProviderLinks] refuses to build one.
 *
 * [selectable] is derived, never stored: only a provider the app can actually query may be put into
 * the selected set, so a browser-only card can never be mistaken for an in-app search.
 */
class ProviderSpec(
    val id: String,
    val name: String,
    val host: String,
    val access: ProviderAccess,
    /** One sentence, shown under the provider's name. Never a marketing claim. */
    val note: String,
    /** `{q}` is replaced with the encoded query (see [queryForm]). Null = no verified search page. */
    val searchTemplate: String?,
    /** Opened instead when the query is blank. Null = no verified landing page. */
    val landingUrl: String?,
    /** How this platform encodes a space in its own search URL. */
    val queryForm: QueryForm = QueryForm.PERCENT_20,
) {
    /** Only automated providers can be selected for an in-app search. */
    val selectable: Boolean get() = access == ProviderAccess.AUTOMATED

    /** True when this build has a verified public page to open for this provider. */
    val hasLink: Boolean get() = !searchTemplate.isNullOrBlank() && !landingUrl.isNullOrBlank()

    /** The provider's own search page for [query] (its landing page for a blank query). */
    fun searchUrl(query: String): String = ProviderLinks.searchUrl(this, query)
}
