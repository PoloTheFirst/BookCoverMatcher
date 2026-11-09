// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.author

import java.util.Base64

/**
 * Makes the SVG features a Shields.io badge actually uses legible to a non-SVG2 renderer (v2.6.0 crash-fix 2).
 *
 * A Shields.io badge draws its technology icon as a **nested SVG document carried in a `data:` URI**:
 *
 * ```xml
 * <image x="5" y="3" width="14" height="14"
 *        href="data:image/svg+xml;base64,PHN2ZyBmaWxsPSJ3aGl0ZSIgdmlld0JveD0iMCAwIDI0IDI0Ij4…"/>
 * ```
 *
 * `AndroidSVG 1.4` renders such a picture as nothing at all, for two independent reasons, and both have to be
 * removed before the icon appears — which is why a badge showed its background and its text but left the icon's
 * space blank:
 *
 *  1. it reads the SVG 1.1 attribute `xlink:href`, not SVG 2's plain `href`, so the reference is never seen;
 *  2. even when it is seen, its `<image>` handler decodes with `BitmapFactory`, so only raster payloads work — an
 *     `image/svg+xml` payload yields no bitmap.
 *
 * This object converts the document instead of the renderer:
 *
 *  * a nested **SVG** data URI becomes a `<g transform="…">` holding that document's own content, positioned and
 *    scaled exactly where the `<image>` would have drawn it (`x`, `y`, `width`, `height`, `viewBox`, with
 *    `preserveAspectRatio` honoured);
 *  * a remaining `data:image/png|jpeg|gif|webp` reference keeps its `<image>` element but has the attribute
 *    rewritten to `xlink:href`, so the raster decoder can find it;
 *  * anything else — an external `https://` reference, a malformed payload, an unsupported scheme — is left exactly
 *    as it was. Nothing is fetched, no script survives, nothing is executed.
 *
 * It is pure Kotlin (no Android type), so the transformation is asserted on the JVM, and it is generic: the rule is
 * "resolve an embedded SVG icon", not "special-case the Java badge".
 */
object SvgEmbeddedImages {

    /** The largest `data:` payload this will decode, so a hostile document cannot spend unbounded memory. */
    const val MAX_PAYLOAD_CHARS = 256 * 1024

    /** The greatest number of images inlined in one document. */
    const val MAX_CONVERSIONS = 48

    /** The xlink namespace URI a renderer expects for `xlink:href`. */
    private const val XLINK_URI = "http://www.w3.org/1999/xlink"

    /** Elements inside an embedded icon that carry no drawing and must not be carried over. */
    private val NON_DRAWING = Regex(
        "<\\s*(script|title|desc|metadata|style)\\b[^>]*>.*?<\\s*/\\s*\\1\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** Presentation attributes of an embedded icon's root that its content inherits. */
    private val INHERITED = listOf(
        "fill", "fill-rule", "fill-opacity", "stroke", "stroke-width", "stroke-linecap",
        "stroke-linejoin", "stroke-opacity", "opacity", "color",
    )

    private val ATTRIBUTE = Regex("([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")

    /**
     * The document with its embedded icon images resolved.
     *
     * A document that references no embedded image is returned unchanged (the same string), so a plain SVG costs
     * nothing beyond one scan.
     */
    fun prepare(document: String): String {
        if (document.isEmpty()) return document
        if (!document.contains("<image", ignoreCase = true)) return document
        var out = inlineEmbeddedSvgs(document)
        out = pointRasterImagesAtXlink(out)
        return ensureXlinkNamespace(out)
    }

    /** True when [document] carries an image reference this renderer cannot resolve by itself. */
    fun needsPreparation(document: String): Boolean =
        document.contains("<image", ignoreCase = true) &&
            (document.contains("data:image/svg+xml", ignoreCase = true) ||
                IMAGE_WITH_BARE_HREF.containsMatchIn(document))

    /**
     * Replaces every `<image>` whose target is an embedded SVG with a positioned `<g>` of that SVG's content.
     *
     * The scan is span-based rather than one regular expression, so a self-closing `<image/>`, an
     * `<image>…</image>` pair and an attribute value containing `>` are all handled the same way.
     */
    private fun inlineEmbeddedSvgs(document: String): String {
        val replacements = mutableListOf<Triple<Int, Int, String>>()
        var index = 0
        while (index < document.length && replacements.size < MAX_CONVERSIONS) {
            val open = document.indexOf("<image", index, ignoreCase = true)
            if (open < 0) break
            val end = tagEnd(document, open)
            if (end < 0) break
            var spanEnd = end + 1
            val raw = document.substring(open, spanEnd)
            if (!raw.trimEnd().endsWith("/>")) {
                val closing = document.indexOf("</image", spanEnd, ignoreCase = true)
                if (closing < 0) break
                val closingEnd = tagEnd(document, closing)
                if (closingEnd < 0) break
                spanEnd = closingEnd + 1
            }
            val attributes = attributesOf(raw)
            val href = attributes["href"] ?: attributes["xlink:href"]
            val replacement = href?.let { svgGroupFor(it, attributes) }
            if (replacement != null) replacements += Triple(open, spanEnd, replacement)
            index = spanEnd
        }
        if (replacements.isEmpty()) return document
        val out = StringBuilder(document)
        for ((start, end, text) in replacements.sortedByDescending { it.first }) {
            out.replace(start, end, text)
        }
        return out.toString()
    }

    /**
     * The `<g>` that draws [href]'s embedded SVG inside the box [attributes] describes, or null when [href] is not
     * an embedded SVG this object will convert.
     */
    private fun svgGroupFor(href: String, attributes: Map<String, String>): String? {
        val prefix = "data:image/svg+xml;base64,"
        if (!href.startsWith(prefix, ignoreCase = true)) return null
        val payload = href.substring(prefix.length).trim()
        if (payload.isEmpty() || payload.length > MAX_PAYLOAD_CHARS) return null
        val decoded = runCatching { String(Base64.getDecoder().decode(payload), Charsets.UTF_8) }.getOrNull()
            ?: return null
        return groupForEmbedded(decoded, attributes)
    }

    /** Builds the positioned group for an already-decoded embedded SVG document. */
    private fun groupForEmbedded(embedded: String, attributes: Map<String, String>): String? {
        val rootStart = embedded.indexOf("<svg", ignoreCase = true)
        if (rootStart < 0) return null
        val rootEnd = tagEnd(embedded, rootStart)
        if (rootEnd < 0) return null
        val rootAttributes = attributesOf(embedded.substring(rootStart, rootEnd + 1))
        val innerEnd = embedded.lastIndexOf("</svg", ignoreCase = true)
        val inner = if (innerEnd > rootEnd) embedded.substring(rootEnd + 1, innerEnd) else ""
        if (inner.isBlank()) return null

        val box = viewBoxOf(rootAttributes) ?: return null
        val x = attributes["x"]?.toFloatOrNull() ?: 0f
        val y = attributes["y"]?.toFloatOrNull() ?: 0f
        val width = attributes["width"]?.toFloatOrNull() ?: box[2]
        val height = attributes["height"]?.toFloatOrNull() ?: box[3]
        if (width <= 0f || height <= 0f) return null

        val stretched = rootAttributes["preserveaspectratio"]?.equals("none", ignoreCase = true) == true
        val scaleX = width / box[2]
        val scaleY = height / box[3]
        val transform = if (stretched) {
            "translate(" + number(x - box[0] * scaleX) + "," + number(y - box[1] * scaleY) + ") " +
                "scale(" + number(scaleX) + "," + number(scaleY) + ")"
        } else {
            val scale = minOf(scaleX, scaleY)
            val offsetX = x + (width - box[2] * scale) / 2f - box[0] * scale
            val offsetY = y + (height - box[3] * scale) / 2f - box[1] * scale
            "translate(" + number(offsetX) + "," + number(offsetY) + ") scale(" + number(scale) + ")"
        }

        val inherited = INHERITED.mapNotNull { name ->
            rootAttributes[name]?.let { value -> " " + name + "=\"" + escape(value) + "\"" }
        }.joinToString("")

        return "<g transform=\"" + transform + "\"" + inherited + ">" +
            NON_DRAWING.replace(inner, "") + "</g>"
    }

    /** `viewBox` (or `width`/`height`) of an embedded document's root, as `[minX, minY, width, height]`. */
    private fun viewBoxOf(root: Map<String, String>): FloatArray? {
        root["viewbox"]?.let { raw ->
            val parts = raw.split(',', ' ', '\t', '\n').filter { it.isNotBlank() }.mapNotNull { it.toFloatOrNull() }
            if (parts.size == 4 && parts[2] > 0f && parts[3] > 0f) return parts.toFloatArray()
        }
        val width = root["width"]?.let { lengthOf(it) }
        val height = root["height"]?.let { lengthOf(it) }
        if (width != null && height != null && width > 0f && height > 0f) {
            return floatArrayOf(0f, 0f, width, height)
        }
        return null
    }

    /** A length attribute with any unit suffix removed. */
    private fun lengthOf(raw: String): Float? =
        raw.trim().takeWhile { it.isDigit() || it == '.' || it == '-' }.toFloatOrNull()

    /**
     * Rewrites a raster `<image href>` to `xlink:href`.
     *
     * SVG 2 renamed the attribute and `AndroidSVG` still reads the 1.1 name, so a PNG icon (the Bash badge uses
     * one) would be invisible for the same reason the SVG icons were.
     */
    private fun pointRasterImagesAtXlink(document: String): String {
        var out = document
        RASTER_IMAGE_WITH_BARE_HREF.findAll(document).forEach { match ->
            val tag = match.value
            if (tag.contains("xlink:href", ignoreCase = true)) return@forEach
            // Only the attribute name is rewritten: the leading whitespace must survive, or `<image x=… href=…>`
            // becomes the unparsable element name `<imagexlink:href>`.
            val rewritten = BARE_HREF_NAME.replace(tag) { m -> "xlink:href" + m.groupValues[1] }
            out = out.replace(tag, rewritten)
        }
        return out
    }

    /** Adds `xmlns:xlink` to the root element when the document now uses `xlink:` and does not declare it. */
    private fun ensureXlinkNamespace(document: String): String {
        if (!document.contains("xlink:", ignoreCase = true)) return document
        if (document.contains("xmlns:xlink", ignoreCase = true)) return document
        val root = ROOT_SVG.find(document) ?: return document
        val tag = root.value
        val end = tag.lastIndexOf('>')
        if (end < 0) return document
        val withNamespace = tag.substring(0, end) + " xmlns:xlink=\"" + XLINK_URI + "\"" + tag.substring(end)
        return document.replaceFirst(tag, withNamespace)
    }

    /** `name="value"` pairs of one tag. */
    private fun attributesOf(tag: String): Map<String, String> {
        val found = mutableMapOf<String, String>()
        for (match in ATTRIBUTE.findAll(tag)) {
            found[match.groupValues[1].lowercase()] = match.groupValues[3].ifEmpty { match.groupValues[4] }
        }
        return found
    }

    /** A float as SVG writes it: no exponent, no locale-dependent separator, no trailing zeros. */
    private fun number(value: Float): String {
        if (!value.isFinite()) return "0"
        val rounded = Math.round(value * 100_000.0) / 100_000.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
    }

    /** Escapes a value about to be written into a double-quoted attribute. */
    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /** Index of the `>` closing the tag that starts at [open], ignoring `>` inside quoted values. */
    private fun tagEnd(document: String, open: Int): Int {
        var quote = 0.toChar()
        var index = open + 1
        while (index < document.length) {
            val c = document[index]
            when {
                quote != 0.toChar() -> if (c == quote) quote = 0.toChar()
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return index
            }
            index++
        }
        return -1
    }

    private val IMAGE_WITH_BARE_HREF = Regex("<image\\b[^>]*\\shref\\s*=", RegexOption.IGNORE_CASE)

    private val RASTER_IMAGE_WITH_BARE_HREF =
        Regex("<image\\b[^>]*href\\s*=\\s*\"data:image/(?!svg)[^\"]*\"[^>]*>", RegexOption.IGNORE_CASE)

    private val BARE_HREF_NAME = Regex("(?<![A-Za-z0-9_:])href(\\s*=)", RegexOption.IGNORE_CASE)

    private val ROOT_SVG = Regex("<svg\\b[^>]*>", RegexOption.IGNORE_CASE)
}
