// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.author

/**
 * The animation a GitHub-generated SVG carries, evaluated into a still document per frame (v2.6.0 crash-fix 1).
 *
 * The Pacman contribution graph is an **SMIL-animated SVG**: 54 `<animate>` elements that switch each day's cell
 * between its activity colour and the eaten grey, plus six `<animateTransform>` elements that move and turn
 * Pacman. `AndroidSVG` parses the geometry and ignores SMIL entirely, which is why the graph used to appear frozen
 * in its first frame: a successful parse is not animation.
 *
 * This class is not a general SMIL implementation, and does not pretend to be. It handles exactly the animation
 * the generator emits — `attributeName="fill"` steps and `translate`/`rotate`/`scale` transforms, driven by
 * `keyTimes`/`values` over a repeating `dur` — by sampling the timeline at a given time and writing the sampled
 * value into the element's own attributes. The result is a *still* SVG document that is the source document at
 * time *t*: same shapes, same colours, same layout, one instant later.
 *
 * Because it only rewrites attribute values it can be checked on the JVM, needs no WebView (this app renders no
 * third-party pages), executes nothing, and keeps the original asset as the only source of truth:
 *
 *  * the document's own `dur` decides the loop length;
 *  * the document's own `frameRate` metadata decides the playback rate;
 *  * a document with no SMIL yields `null`, so a still badge is never treated as an animation.
 */
class SmilAnimation private constructor(
    private val source: String,
    /** The loop length the document declares, in milliseconds. */
    val durationMs: Long,
    /** The playback rate the document declares, clamped to [MIN_FPS]..[MAX_FPS]. */
    val framesPerSecond: Int,
    private val animated: List<AnimatedElement>,
    /** Elements whose `<animate>` children this class understands. */
    val animatedElementCount: Int,
) {

    /** How many distinct frames one loop has, at [framesPerSecond]. */
    val frameCount: Int = maxOf(1, ((durationMs * framesPerSecond) / 1000L).toInt())

    /**
     * The document this animation was detected in, for callers that need the same source (v2.6.0 stable).
     *
     * `PacmanCompositor` builds its scenery and its sprites from exactly the string this class scanned, so the two can
     * never disagree about what the document says.
     */
    val sourceDocument: String get() = source

    /**
     * True when at least one animation interpolates, so two different instants inside the same source frame differ.
     *
     * The Pacman graph is `linear` for its translate/rotate, so it is continuous; a document whose animations are all
     * `discrete` repeats its picture between keyTimes and the player can skip those ticks (v2.6.0 crash-fix 3).
     */
    val hasContinuousMotion: Boolean = animated.any { element ->
        element.smils.any { it.calcMode != "discrete" && it.values.size > 1 }
    }

    /**
     * The source document as it looks [elapsedMs] into the loop.
     *
     * Time is taken modulo [durationMs], so a caller may pass a monotonically growing clock. The returned string
     * contains no SMIL elements this class has consumed, so rendering it produces exactly the sampled instant
     * rather than the document's first frame.
     */
    fun frameDocument(elapsedMs: Long): String {
        if (animated.isEmpty() || durationMs <= 0L) return withoutAnimation()
        val wrapped = ((elapsedMs % durationMs) + durationMs) % durationMs
        val progress = wrapped.toFloat() / durationMs.toFloat()
        val edits = ArrayList<Edit>(animated.size * 2)
        for (element in animated) {
            var tag = source.substring(element.openStart, element.openEnd)
            val transforms = StringBuilder()
            for (smil in element.smils) {
                val value = sample(smil, progress) ?: continue
                if (smil.name == "animateTransform" || smil.attribute.equals("transform", true)) {
                    val one = transformFor(smil.type, value)
                    if (one.isNotEmpty()) {
                        if (transforms.isNotEmpty()) transforms.append(' ')
                        transforms.append(one)
                    }
                } else {
                    tag = withAttribute(tag, smil.attribute, value)
                }
            }
            if (transforms.isNotEmpty()) tag = withAttribute(tag, "transform", transforms.toString())
            edits += Edit(element.openStart, element.openEnd, tag)
            element.smils.forEach { edits += Edit(it.spanStart, it.spanEnd, "") }
        }
        // Apply from the end backwards so an earlier replacement cannot move a later span.
        edits.sortByDescending { it.start }
        val out = StringBuilder(source)
        for (edit in edits) out.replace(edit.start, edit.end, edit.text)
        return out.toString()
    }

    /** The document with its animation removed: one still frame, used when playback is not wanted. */
    fun withoutAnimation(): String {
        val edits = animated.flatMap { element -> element.smils.map { Edit(it.spanStart, it.spanEnd, "") } }
            .sortedByDescending { it.start }
        val out = StringBuilder(source)
        for (edit in edits) out.replace(edit.start, edit.end, edit.text)
        return out.toString()
    }

    /**
     * The elements that carry animation, for callers that build a scene graph out of the same scan (v2.6.0 stable).
     *
     * Exposed inside the module rather than recomputed: `SmilTimeline` needs the element spans, and a second scanner
     * could disagree with this one about where an element ends.
     */
    internal fun elements(): List<AnimatedElement> = animated

    /**
     * The value of [smil] at [progress] of *its own* duration.
     *
     * Shared with `SmilTimeline` so the 60 fps compositor interpolates exactly like the frame-document renderer: one
     * implementation of `calcMode`, `keyTimes` and colour blending, not two.
     */
    internal fun sampleValue(smil: Smil, progress: Float): String? = sample(smil, progress)

    /**
     * The sampled value of [smil] at [progress] in `0..1`, or null when it declares no usable values.
     *
     * `calcMode` is honoured, because the two halves of this document use different modes: the day cells declare
     * `calcMode="discrete"` (a cell is either its level colour or eaten, never half-eaten), while Pacman's
     * `fill`, `translate` and `rotate` declare no mode at all — which is SMIL's `linear` default. Interpolating the
     * ones that ask for it is what lets the animation be sampled at 18 fps instead of the generator's 5, without
     * moving Pacman any faster (v2.6.0 crash-fix 3).
     */
    private fun sample(smil: Smil, progress: Float): String? {
        val values = smil.values
        if (values.isEmpty()) return null
        if (values.size == 1) return values[0].trim().ifBlank { null }
        val times = keyTimesOf(smil)

        // The value in force is the last one whose keyTime has passed; the next one is the interpolation target.
        var index = 0
        while (index + 1 < times.size && progress + EPSILON >= times[index + 1]) index++
        val current = values[index].trim()
        if (smil.calcMode == "discrete") return current.ifBlank { null }

        val next = values.getOrNull(index + 1)?.trim() ?: return current.ifBlank { null }
        val from = times[index]
        val to = times.getOrNull(index + 1) ?: return current.ifBlank { null }
        if (to <= from) return current.ifBlank { null }
        val fraction = ((progress - from) / (to - from)).coerceIn(0f, 1f)
        return interpolate(current, next, fraction) ?: current.ifBlank { null }
    }

    /** `keyTimes` padded or synthesised so that `times.size == values.size`. */
    private fun keyTimesOf(smil: Smil): List<Float> {
        val values = smil.values.size
        val declared = smil.keyTimes
        if (declared.size == values) return declared
        if (values == 1) return listOf(0f)
        return List(values) { index -> index.toFloat() / (values - 1).toFloat() }
    }

    /**
     * The value between [from] and [to] at [fraction], or null when this kind of value cannot be blended.
     *
     * Numbers, numeric lists (`translate`, `rotate`, `scale`) and `#rrggbb` colours are interpolated; anything else
     * falls back to the step value, so an unrecognised value is never invented.
     */
    private fun interpolate(from: String, to: String, fraction: Float): String? {
        blendColour(from, to, fraction)?.let { return it }
        val a = from.split(',', ' ', '\t', '\n').filter { it.isNotBlank() }
        val b = to.split(',', ' ', '\t', '\n').filter { it.isNotBlank() }
        if (a.size != b.size || a.isEmpty()) return null
        val blended = ArrayList<String>(a.size)
        for (index in a.indices) {
            val left = a[index].toFloatOrNull() ?: return null
            val right = b[index].toFloatOrNull() ?: return null
            blended += number(left + (right - left) * fraction)
        }
        // Preserve the separator the source used, so `translate(1122,15)` does not become `translate(1122 15)`.
        return blended.joinToString(if (from.contains(',')) "," else " ")
    }

    /** Linear blend of two `#rrggbb` colours, or null when either is not one. */
    private fun blendColour(from: String, to: String, fraction: Float): String? {
        if (from.length != 7 || to.length != 7) return null
        if (!from.startsWith('#') || !to.startsWith('#')) return null
        val left = from.substring(1).toIntOrNull(16) ?: return null
        val right = to.substring(1).toIntOrNull(16) ?: return null
        val r = blend((left shr 16) and 0xFF, (right shr 16) and 0xFF, fraction)
        val g = blend((left shr 8) and 0xFF, (right shr 8) and 0xFF, fraction)
        val b = blend(left and 0xFF, right and 0xFF, fraction)
        return "#%02x%02x%02x".format(r, g, b)
    }

    private fun blend(from: Int, to: Int, fraction: Float): Int =
        Math.round(from + (to - from) * fraction).coerceIn(0, 255)

    /** A float as SVG writes it: short, locale-independent, no exponent. */
    private fun number(value: Float): String {
        val rounded = Math.round(value * 1_000.0) / 1_000.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
    }

    /** `animateTransform`'s value as a CSS transform; an unsupported type contributes nothing. */
    private fun transformFor(type: String?, value: String): String {
        val numbers = value.split(',', ' ', '\t', '\n').filter { it.isNotBlank() }
        return when (type?.lowercase()) {
            "translate" -> if (numbers.size >= 2) "translate(${numbers[0]},${numbers[1]})" else ""
            "rotate" -> when {
                numbers.size >= 3 -> "rotate(${numbers[0]} ${numbers[1]} ${numbers[2]})"
                numbers.size == 1 -> "rotate(${numbers[0]})"
                else -> ""
            }
            "scale" -> if (numbers.size >= 2) "scale(${numbers[0]},${numbers[1]})" else ""
            "skewx" -> if (numbers.isNotEmpty()) "skewX(${numbers[0]})" else ""
            "skewy" -> if (numbers.isNotEmpty()) "skewY(${numbers[0]})" else ""
            else -> ""
        }
    }

    /** Sets `name="value"` on an open tag, replacing an existing attribute of that name and nothing else. */
    private fun withAttribute(tag: String, name: String, value: String): String {
        if (name.isBlank()) return tag
        val existing = Regex("(\\s${Regex.escape(name)}\\s*=)\\s*(\"[^\"]*\"|'[^']*')", RegexOption.IGNORE_CASE)
        if (existing.containsMatchIn(tag)) {
            return existing.replace(tag) { match -> match.groupValues[1] + "\"" + value + "\"" }
        }
        val end = tag.lastIndexOf('>')
        if (end < 0) return tag
        return tag.substring(0, end) + " " + name + "=\"" + value + "\"" + tag.substring(end)
    }

    private data class Edit(val start: Int, val end: Int, val text: String)

    /**
     * One animation element, as the scanner found it (v2.6.0 stable: public so the 60 fps scene graph can hold it).
     */
    class Smil(
        val name: String,
        val attribute: String,
        val type: String?,
        val calcMode: String,
        val values: List<String>,
        val keyTimes: List<Float>,
        val spanStart: Int,
        val spanEnd: Int,
        val durationMs: Long,
    )

    internal class AnimatedElement(
        val name: String,
        val openStart: Int,
        val openEnd: Int,
        val smils: List<Smil>,
    )

    companion object {

        /** Never play faster than this, whatever a document claims: twenty re-renders a second is already a lot. */
        const val MAX_FPS = 10

        /**
         * The rate the Author page *displays* at, independent of the rate the source declares (v2.6.0 crash-fix 3).
         *
         * The generator emits one Pacman position every 200 ms (5 fps for a 29 s loop), which reads as a stutter.
         * Because those animations are `linear` — SMIL's default — an intermediate instant can be computed honestly
         * by interpolating between the two surrounding keyTimes, so the page samples the *same* timeline more often
         * instead of moving Pacman faster. 18 fps is inside the 15–20 the task asks for and inside what this
         * renderer can sustain: one frame costs one document rewrite plus one `AndroidSVG` parse and rasterisation.
         */
        const val SMOOTH_FPS = 18

        /** The lowest rate worth animating at. */
        const val MIN_FPS = 1

        /** Tolerance for `keyTimes` arithmetic in floating point. */
        private const val EPSILON = 1e-6f

        /** Elements this class consumes. */
        private val SMIL_NAMES = setOf("animate", "animatetransform", "set")

        /**
         * Reads [svg] and returns its animation, or `null` when the document is still.
         *
         * A document is animated only when it declares SMIL elements *and* a duration *and* at least one element
         * carries a value this renderer can sample. A document that merely mentions the word in a comment, a
         * CSS-animated document, or one whose animations cannot be expressed here returns `null`, so the caller
         * shows the still picture instead of claiming an animation that is not there.
         */
        fun detect(svg: String): SmilAnimation? {
            if (svg.isEmpty()) return null
            if (!svg.contains("<animate", ignoreCase = true)) return null
            val animated = scan(svg).filter { it.smils.isNotEmpty() }
            if (animated.isEmpty()) return null
            val duration = animated.flatMap { it.smils }.maxOfOrNull { it.durationMs } ?: 0L
            if (duration <= 0L) return null
            return SmilAnimation(
                source = svg,
                durationMs = duration,
                framesPerSecond = declaredFrameRate(svg),
                animated = animated,
                animatedElementCount = animated.size,
            )
        }

        /** True when [svg] declares animation this renderer can play. */
        fun isAnimated(svg: String): Boolean = detect(svg) != null

        /** The rate the document's own `<frameRate>` metadata asks for, clamped to [MIN_FPS]..[MAX_FPS]. */
        private fun declaredFrameRate(svg: String): Int {
            val declared = FRAME_RATE.find(svg)?.groupValues?.get(1)?.trim()?.toIntOrNull() ?: 0
            return declared.coerceIn(MIN_FPS, MAX_FPS)
        }

        /**
         * Finds every element that owns SMIL children.
         *
         * The scanner walks the document rather than matching one regular expression against it, because the
         * rewrite has to know the exact span of each element's open tag *and* of each animation child inside it.
         * It keeps a stack of open elements, so `<g>` nesting and `<defs>` are handled the same way as a top-level
         * `<rect>`, and it skips comments, CDATA and processing instructions.
         */
        private fun scan(svg: String): List<AnimatedElement> {
            val found = mutableListOf<AnimatedElement>()
            val stack = ArrayDeque<Frame>()
            var index = 0
            while (index < svg.length) {
                val open = svg.indexOf('<', index)
                if (open < 0) break
                if (svg.startsWith("<!--", open)) {
                    val end = svg.indexOf("-->", open + 4)
                    index = if (end < 0) svg.length else end + 3
                    continue
                }
                if (svg.startsWith("<![CDATA[", open)) {
                    val end = svg.indexOf("]]>", open + 9)
                    index = if (end < 0) svg.length else end + 3
                    continue
                }
                if (svg.startsWith("<?", open)) {
                    val end = svg.indexOf("?>", open + 2)
                    index = if (end < 0) svg.length else end + 2
                    continue
                }
                val close = tagEnd(svg, open)
                if (close < 0) break
                val raw = svg.substring(open, close + 1)
                val name = elementName(raw)
                if (name.isEmpty()) {
                    index = close + 1
                    continue
                }
                if (raw.startsWith("</")) {
                    // Pop to the matching open element; a stray close tag leaves the stack alone.
                    while (stack.isNotEmpty()) {
                        val frame = stack.removeLast()
                        if (frame.name.equals(name, ignoreCase = true)) {
                            if (frame.smils.isNotEmpty()) found += frame.toAnimated()
                            break
                        }
                    }
                    index = close + 1
                    continue
                }
                val lowered = name.lowercase()
                if (lowered in SMIL_NAMES) {
                    var spanEnd = close + 1
                    if (!raw.trimEnd().endsWith("/>")) {
                        val closing = indexOfClosing(svg, name, spanEnd)
                        if (closing > 0) spanEnd = closing
                    }
                    stack.lastOrNull()?.smils?.add(parseSmil(lowered, raw, open, spanEnd))
                    index = spanEnd
                    continue
                }
                if (!raw.trimEnd().endsWith("/>")) stack.addLast(Frame(name, open, close + 1))
                index = close + 1
            }
            while (stack.isNotEmpty()) {
                val frame = stack.removeLast()
                if (frame.smils.isNotEmpty()) found += frame.toAnimated()
            }
            return found
        }

        /** Index just past `</name>`, or -1 when the element is unclosed. */
        private fun indexOfClosing(svg: String, name: String, from: Int): Int {
            val match = Regex("</\\s*" + Regex.escape(name) + "\\s*>", RegexOption.IGNORE_CASE).find(svg, from)
                ?: return -1
            return match.range.last + 1
        }

        private fun parseSmil(name: String, raw: String, start: Int, end: Int): Smil {
            val attributes = attributesOf(raw)
            val values = attributes["values"]
                ?.split(';')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            val keyTimes = attributes["keytimes"]
                ?.split(';')
                ?.mapNotNull { it.trim().toFloatOrNull() }
                .orEmpty()
            return Smil(
                name = name,
                attribute = attributes["attributename"].orEmpty(),
                type = attributes["type"],
                // SMIL's default is linear; only `discrete` is treated as a step (v2.6.0 crash-fix 3).
                calcMode = attributes["calcmode"]?.trim()?.lowercase() ?: "linear",
                values = values,
                keyTimes = keyTimes,
                spanStart = start,
                spanEnd = end,
                durationMs = parseDuration(attributes["dur"]),
            )
        }

        /** `29400ms`, `29.4s` or a bare number of seconds, as milliseconds; 0 when unreadable. */
        private fun parseDuration(raw: String?): Long {
            val text = raw?.trim()?.lowercase().orEmpty()
            if (text.isEmpty() || text == "indefinite") return 0L
            val number = text.takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return 0L
            return if (text.endsWith("ms")) number.toLong() else (number * 1000.0).toLong()
        }

        /** `name="value"` pairs of one tag; enough for the attributes SMIL uses. */
        private fun attributesOf(tag: String): Map<String, String> {
            val found = mutableMapOf<String, String>()
            for (match in ATTRIBUTE.findAll(tag)) {
                found[match.groupValues[1].lowercase()] = match.groupValues[3].ifEmpty { match.groupValues[4] }
            }
            return found
        }

        /** The element name of a start or end tag, without `<` or `/`. */
        private fun elementName(raw: String): String {
            var index = if (raw.startsWith("</")) 2 else 1
            val start = index
            while (index < raw.length) {
                val c = raw[index]
                if (c.isLetterOrDigit() || c == ':' || c == '-' || c == '_' || c == '.') index++ else break
            }
            return raw.substring(start, index)
        }

        /** Index of the `>` that closes the tag starting at [open], ignoring `>` inside quoted values. */
        private fun tagEnd(svg: String, open: Int): Int {
            var quote = 0.toChar()
            var index = open + 1
            while (index < svg.length) {
                val c = svg[index]
                when {
                    quote != 0.toChar() -> if (c == quote) quote = 0.toChar()
                    c == '"' || c == '\'' -> quote = c
                    c == '>' -> return index
                }
                index++
            }
            return -1
        }

        private class Frame(val name: String, val openStart: Int, val openEnd: Int) {
            val smils = mutableListOf<Smil>()

            fun toAnimated() = AnimatedElement(name, openStart, openEnd, smils.toList())
        }

        private val FRAME_RATE = Regex("<frameRate>\\s*(\\d+)\\s*</frameRate>", RegexOption.IGNORE_CASE)

        private val ATTRIBUTE = Regex("([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")
    }
}
