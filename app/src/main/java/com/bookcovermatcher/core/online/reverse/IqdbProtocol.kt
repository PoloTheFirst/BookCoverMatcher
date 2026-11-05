// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.online.reverse

/**
 * IQDB's public search page, and exactly why this build does **not** automate it.
 *
 * Verified in this session against the live site (2026-11-05):
 *
 *  * `https://iqdb.org/` is an HTML form: one `File or Source URL` field, JPEG/PNG/GIF only, 8192 KB and
 *    7500×7500 limits. There is **no documented public API** — no endpoint, parameter set, versioning or
 *    response contract is published anywhere the project could find.
 *  * `https://iqdb.org/robots.txt` (verbatim, 2026-11-05) disallows `/multi-search`, `/db-search`,
 *    `/kc-search`, `/mi-search`, `/index` and `/?url=` for every user agent. Those are precisely the
 *    query routes of the service.
 *
 * So automated IQDB search is **not** implemented, and this file exists to say so in one place rather
 * than in a comment somewhere. What the app does instead is the sanctioned fallback: it opens IQDB's own
 * search page in the user's browser, and the user brings the material back (a listing URL, the metadata
 * they can see, optionally the cover image), which then goes through the same local candidate pipeline as
 * every other result.
 *
 * The parser below is real and tested, and it is used for **user-supplied** material only: a pasted
 * result line or a saved result page. It never runs against the live service.
 *
 * Plain Kotlin/JVM; unit-tested against sanitised fixtures.
 */
object IqdbProtocol {

    const val SEARCH_PAGE = "https://iqdb.org/"
    const val DISPLAY_NAME = "IQDB"

    /** False by design, and the reason is documented above. Never flipped by a build flag. */
    const val AUTOMATION_SUPPORTED = false

    /**
     * The paths IQDB's own `robots.txt` disallows. Kept as data so a future integration cannot quietly
     * start using one of them.
     */
    val DISALLOWED_BY_ROBOTS: List<String> = listOf(
        "/multi-search",
        "/db-search",
        "/kc-search",
        "/mi-search",
        "/index",
        "/?url=",
    )

    /** IQDB's own documented upload ceiling (8192 KB). */
    const val MAX_UPLOAD_BYTES = 8192 * 1024

    /** The file types IQDB's form accepts. */
    val SUPPORTED_MIME: Set<String> = setOf("image/jpeg", "image/png", "image/gif")

    /** One sentence the UI can show verbatim, so the limitation is never hidden. */
    fun manualOnlyReason(): String =
        "IQDB has no documented public API, and its robots.txt disallows its own search routes, so the " +
            "app does not query it. Open IQDB in your browser and bring the result back."

    /** True for a path this build must never request from IQDB. */
    fun isDisallowedPath(path: String): Boolean {
        val clean = path.trim()
        return DISALLOWED_BY_ROBOTS.any { disallowed ->
            if (disallowed.endsWith("=")) clean.contains(disallowed) else clean.startsWith(disallowed)
        }
    }
}

/**
 * Normalises material that came from IQDB, for the cases where the **user** is the transport.
 *
 * Two inputs are accepted, and both are user-supplied by definition:
 *  * [parsePage] — an IQDB result page the user saved or copied (its result tables are read);
 *  * [parsePasted] — lines the user pasted (a result URL, optionally with a `NN% similarity` figure).
 *
 * A row without a source URL is dropped: a similarity number with no page behind it is not evidence the
 * app can show or follow.
 */
object IqdbResultParser {

    private const val PROVIDER = ReverseImageProviders.IQDB_ID
    private const val NAME = IqdbProtocol.DISPLAY_NAME

    private val TABLE = Regex("(?is)<table[^>]*>(.*?)</table>")
    private val ANCHOR = Regex("(?i)href=\"((?:https?:)?//[^\"]+)\"")
    private val IMAGE = Regex("(?i)<img[^>]+src=\"((?:https?:)?//[^\"]+)\"")
    private val IMAGE_FILE = Regex("(?i)\\.(jpe?g|png|gif|webp)(\\?.*)?$")
    private val PERCENT = Regex("(?i)(\\d{1,3})\\s*%\\s*similarity")
    private val SERVICE = Regex(
        "(?i)>(danbooru|konachan|yande\\.re|gelbooru|sankaku(?:\\s+channel)?|e-?shuushuu|zerochan|anime-?pictures|3dbooru)<",
    )

    /** Reads the result tables of a saved IQDB page. Unreadable markup yields no rows, never a guess. */
    fun parsePage(html: String, searchedAtMillis: Long, limit: Int = 10): List<ReverseImageHit> {
        if (html.isBlank() || limit <= 0) return emptyList()
        val hits = ArrayList<ReverseImageHit>()
        for (table in TABLE.findAll(html)) {
            if (hits.size >= limit) break
            val block = table.groupValues[1]
            val anchors = ANCHOR.findAll(block)
                .map { absolute(it.groupValues[1]) }
                .filter { it.isNotBlank() && !it.contains("iqdb.org") }
                .toList()
            val image = IMAGE.findAll(block)
                .map { absolute(it.groupValues[1]) }
                .firstOrNull { it.isNotBlank() && !it.contains("iqdb.org") }
            // The thumbnail link comes first in IQDB's own markup; the page the image came from is the first
            // link that is not an image file.
            val source = anchors.firstOrNull { !looksLikeImage(it) } ?: anchors.firstOrNull() ?: continue
            val service = SERVICE.find(block)?.groupValues?.get(1)?.replaceFirstChar { it.uppercase() }.orEmpty()
            val similarityText = PERCENT.find(block)?.groupValues?.get(1)
            val similarity = similarityText?.toDoubleOrNull()
            val metadata = LinkedHashMap<String, String>()
            if (service.isNotBlank()) metadata["service"] = service
            if (similarityText != null) metadata["similarity"] = "$similarityText%"
            hits += ReverseImageHit(
                providerId = PROVIDER,
                providerName = NAME,
                providerResultId = source,
                sourceUrl = source,
                imageUrl = image,
                title = service,
                providerSimilarity = similarity,
                similarityScale = SimilarityScale.PERCENT_0_100,
                indexName = service.takeIf { it.isNotBlank() },
                metadata = metadata,
                searchedAtMillis = searchedAtMillis,
            )
        }
        return hits
    }

    /**
     * Reads pasted lines. Each non-blank line contributes at most one row: the first absolute URL it
     * contains, plus a `NN%` figure when the user pasted one. A line without a URL is skipped rather than
     * turned into a title-only "result".
     */
    fun parsePasted(text: String, searchedAtMillis: Long, limit: Int = 10): List<ReverseImageHit> {
        if (text.isBlank() || limit <= 0) return emptyList()
        val urlPattern = Regex("(?i)\\b(https?://[^\\s<>\"']+)")
        val percentPattern = Regex("(?i)(\\d{1,3})\\s*%")
        val hits = ArrayList<ReverseImageHit>()
        for (raw in text.lineSequence()) {
            if (hits.size >= limit) break
            val line = raw.trim()
            if (line.isEmpty()) continue
            val url = urlPattern.find(line)?.groupValues?.get(1) ?: continue
            val similarityText = percentPattern.find(line)?.groupValues?.get(1)
            val similarity = similarityText?.toDoubleOrNull()
            val title = line.substringBefore(url).trim().trim('-', ':', '\u2013', '\u2014')
            val metadata = LinkedHashMap<String, String>()
            metadata["submitted"] = "pasted by the user"
            if (similarityText != null) metadata["similarity"] = "$similarityText%"
            hits += ReverseImageHit(
                providerId = PROVIDER,
                providerName = NAME,
                providerResultId = url,
                sourceUrl = url,
                title = title,
                providerSimilarity = similarity,
                similarityScale = SimilarityScale.PERCENT_0_100,
                metadata = metadata,
                searchedAtMillis = searchedAtMillis,
            )
        }
        return hits
    }

    /** True for a link that points at an image file rather than at a page. */
    private fun looksLikeImage(url: String): Boolean = IMAGE_FILE.containsMatchIn(url)

    /** Protocol-relative links (`//danbooru.donmai.us/…`) become absolute `https` URLs. */
    private fun absolute(link: String): String {
        val trimmed = link.trim()
        return when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            else -> ""
        }
    }
}
