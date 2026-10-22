package com.bookcovermatcher.core.wishlist

import java.net.URI

/**
 * Item identity — what makes two saved things "the same thing".
 *
 * Rules, in order (a provider identifier always wins):
 *  1. A provider key such as `ol:OL27482W`. It is stable across editions, locales and tracking
 *     parameters, so it is the only key that may merge two different URLs.
 *  2. Otherwise a normalised URL: `https` forced, host lowercased, default port removed, fragment
 *     removed, one trailing `/` removed, and **only** well-known tracking parameters stripped.
 *
 * Deliberately conservative: a parameter that can change the destination (`?pid=`, `?number=`,
 * `?query=`, `?age=`, …) is always kept, so two genuinely different products never collapse into
 * one. Two editions of the same book with different titles/URLs are **not** merged — the app cannot
 * know they are the same edition, and silently merging would hide a result.
 *
 * Workbook matches have no URL: their key is `wb:<cell>`, because a row of a workbook has no other
 * identifier in this app. Two different workbooks that both use `C97` therefore share a key; that is
 * a documented limitation, not an accident.
 */
object ItemIdentity {

    /** Query parameters that only track a click. Everything else is preserved. */
    private val TRACKING_PARAMS = setOf(
        "fbclid", "gclid", "gclsrc", "dclid", "msclkid", "mc_cid", "mc_eid", "igshid",
        "yclid", "_ga", "_gl", "ref_src", "ref_url", "spm",
    )

    /**
     * The identity of a draft or a result. Never blank: the final fallback is the title, lowercased,
     * so a store entry is always addressable.
     */
    fun fingerprintFor(
        providerKey: String? = null,
        url: String? = null,
        workbookCell: String? = null,
        title: String? = null,
    ): String {
        val provider = providerKey?.trim()?.takeIf { it.isNotEmpty() }
        if (provider != null) return provider.lowercase()

        normaliseUrl(url)?.let { return "url:$it" }

        val cell = workbookCell?.trim()?.takeIf { it.isNotEmpty() }
        if (cell != null) return workbookFingerprint(cell)

        return "title:" + (title?.trim()?.lowercase().orEmpty())
    }

    fun workbookFingerprint(cell: String): String = "wb:" + cell.trim().uppercase()

    /**
     * A canonical `https` form of [raw], or null when it is not an absolute URL with a host.
     *
     * Returns the *identity*, never the URL to open: the caller keeps the original string so the user
     * opens exactly the page the provider sent (or typed), query order and all.
     */
    fun normaliseUrl(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val uri = try {
            URI(text)
        } catch (e: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return null
        val port = uri.port.takeIf { it > 0 && !((scheme == "http" && it == 80) || (scheme == "https" && it == 443)) }
        val path = (uri.rawPath ?: "").trimEnd('/').ifEmpty { "" }
        val query = (uri.rawQuery ?: "")
            .split('&')
            .filter { it.isNotEmpty() && !isTracking(it.substringBefore('=')) }
            .joinToString("&")
        return buildString {
            append("https://").append(host)
            if (port != null) append(':').append(port)
            append(path)
            if (query.isNotEmpty()) append('?').append(query)
        }
    }

    /** `www.openlibrary.org` for a link, or null when the value is not a URL. */
    fun domainOf(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val uri = try {
            URI(text)
        } catch (e: Exception) {
            return null
        }
        val host = uri.host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return null
        return host
    }

    private fun isTracking(param: String): Boolean {
        val key = param.trim().lowercase()
        return key.startsWith("utm_") || key in TRACKING_PARAMS
    }
}
