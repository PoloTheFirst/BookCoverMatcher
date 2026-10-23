package com.bookcovermatcher.core.online

import java.net.URI
import java.net.URLEncoder

/**
 * Builds the browser links for every platform, outside Compose and outside the click handler so the
 * rules can be unit-tested.
 *
 * Every link is built from the platform's own template and the user's own query, then **validated**:
 * `https`, the platform's host (or a subdomain of it), no credentials, no unusual port. A link that
 * fails validation is not opened — the caller reports it instead of sending the user somewhere
 * arbitrary.
 *
 * Encoding is UTF-8 and uses `%20` for spaces rather than `+`, because these templates put the query
 * in a path segment (DLsite) as well as in query strings; `%20` is valid in both.
 */
object ProviderLinks {

    /** Thrown when a generated link is not a safe destination for [ProviderSpec]. */
    class UnsafeLinkException(message: String) : IllegalArgumentException(message)

    /**
     * The platform's search page for [query], or its landing page when the query is blank.
     *
     * @throws UnsafeLinkException when the platform has no verified page in this build, or when the
     * result is not an HTTPS URL on the platform's own host.
     */
    fun searchUrl(spec: ProviderSpec, query: String): String {
        val template = spec.searchTemplate
        val landing = spec.landingUrl
        if (template.isNullOrBlank() || landing.isNullOrBlank()) {
            throw UnsafeLinkException("${spec.name} has no verified public search page in this build.")
        }
        val trimmed = query.trim()
        val raw = if (trimmed.isEmpty()) landing else template.replace(Q, encode(trimmed))
        return validate(raw, spec)
    }

    /** Percent-encodes [query] as UTF-8, with `%20` instead of `+`. */
    fun encode(query: String): String =
        URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")

    /**
     * @return [url] unchanged when it is a safe destination for [spec].
     * @throws UnsafeLinkException otherwise.
     */
    fun validate(url: String, spec: ProviderSpec): String {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            throw UnsafeLinkException("'$url' is not a valid link for ${spec.name}.")
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            throw UnsafeLinkException("${spec.name} links must use https.")
        }
        val host = uri.host?.lowercase()?.trimEnd('.')
            ?: throw UnsafeLinkException("${spec.name} link has no host.")
        val expected = spec.host.lowercase().trimEnd('.')
        if (host != expected && !host.endsWith(".$expected")) {
            throw UnsafeLinkException("${spec.name} links must stay on $expected (was $host).")
        }
        if (uri.userInfo != null) {
            throw UnsafeLinkException("${spec.name} link must not contain credentials.")
        }
        if (uri.port != -1 && uri.port != 443) {
            throw UnsafeLinkException("${spec.name} links must use the default https port.")
        }
        return url
    }

    private const val Q = "{q}"
}
