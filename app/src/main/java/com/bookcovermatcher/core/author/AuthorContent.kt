// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.author

import com.bookcovermatcher.core.markdown.Markdown
import com.bookcovermatcher.core.markdown.MdBlock

/** Where the README currently on screen came from. */
enum class AuthorSource {
    /** The copy packaged in the APK, used before the first successful fetch. */
    BUNDLED,

    /** The last successfully fetched copy, stored on the device. */
    CACHED,

    /** Fetched during this launch. */
    REFRESHED,
}

/**
 * The checks that decide whether a download may replace what the user already has (v2.6.0 amendment).
 *
 * They are pure so the rules can be asserted without a network: a GitHub error page, an empty body, a truncated
 * image or a JSON rate-limit message must never overwrite a good cached README or picture.
 */
object AuthorContentValidator {

    /** A README larger than this is not the owner's README; it is something going wrong. */
    const val MAX_README_BYTES = 256 * 1024

    /** The bundled banner is 9.6 MB, so the cap has to sit above it while still bounding memory. */
    const val MAX_IMAGE_BYTES = 14 * 1024 * 1024

    /** True when these bytes are a plausible README rather than an error page or an empty body. */
    fun acceptsReadme(bytes: ByteArray, contentType: String? = null): Boolean {
        if (bytes.isEmpty() || bytes.size > MAX_README_BYTES) return false
        if (contentType != null && contentType.isNotBlank() && !contentType.contains("text", true) &&
            !contentType.contains("markdown", true) && !contentType.contains("plain", true)
        ) {
            // GitHub serves text/plain; anything binary is not a README.
            return false
        }
        val text = String(bytes, Charsets.UTF_8)
        if (text.isBlank()) return false
        val head = text.trimStart().take(200).lowercase()
        if (head.startsWith("<html") || head.startsWith("<!doctype html") || head.startsWith("{")) return false
        // A README we can render has at least one heading or one paragraph of reasonable length.
        return text.contains('\n') || text.length > 40
    }

    /** True when these bytes are a decodable picture rather than an HTML page or a truncated download. */
    fun acceptsImage(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return false
        return AuthorImageFormat.isRenderable(bytes)
    }
}

/**
 * The images a README actually asks for, in document order and without duplicates.
 *
 * Both picture syntaxes are collected: a standalone `![alt](src)` line (the banner) and an inline image, plus the
 * `<img>` tags the HTML pass now turns into image blocks (the badges and the contribution graph).
 */
object AuthorImageRefs {

    fun of(markdown: String): List<String> {
        val urls = LinkedHashSet<String>()
        Markdown.parse(markdown).forEach { block ->
            when (block) {
                is MdBlock.Image -> if (block.url.isNotBlank()) urls += block.url.trim()
                is MdBlock.Paragraph -> block.runs.filter { it.image }.forEach { run ->
                    run.url?.takeIf { it.isNotBlank() }?.let { urls += it.trim() }
                }
                is MdBlock.Heading -> block.runs.filter { it.image }.forEach { run ->
                    run.url?.takeIf { it.isNotBlank() }?.let { urls += it.trim() }
                }
                is MdBlock.Listing -> block.items.forEach { item ->
                    item.runs.filter { it.image }.forEach { run ->
                        run.url?.takeIf { it.isNotBlank() }?.let { urls += it.trim() }
                    }
                }
                else -> Unit
            }
        }
        return urls.toList()
    }
}
