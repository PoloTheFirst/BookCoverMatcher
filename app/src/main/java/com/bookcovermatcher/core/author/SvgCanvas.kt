// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.author

/**
 * Gives a generated SVG the `viewBox` it is missing, and trims the empty band under its content
 * (v2.6.0 crash-fix 4).
 *
 * The contribution graph — and every Shields.io badge — declares `width`/`height` but **no `viewBox`**:
 *
 * ```xml
 * <svg width="1166" height="184" xmlns="…"> <rect width="100%" height="100%" fill="#0d1117"/> …
 * ```
 *
 * A `viewBox` is what tells a renderer how the document's own coordinates map onto the viewport. Without one, the
 * content keeps its own 1166×184 coordinates while percentage geometry is resolved against the *viewport*, which
 * this app shrinks to 1000×157 when it decodes the file. `100%` therefore painted a 1000×157 rectangle — so the
 * last 166 units across (the newest weeks: October and November) and the bottom 27 units were never painted and
 * showed whatever was behind the picture. On the device that was the Author page's navy panel, which is why those
 * areas looked like "no dark background" and the bottom strip looked like an extra heat-map row.
 *
 * This object makes the relationship explicit instead of guessing at each site:
 *
 *  * if the root already has a `viewBox`, it is returned untouched — nothing to fix, and nothing to break;
 *  * otherwise a numeric `width`/`height` becomes `viewBox="0 0 width height"`, so `100%` means the whole canvas;
 *  * the box is then **trimmed to the drawing's own bounds** when the canvas is taller than its content, which
 *    removes the empty band below the last contribution row without touching a single cell.
 *
 * Everything here is pure Kotlin, so the geometry can be asserted on the JVM against the real graph, and it is
 * generic: any generated SVG with a padded canvas is corrected the same way.
 */
object SvgCanvas {

    /** A canvas smaller than its content, or a trim smaller than this, is left alone rather than nudged. */
    const val MIN_TRIM_UNITS = 4f

    /** One unit of margin kept below the content, so the last row's edge is not clipped by rounding. */
    const val TRIM_MARGIN_UNITS = 1f

    /** The root element. */
    private val ROOT = Regex("<svg\\b[^>]*>", RegexOption.IGNORE_CASE)

    /** A numeric length attribute of the root. */
    private val LENGTH = Regex("\\b(width|height)\\s*=\\s*\"([0-9.]+)\"", RegexOption.IGNORE_CASE)

    /** The root's own width attribute, for rewriting a trimmed canvas. */
    private val WIDTH_ATTRIBUTE = Regex("\\bwidth\\s*=\\s*\"[0-9.]+?\"", RegexOption.IGNORE_CASE)

    /** The root's own height attribute. */
    private val HEIGHT_ATTRIBUTE = Regex("\\bheight\\s*=\\s*\"[0-9.]+?\"", RegexOption.IGNORE_CASE)

    /** Any rectangle's vertical extent. */
    private val RECT = Regex("<rect\\b[^>]*>", RegexOption.IGNORE_CASE)

    /** A text baseline. */
    private val TEXT_Y = Regex("<text\\b[^>]*\\by\\s*=\\s*\"(-?[0-9.]+)\"", RegexOption.IGNORE_CASE)

    /**
     * The document with a `viewBox` that covers exactly what it draws.
     *
     * A document that already has a `viewBox`, or that declares no numeric size, is returned unchanged (the same
     * string), so this is safe to call on every SVG the app loads.
     */
    fun normalise(document: String): String {
        if (document.isEmpty()) return document
        val root = ROOT.find(document) ?: return document
        val rootTag = root.value
        if (rootTag.contains("viewBox", ignoreCase = true)) return document

        val width = length(rootTag, "width") ?: return document
        val height = length(rootTag, "height") ?: return document
        if (width <= 0f || height <= 0f) return document

        val contentBottom = contentBottomOf(document) ?: return document
        val trimmed = height - contentBottom >= MIN_TRIM_UNITS
        // Keeping the content's own bounds when trimming is what removes the surplus band; otherwise the declared
        // canvas is what the author intended.
        val visibleBottom = if (trimmed) contentBottom + TRIM_MARGIN_UNITS else height
        if (visibleBottom <= 0f) return document

        // The declared height is rewritten too: a viewBox shorter than the declared size would be letterboxed inside
        // it, putting back an empty transparent band exactly where the surplus row was removed.
        var rewritten = if (trimmed) resize(rootTag, width, visibleBottom) else rootTag
        val viewBox = "viewBox=\"0 0 " + number(width) + " " + number(visibleBottom) + "\""
        rewritten = rewritten.substring(0, rewritten.length - 1) + " " + viewBox + ">"
        return document.replaceFirst(rootTag, rewritten)
    }

    /** [rootTag] with its declared size set to [width] × [height]. */
    private fun resize(rootTag: String, width: Float, height: Float): String {
        var tag = rootTag.replace(WIDTH_ATTRIBUTE, "width=\"" + number(width) + "\"")
        tag = tag.replace(HEIGHT_ATTRIBUTE, "height=\"" + number(height) + "\"")
        return tag
    }

    /** The lowest point any drawn element reaches, or null when the document draws nothing measurable. */
    private fun contentBottomOf(document: String): Float? {
        // A percentage length cannot be measured without the canvas, so such an element is skipped rather than guessed
        // — the trim is then conservative (it keeps more canvas), which is the safe direction.
        var bottom = Float.NEGATIVE_INFINITY
        for (match in RECT.findAll(document)) {
            val tag = match.value
            val height = attribute(tag, "height")?.toFloatOrNull() ?: continue
            val yAttribute = attribute(tag, "y")
            val y = if (yAttribute == null) 0f else yAttribute.toFloatOrNull() ?: continue
            val candidate = y + height
            if (candidate > bottom) bottom = candidate
        }
        for (match in TEXT_Y.findAll(document)) {
            val candidate = match.groupValues[1].toFloatOrNull() ?: continue
            if (candidate > bottom) bottom = candidate
        }
        return if (bottom == Float.NEGATIVE_INFINITY) null else bottom
    }

    /** A numeric length of the root tag, or null when it is absent or not a plain number. */
    private fun length(rootTag: String, name: String): Float? {
        for (match in LENGTH.findAll(rootTag)) {
            if (!match.groupValues[1].equals(name, ignoreCase = true)) continue
            return match.groupValues[2].toFloatOrNull()
        }
        return null
    }

    /** One `name="value"` attribute of a tag. */
    private fun attribute(tag: String, name: String): String? =
        Regex("\\b" + Regex.escape(name) + "\\s*=\\s*\"([^\"]*)\"").find(tag)?.groupValues?.get(1)

    /** A float as SVG writes it: no exponent, no locale-dependent separator, no trailing zeros. */
    private fun number(value: Float): String {
        val rounded = Math.round(value * 1000.0) / 1000.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
    }
}
