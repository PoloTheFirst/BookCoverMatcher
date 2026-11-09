// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.author

import java.net.URI

/**
 * Which URLs from the author's README may be fetched, and what they are translated to (v2.6.0 amendment).
 *
 * The README contains three kinds of image reference that this app has to fetch safely:
 *
 *  * a Markdown image whose target is a **GitHub blob page** (`github.com/<owner>/<repo>/blob/<ref>/<path>`),
 *    which serves HTML — a browser shows the picture because GitHub's page embeds it, an image decoder cannot.
 *    The same file is served as bytes by `raw.githubusercontent.com/<owner>/<repo>/<ref>/<path>`, so that
 *    translation is applied here: same repository, same ref, same path, no guessing;
 *  * Shields.io badges (`img.shields.io`, SVG);
 *  * raw files from the profile repository (`raw.githubusercontent.com`, SVG).
 *
 * Everything else is refused. A host allowlist plus an HTTPS-only rule is what keeps this from becoming an
 * unrestricted web fetcher: a README could otherwise point the app at any host on the internet.
 */
object AuthorUrls {

    /** The only hosts an Author image may be fetched from, after any translation. */
    val ALLOWED_HOSTS: Set<String> = setOf("raw.githubusercontent.com", "img.shields.io")

    /** GitHub serves repository files as bytes from here. */
    const val RAW_GITHUB = "https://raw.githubusercontent.com"

    /** Host whose `blob` pages are translated to [RAW_GITHUB]. */
    private const val GITHUB = "github.com"

    /**
     * The URL to fetch for [url], or `null` when this app will not fetch it.
     *
     * Returns a normalised absolute HTTPS URL with no query and no fragment for raw files (a blob page's query
     * carries no meaning once the file itself is requested); Shields.io URLs keep their query, because it *is*
     * the badge definition.
     */
    fun resolve(url: String?): String? {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.userInfo != null) return null
        val host = uri.host?.lowercase() ?: return null
        if (uri.port != -1 && uri.port != 443) return null

        if (host == GITHUB) return fromBlobPage(uri)
        if (host !in ALLOWED_HOSTS) return null
        return trimmed
    }

    /** True when [url] is already in a form this app fetches. */
    fun isAllowed(url: String?): Boolean = resolve(url) != null

    /**
     * `https://github.com/o/r/blob/main/assets/x.png` → `https://raw.githubusercontent.com/o/r/main/assets/x.png`.
     *
     * Only a four-segment `blob` path is translated; anything else on github.com (issues, releases, gists) is
     * refused rather than guessed at.
     */
    private fun fromBlobPage(uri: URI): String? {
        val path = uri.path.orEmpty().trim('/')
        val parts = path.split('/')
        if (parts.size < 5) return null
        if (!parts[2].equals("blob", ignoreCase = true)) return null
        val owner = parts[0]
        val repo = parts[1]
        val ref = parts[3]
        val rest = parts.drop(4)
        if (rest.isEmpty()) return null
        // Every segment is checked, not just owner/repo/ref: `..` in the file path would climb out of the repository.
        if (!parts.filterIndexed { index, _ -> index != 2 }.all { isSafeSegment(it) }) return null
        return "$RAW_GITHUB/$owner/$repo/$ref/${rest.joinToString("/")}"
    }

    /** A path segment that is safe to interpolate into a URL: no traversal, no encoding tricks. */
    private val SEGMENT = Regex("^[A-Za-z0-9._-]+$")

    /** [SEGMENT] and also not `.` or `..`, which match the pattern but would climb out of the repository. */
    private fun isSafeSegment(segment: String): Boolean =
        segment != "." && segment != ".." && SEGMENT.matches(segment)
}
