package com.bookcovermatcher.core.online.reverse

/**
 * Where a discovery result's picture came from. Kept explicit because the two are not equal evidence:
 * a provider thumbnail is something SauceNAO itself associated with the match, while a webpage image was merely
 * declared by that page's own metadata.
 */
enum class DiscoveryImageSource {
    /** The provider's own result image (`header.thumbnail` in the SauceNAO response). */
    PROVIDER,

    /** `og:image` / `og:image:secure_url` on the result's own page. */
    WEBPAGE_OPEN_GRAPH,

    /** `twitter:image` / `twitter:image:src` on the result's own page. */
    WEBPAGE_TWITTER,

    /** `<link rel="image_src">` or `itemprop="image"` on the result's own page. */
    WEBPAGE_IMAGE_SRC,
}

/**
 * The state of one candidate's picture, so the UI can draw an honest placeholder instead of guessing.
 *
 * [UNAVAILABLE] (nothing found, or a page we are not allowed to fetch) is deliberately different from [FAILED]
 * (a fetch was attempted and did not produce a usable image): a card with either state is still a perfectly valid
 * result — the image is decoration, never evidence for the match.
 */
enum class DiscoveryImageState {
    NOT_REQUESTED,
    LOADING,
    AVAILABLE,
    UNAVAILABLE,
    FAILED,
}

/** One image URL found on a result page, with the metadata that declared it. */
data class ExtractedCoverImage(val url: String, val source: DiscoveryImageSource)

/**
 * The SSRF guard for every URL this feature is willing to fetch.
 *
 * A reverse-image result's "source page" is attacker-controllable data: it arrives from a third-party provider and
 * may point anywhere, including at the device's own network. Before a socket is opened (for the page **and** for
 * every redirect hop and every image URL), the target must pass these rules:
 *
 *  * **HTTPS only.** The app refuses cleartext (a plain `http` image would also be blocked by the platform on
 *    target SDK 35, so accepting it would only produce confusing failures).
 *  * **A real host.** No credentials in the URL, no non-default port, and the host must not be a bare name
 *    (`localhost`, `intranet`, `nas`) — a dot is required.
 *  * **A public address.** Loopback, private (10/8, 172.16/12, 192.168/16), link-local (169.254/16, fe80::/10),
 *    unique-local (fc00::/7), `0.0.0.0`, `::1` and the `*.local`/`*.internal`/`*.localhost` names are all refused,
 *    whether written as a name or as an IPv4/IPv6 literal.
 *
 * What this deliberately does **not** do is resolve DNS. A hostname that resolves to a private address at fetch
 * time is therefore outside this guard; the mitigation is that only URLs a *provider* returned or a *result page*
 * declared are ever fetched, each with a hard timeout, a byte cap and a content-type check. Plain Kotlin/JVM so the
 * rules can be unit-tested without a socket.
 */
object SafeImageUrl {

    /** The longest URL this feature will look at; anything longer is treated as junk. */
    const val MAX_LENGTH = 2048

    /** True when [raw] is a URL this app may open a connection to. */
    fun isFetchable(raw: String?): Boolean = parse(raw) != null

    /** The lower-cased host of [raw], when it is a fetchable URL. */
    fun host(raw: String?): String? = parse(raw)?.host

    private data class Target(val host: String)

    private fun parse(raw: String?): Target? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text.length > MAX_LENGTH) return null
        if (text.any { it == ' ' || it == '\n' || it == '\r' || it == '\t' }) return null
        val uri = try {
            java.net.URI(text)
        } catch (e: Exception) {
            return null
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.userInfo != null) return null
        if (uri.port != -1 && uri.port != 443) return null
        val host = uri.host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return null
        if (!host.contains('.')) return null
        if (isPrivateHost(host)) return null
        return Target(host)
    }

    private fun isPrivateHost(host: String): Boolean {
        val blockedNames = listOf("localhost", "local", "internal", "lan", "home", "home.arpa", "arpa")
        if (blockedNames.any { host == it || host.endsWith(".$it") }) return true
        if (isIPv4Literal(host)) return isPrivateIPv4(host)
        if (host.contains(':')) return isPrivateIPv6(host)
        return false
    }

    private fun isIPv4Literal(host: String): Boolean =
        host.split('.').let { parts -> parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.all { it.isDigit() } && p.toIntOrNull() in 0..255 } }

    private fun isPrivateIPv4(host: String): Boolean {
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return true
        val (a, b) = parts[0] to parts[1]
        return a == 0 || a == 10 || a == 127 ||
            (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 100 && b in 64..127) ||
            a >= 224
    }

    private fun isPrivateIPv6(host: String): Boolean {
        val h = host.removePrefix("[").removeSuffix("]")
        if (h == "::1" || h == "::") return true
        if (h.startsWith("fe80") || h.startsWith("fc") || h.startsWith("fd")) return true
        // An IPv4-mapped address (`::ffff:10.0.0.1`) hides a private v4 target behind a v6 literal.
        val mapped = h.substringAfterLast(':', "")
        return mapped.contains('.') && isIPv4Literal(mapped) && isPrivateIPv4(mapped)
    }
}

/**
 * Pulls a representative image URL out of a result page's own metadata.
 *
 * Conservative by construction — this is the difference between "the page told us which image represents it" and
 * "we took the first `<img>` we found":
 *
 *  * only **declared metadata** is read: Open Graph (`og:image`, `og:image:secure_url`), Twitter cards
 *    (`twitter:image`, `twitter:image:src`), `<link rel="image_src">` and `itemprop="image"`;
 *  * the order is the priority order, and the declaring metadata is returned with the URL so the UI can say where
 *    the picture came from;
 *  * a relative URL is resolved against the page, and the result must pass [SafeImageUrl] — so a page cannot point
 *    the app at a private address;
 *  * a page with no such metadata yields `null`, and the card keeps its placeholder. That is a normal outcome, not
 *    an error.
 *
 * Plain Kotlin/JVM: the parsing rules are unit-tested against sanitised markup, with no network involved.
 */
object CoverImageExtractor {

    private val META = Regex("(?is)<meta\\s+([^>]*?)/?>")
    private val LINK = Regex("(?is)<link\\s+([^>]*?)/?>")
    private val ATTR = Regex("(?is)([a-z_:][-a-z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")

    /**
     * @return the highest-priority declared image, or null when the page declares none that may be fetched
     */
    fun fromHtml(html: String, pageUrl: String): ExtractedCoverImage? {
        if (html.isBlank()) return null
        val meta = LinkedHashMap<String, String>()
        for (tag in META.findAll(html)) {
            val attrs = attributes(tag.groupValues[1])
            val key = (attrs["property"] ?: attrs["name"] ?: attrs["itemprop"])?.trim()?.lowercase() ?: continue
            val content = attrs["content"]?.trim().orEmpty()
            if (content.isNotEmpty() && meta[key] == null) meta[key] = content
        }
        val linkImage = LINK.findAll(html)
            .map { attributes(it.groupValues[1]) }
            .firstOrNull { attrs -> attrs["rel"]?.trim()?.lowercase() == "image_src" }
            ?.get("href")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        val ordered = listOf(
            "og:image:secure_url" to DiscoveryImageSource.WEBPAGE_OPEN_GRAPH,
            "og:image" to DiscoveryImageSource.WEBPAGE_OPEN_GRAPH,
            "twitter:image" to DiscoveryImageSource.WEBPAGE_TWITTER,
            "twitter:image:src" to DiscoveryImageSource.WEBPAGE_TWITTER,
            "image" to DiscoveryImageSource.WEBPAGE_IMAGE_SRC,
        )
        for ((key, source) in ordered) {
            val resolved = resolve(meta[key], pageUrl) ?: continue
            return ExtractedCoverImage(resolved, source)
        }
        val resolvedLink = resolve(linkImage, pageUrl) ?: return null
        return ExtractedCoverImage(resolvedLink, DiscoveryImageSource.WEBPAGE_IMAGE_SRC)
    }

    /** Resolves [raw] against [pageUrl] and returns it only when [SafeImageUrl] accepts it. */
    fun resolve(raw: String?, pageUrl: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val absolute = try {
            java.net.URI(value)
        } catch (e: Exception) {
            return null
        }
        val resolved = if (absolute.isAbsolute) {
            value
        } else {
            val base = try {
                java.net.URI(pageUrl?.trim().orEmpty())
            } catch (e: Exception) {
                return null
            }
            try {
                base.resolve(absolute).toString()
            } catch (e: Exception) {
                return null
            }
        }
        // Anything with a scheme that is not https (data:, javascript:, file:, http:) is refused here, even though
        // SafeImageUrl would also refuse it — the two checks are independent on purpose.
        return resolved.takeIf { SafeImageUrl.isFetchable(it) }
    }

    private fun attributes(raw: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (m in ATTR.findAll(raw)) {
            val name = m.groupValues[1].lowercase()
            val value = m.groupValues.drop(2).firstOrNull { it.isNotEmpty() }.orEmpty()
            if (out[name] == null) out[name] = value
        }
        return out
    }
}

