// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.author

/**
 * The contribution graph as something that can be drawn 60 times a second (v2.6.0 stable).
 *
 * Re-rendering the whole SVG per frame cannot reach 60 fps: measured on the emulator, one frame of the production
 * path costs **77 ms** (16.5 ms parsing the 85 KB document, 70.6 ms rasterising it) against a 16.7 ms budget. But
 * almost nothing in the picture actually moves. This class splits the document into the two things it really is:
 *
 *  * **the scenery** — canvas, 371 day cells, the maze walls and the month labels. Constant for the whole loop,
 *    rendered once by `AndroidSVG`;
 *  * **five movers** — Pacman and four ghosts, whose only continuous property is a translation;
 *  * **twenty flashing cells**, whose colour steps from eaten-green to empty once each, at a keyTime the generator
 *    declares.
 *
 * Everything else about the movers is *discrete*: which direction sprite a ghost shows, Pacman's mouth shape (a
 * separate 0.5 s cycle), the quarter-turn rotation and the red "eaten" tint. A frame is therefore one blit plus four
 * sprite blits plus one path — measured at **0.04 ms**, which leaves the whole 16.7 ms budget for presentation.
 *
 * This is the pure, JVM-testable half: it parses the document and answers "what does the scene look like at instant
 * *t*". It draws nothing; the Android half is `PacmanCompositor`.
 */
class SmilTimeline private constructor(
    private val sampler: SmilAnimation,
    val baseDocument: String,
    val movers: List<Mover>,
    val flashes: List<Flash>,
) {

    /** The loop length the generator declares, e.g. 29 000 ms. */
    val durationMs: Long = sampler.durationMs

    /** What a mover is, which decides how it is drawn. */
    enum class Kind { GHOST, PACMAN }

    /**
     * One moving element.
     *
     * [translate] is the only property sampled continuously; the rest are discrete by construction of the source, so
     * sampling them per frame is a table lookup rather than an interpolation.
     */
    class Mover(
        val id: String,
        val kind: Kind,
        val translate: SmilAnimation.Smil?,
        /** Pacman only: `rotate(angle cx cy)`, `calcMode="discrete"`. */
        val rotate: SmilAnimation.Smil?,
        /** Pacman only: `fill`, yellow except while he is eating. */
        val fill: SmilAnimation.Smil?,
        /** Pacman only: the `d` mouth shapes, a 0.5 s cycle of its own. */
        val shape: SmilAnimation.Smil?,
        /** Ghosts only: the direction sprites, of which exactly one is visible at a time. */
        val sprites: List<GhostSprite>,
    )

    /** One direction sprite of a ghost: a `<use href="#symbol">` with its own visibility animation. */
    class GhostSprite(val symbol: String, val visibility: SmilAnimation.Smil?)

    /**
     * A cell that steps from eaten-green to empty at [switchAt], a fraction of the loop.
     *
     * The generator writes `values="#0e4429;#161b22;#161b22"` with `keyTimes="0;T;1"`, so the cell is green *before*
     * T and empty afterwards: Pacman eats the dot and it is gone.
     */
    class Flash(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val radius: Float,
        val switchAt: Float,
    )

    /** The state of every mover and flash at one instant. */
    class Scene(
        val ghosts: List<GhostState>,
        val pacman: PacmanState,
        val litFlashes: List<Flash>,
    )

    /** A ghost at one instant: where its group is translated to, and which sprite it shows. */
    class GhostState(val x: Float, val y: Float, val symbol: String)

    /** Pacman at one instant. */
    class PacmanState(
        val x: Float,
        val y: Float,
        val rotationDegrees: Float,
        val rotationCentreX: Float,
        val rotationCentreY: Float,
        val fill: String,
        val pathData: String,
    )

    /**
     * The scene at [instantMs] of the loop.
     *
     * Each animation is sampled against *its own* declared duration, not against the loop: Pacman's mouth runs on a
     * 0.5 s cycle inside a 29 s loop, and sampling it against the loop (as the frame-document path did) froze it on its
     * first shape for the first 14.5 s.
     */
    fun sceneAt(instantMs: Long): Scene {
        val loop = ((instantMs % durationMs) + durationMs) % durationMs
        val loopProgress = loop.toFloat() / durationMs.toFloat()
        val ghosts = ArrayList<GhostState>(movers.size)
        var pacman: PacmanState? = null
        for (mover in movers) {
            val position = translateAt(mover, loop)
            when (mover.kind) {
                Kind.GHOST -> {
                    val symbol = mover.sprites.firstOrNull { sprite ->
                        sample(sprite.visibility, loop).equals("visible", ignoreCase = true)
                    }?.symbol ?: mover.sprites.firstOrNull()?.symbol.orEmpty()
                    ghosts += GhostState(position[0], position[1], symbol)
                }
                Kind.PACMAN -> {
                    val rotation = rotationAt(mover.rotate, loop)
                    pacman = PacmanState(
                        x = position[0],
                        y = position[1],
                        rotationDegrees = rotation[0],
                        rotationCentreX = rotation[1],
                        rotationCentreY = rotation[2],
                        fill = sample(mover.fill, loop) ?: "#ffff00",
                        pathData = sample(mover.shape, loop) ?: "",
                    )
                }
            }
        }
        return Scene(
            ghosts = ghosts,
            pacman = pacman ?: PacmanState(0f, 0f, 0f, 10f, 10f, "#ffff00", ""),
            litFlashes = flashes.filter { loopProgress < it.switchAt },
        )
    }

    /** The element's own progress inside its own duration, so a 0.5 s cycle stays a 0.5 s cycle. */
    private fun progressOf(smil: SmilAnimation.Smil?, loopMs: Long): Float {
        if (smil == null) return 0f
        val duration = if (smil.durationMs > 0L) smil.durationMs else durationMs
        return (((loopMs % duration) + duration) % duration).toFloat() / duration.toFloat()
    }

    /** Samples [smil] at [instantMs] of its own timeline. */
    private fun sample(smil: SmilAnimation.Smil?, instantMs: Long): String? =
        smil?.let { sampler.sampleValue(it, progressOf(it, instantMs)) }

    /** `(x, y)` of a translate animation, interpolated between the two surrounding keys. */
    private fun translateAt(mover: Mover, loopMs: Long): FloatArray {
        val smil = mover.translate ?: return floatArrayOf(0f, 0f)
        val value = sampler.sampleValue(smil, progressOf(smil, loopMs)) ?: return floatArrayOf(0f, 0f)
        val parts = numbers(value)
        return when {
            parts.size >= 2 -> floatArrayOf(parts[0], parts[1])
            parts.size == 1 -> floatArrayOf(parts[0], 0f)
            else -> floatArrayOf(0f, 0f)
        }
    }

    /** `(angle, cx, cy)` of a rotate animation; the centre defaults to the middle of a 20-unit cell. */
    private fun rotationAt(smil: SmilAnimation.Smil?, loopMs: Long): FloatArray {
        val value = sample(smil, loopMs) ?: return floatArrayOf(0f, 10f, 10f)
        val parts = numbers(value)
        return when {
            parts.size >= 3 -> floatArrayOf(parts[0], parts[1], parts[2])
            parts.isNotEmpty() -> floatArrayOf(parts[0], 10f, 10f)
            else -> floatArrayOf(0f, 10f, 10f)
        }
    }

    /** The numbers in an attribute value, in order. */
    private fun numbers(value: String): List<Float> =
        Regex("-?[0-9]*\\.?[0-9]+").findAll(value).map { it.value.toFloat() }.toList()

    companion object {

        /** A ghost group: `<g id="ghost0" transform="translate(...)">`. */
        private val GHOST_GROUP = Regex("<g\\b[^>]*\\bid\\s*=\\s*\"(ghost\\d+)\"[^>]*>", RegexOption.IGNORE_CASE)

        /** The Pacman path. */
        private val PACMAN = Regex("<path\\b[^>]*\\bid\\s*=\\s*\"pacman\"[^>]*>", RegexOption.IGNORE_CASE)

        /** A day cell, which flashes when Pacman eats it. */
        private val CELL = Regex("<rect\\b[^>]*\\bid\\s*=\\s*\"c-\\d+-\\d+\"[^>]*>", RegexOption.IGNORE_CASE)

        /** `href="#symbol"`, with or without the `xlink:` prefix. */
        private val HREF = Regex("(?:xlink:)?href\\s*=\\s*\"#([^\"]+)\"", RegexOption.IGNORE_CASE)

        /**
         * Reads [svg] and returns its scene graph, or `null` when the document is not an animated contribution graph.
         *
         * A document that does not parse into scenery plus at least one mover returns `null`, and the caller keeps the
         * whole-document renderer, so an unexpected SVG degrades to the previous behaviour instead of drawing nothing.
         */
        fun detect(svg: String): SmilTimeline? {
            val animation = SmilAnimation.detect(svg) ?: return null
            val elements = animation.elements()
            if (elements.isEmpty()) return null

            val movers = mutableListOf<Mover>()
            val moverSpans = mutableListOf<IntRange>()

            for (group in GHOST_GROUP.findAll(svg)) {
                val span = elementSpan(svg, group.range.first) ?: continue
                val own = elements.filter { it.openStart in span }
                val translate = own.flatMap { it.smils }.firstOrNull {
                    it.name.equals("animatetransform", ignoreCase = true) &&
                        it.type.equals("translate", ignoreCase = true)
                } ?: continue
                val sprites = own
                    .filter { it.name.equals("use", ignoreCase = true) }
                    .mapNotNull { use ->
                        val tag = svg.substring(use.openStart, use.openEnd)
                        val symbol = HREF.find(tag)?.groupValues?.get(1) ?: return@mapNotNull null
                        GhostSprite(
                            symbol = symbol,
                            visibility = use.smils.firstOrNull {
                                it.attribute.equals("visibility", ignoreCase = true)
                            },
                        )
                    }
                if (sprites.isEmpty()) continue
                movers += Mover(group.groupValues[1], Kind.GHOST, translate, null, null, null, sprites)
                moverSpans += span
            }

            PACMAN.find(svg)?.let { match ->
                val span = elementSpan(svg, match.range.first)
                if (span != null) {
                    val own = elements.filter { it.openStart in span }.flatMap { it.smils }
                    val translate = own.firstOrNull {
                        it.name.equals("animatetransform", ignoreCase = true) &&
                            it.type.equals("translate", ignoreCase = true)
                    }
                    if (translate != null) {
                        movers += Mover(
                            id = "pacman",
                            kind = Kind.PACMAN,
                            translate = translate,
                            rotate = own.firstOrNull {
                                it.name.equals("animatetransform", ignoreCase = true) &&
                                    it.type.equals("rotate", ignoreCase = true)
                            },
                            fill = own.firstOrNull { it.attribute.equals("fill", ignoreCase = true) },
                            shape = own.firstOrNull { it.attribute.equals("d", ignoreCase = true) },
                            sprites = emptyList(),
                        )
                        moverSpans += span
                    }
                }
            }

            if (movers.isEmpty()) return null

            val flashes = mutableListOf<Flash>()
            for (cell in CELL.findAll(svg)) {
                val span = elementSpan(svg, cell.range.first) ?: continue
                val fill = elements.filter { it.openStart in span }
                    .flatMap { it.smils }
                    .firstOrNull { it.attribute.equals("fill", ignoreCase = true) } ?: continue
                val tag = cell.value
                flashes += Flash(
                    x = attribute(tag, "x") ?: 0f,
                    y = attribute(tag, "y") ?: 0f,
                    width = attribute(tag, "width") ?: 20f,
                    height = attribute(tag, "height") ?: 20f,
                    radius = attribute(tag, "rx") ?: 0f,
                    switchAt = fill.keyTimes.getOrNull(1) ?: 1f,
                )
            }

            // Every animation goes: the scenery is the document's own initial state, which is exactly what a still
            // picture of the graph should be — cells at their base colour, no sprite parked in a corner.
            val removals = moverSpans + elements.flatMap { element ->
                element.smils.map { it.spanStart until it.spanEnd }
            }
            return SmilTimeline(
                sampler = animation,
                baseDocument = strip(svg, removals),
                movers = movers,
                flashes = flashes,
            )
        }

        /** The span of the element whose open tag starts at [openStart], children included. */
        private fun elementSpan(svg: String, openStart: Int): IntRange? {
            val openEnd = svg.indexOf('>', openStart).takeIf { it >= 0 } ?: return null
            val openTag = svg.substring(openStart, openEnd + 1)
            if (openTag.trimEnd().endsWith("/>")) return openStart..openEnd
            val name = Regex("<([A-Za-z][A-Za-z0-9]*)").find(openTag)?.groupValues?.get(1) ?: return null
            val open = Regex("<" + name + "\\b[^>]*>", RegexOption.IGNORE_CASE)
            val close = Regex("</" + name + "\\s*>", RegexOption.IGNORE_CASE)
            var depth = 1
            var index = openEnd + 1
            while (index < svg.length) {
                val nextOpen = open.find(svg, index)
                val nextClose = close.find(svg, index) ?: return null
                if (nextOpen != null && nextOpen.range.first < nextClose.range.first) {
                    if (!nextOpen.value.trimEnd().endsWith("/>")) depth++
                    index = nextOpen.range.last + 1
                } else {
                    depth--
                    if (depth == 0) return openStart..nextClose.range.last
                    index = nextClose.range.last + 1
                }
            }
            return null
        }

        /**
         * [svg] with every span in [spans] cut out, outermost spans only.
         *
         * A mover element *contains* the `<animate>` children that are removed with it, so the two spans overlap by
         * construction. Cutting the children first and then treating the parent as an overlap left the parent in the
         * document — and a ghost group left in the scenery is drawn at its static `transform="translate(0,0)"`, which
         * is the sprite that appeared stuck in the top-left corner of the graph (2.6.0). Keeping only the maximal
         * spans removes the element once, children and all, whatever nests inside it.
         */
        private fun strip(svg: String, spans: List<IntRange>): String {
            val ordered = spans.filter { it.first <= it.last && it.last < svg.length }.sortedBy { it.first }
            val maximal = ArrayList<IntRange>(ordered.size)
            for (span in ordered) {
                // A span that starts inside the previous one is nested in it: the parent's cut already removes it.
                if (maximal.isNotEmpty() && span.first <= maximal.last().last) continue
                maximal += span
            }
            val out = StringBuilder(svg)
            for (span in maximal.sortedByDescending { it.first }) out.delete(span.first, span.last + 1)
            return out.toString()
        }

        /** A numeric attribute of an open tag. */
        private fun attribute(tag: String, name: String): Float? =
            Regex("\\b" + Regex.escape(name) + "\\s*=\\s*\"(-?[0-9.]+)\"").find(tag)
                ?.groupValues?.get(1)?.toFloatOrNull()
    }
}
