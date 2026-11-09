// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.author

/**
 * What a downloaded image actually is, decided from its bytes rather than its URL (v2.6.0 amendment).
 *
 * The URL is not trustworthy evidence of a format: GitHub serves the owner's profile picture as **JPEG** from a
 * path ending in `.png`, and `img.shields.io` returns **SVG** from a path with no extension at all. The fetcher
 * therefore sniffs the first bytes, and the cache and the decoder both use this one answer.
 */
enum class AuthorImageFormat {
    PNG,
    JPEG,
    WEBP,
    GIF,
    SVG,
    UNKNOWN;

    companion object {

        /** Identifies [bytes], reading only the first few. */
        fun of(bytes: ByteArray): AuthorImageFormat {
            if (bytes.size >= 8 &&
                bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
                bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
            ) {
                return PNG
            }
            if (bytes.size >= 3 &&
                bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
            ) {
                return JPEG
            }
            if (bytes.size >= 12 &&
                bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
                bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
            ) {
                return WEBP
            }
            if (bytes.size >= 4 &&
                bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()
            ) {
                return GIF
            }
            if (looksLikeSvg(bytes)) return SVG
            return UNKNOWN
        }

        /** True when these bytes can be handed to a decoder at all. */
        fun isRenderable(bytes: ByteArray): Boolean = of(bytes) != UNKNOWN

        /**
         * An SVG document starts with an XML declaration, a comment, a doctype or the root `<svg` element.
         *
         * An HTML error page also starts with `<`, so `<html` and `<!doctype html` are explicitly excluded: a
         * failed download must never be cached or shown as though it were the badge.
         */
        private fun looksLikeSvg(bytes: ByteArray): Boolean {
            val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.UTF_8)
                .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
                .lowercase()
            if (head.startsWith("<!doctype html") || head.startsWith("<html")) return false
            if (head.startsWith("<svg")) return true
            if (!head.startsWith("<?xml") && !head.startsWith("<!--")) return false
            val svgAt = head.indexOf("<svg")
            return svgAt in 0..400 && (head.indexOf("<html") == -1 || head.indexOf("<html") > svgAt)
        }
    }
}
