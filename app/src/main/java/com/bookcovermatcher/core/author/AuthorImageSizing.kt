// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.author

/**
 * How large each kind of README picture may be drawn (v2.6.0 crash-fix 1).
 *
 * The renderer used to size every picture the same way — full width, height from its aspect ratio — which is
 * right for the banner and the contribution graph and wrong for a Shields.io badge: a 100×20 badge was stretched
 * to the full width of the panel and roughly 190 dp tall, so thirteen of them filled several screens.
 *
 * This object is the one place that decides the box, and it is pure Kotlin so the rule can be asserted on the JVM
 * without a screen, a bitmap or an Android device:
 *
 *  * a **badge** is drawn at a fixed height, with the width that preserves its own aspect ratio and never more
 *    than [BADGE_MAX_WIDTH_FRACTION] of the row, so it can share a line with its neighbours;
 *  * every other picture keeps the full width and derives its height from the real aspect ratio, so the banner
 *    and the graph are never squeezed into a badge-sized slot.
 */
object AuthorImageSizing {

    /** The host whose pictures are badges: Shields.io renders one fixed-height pill per URL. */
    const val BADGE_HOST = "img.shields.io"

    /** The height a badge is drawn at, matching the size GitHub gives it in a README. */
    const val BADGE_HEIGHT_DP = 20f

    /** A badge may not take more than this share of the row, so several fit on one line. */
    const val BADGE_MAX_WIDTH_FRACTION = 0.6f

    /** The aspect ratio used when a picture reports a nonsensical one (zero, negative, NaN, infinite). */
    private const val FALLBACK_ASPECT = 1f

    /** A picture taller than this many times the row width is clipped to that, so no image can own the screen. */
    private const val MAX_HEIGHT_MULTIPLE = 3f

    /** A `widthDp` × `heightDp` box, in density-independent pixels. */
    data class Box(val widthDp: Float, val heightDp: Float) {
        val aspectRatio: Float get() = if (heightDp > 0f) widthDp / heightDp else 1f
    }

    /** True when [url] is a Shields.io badge, decided by host and never by the size it happens to report. */
    fun isBadge(url: String?): Boolean {
        val host = hostOf(url) ?: return false
        return host == BADGE_HOST
    }

    /**
     * The box [url] must be drawn in.
     *
     * @param aspectRatio width / height as decoded from the picture itself.
     * @param availableWidthDp the width of the row the picture sits in.
     */
    fun box(url: String?, aspectRatio: Float, availableWidthDp: Float): Box {
        val width = availableWidthDp.takeIf { it.isFinite() && it > 0f } ?: 1f
        val aspect = aspectRatio.takeIf { it.isFinite() && it > 0f } ?: FALLBACK_ASPECT
        if (isBadge(url)) {
            val height = BADGE_HEIGHT_DP
            val natural = height * aspect
            val cap = width * BADGE_MAX_WIDTH_FRACTION
            return Box(widthDp = minOf(natural, cap).coerceAtLeast(1f), heightDp = height)
        }
        val height = (width / aspect).coerceAtMost(width * MAX_HEIGHT_MULTIPLE)
        return Box(widthDp = width, heightDp = height.coerceAtLeast(1f))
    }

    /** The widest a badge may be drawn in a row of [availableWidthDp]; the bound a regression test asserts. */
    fun badgeMaxWidthDp(availableWidthDp: Float): Float = availableWidthDp * BADGE_MAX_WIDTH_FRACTION

    /** The host of an absolute HTTPS URL, lowercased, or null when there is none. */
    private fun hostOf(url: String?): String? {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val scheme = trimmed.indexOf("://")
        if (scheme <= 0) return null
        val rest = trimmed.substring(scheme + 3)
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (end < 0) rest else rest.substring(0, end)
        val host = authority.substringAfterLast('@').substringBefore(':')
        return host.lowercase().ifBlank { null }
    }
}
