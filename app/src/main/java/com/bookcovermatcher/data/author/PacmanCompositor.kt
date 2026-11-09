// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.data.author

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Base64
import com.bookcovermatcher.core.author.SmilTimeline
import com.caverock.androidsvg.SVG

/**
 * Draws one frame of the contribution graph in about 0.04 ms (v2.6.0 stable).
 *
 * `AndroidSVG` cannot animate: a frame costs 77 ms — 16.5 ms to parse the 85 KB document and 70.6 ms to rasterise it —
 * against the 16.7 ms a 60 fps display allows. So the document is split once, at load time:
 *
 *  * the **scenery** (canvas, 371 cells, maze walls, month labels) is rasterised once by `AndroidSVG`;
 *  * every **ghost direction sprite** is a 16×16 PNG inside a `<symbol>`, decoded once and scaled once;
 *  * every **Pacman mouth shape** is a path, pre-rasterised once per (shape, colour) pair by the same renderer, so the
 *    geometry stays `AndroidSVG`'s rather than a hand-written re-interpretation of the path data;
 *  * a frame is then a blit, at most twenty rounded rectangles, four sprite blits and one rotated blit.
 *
 * Nothing is allocated per frame and nothing is parsed per frame; the bitmaps belong to this object, so leaving the
 * screen frees them.
 */
class PacmanCompositor private constructor(
    val width: Int,
    val height: Int,
    private val timeline: SmilTimeline,
    private val base: Bitmap,
    /** Symbol id → that sprite at display size. */
    private val sprites: Map<String, Bitmap>,
    /** "shapeHash|colour" → Pacman at display size. */
    private val pacmanVariants: Map<String, Bitmap>,
    /** Document units → pixels. */
    private val scale: Float,
) {

    private val flashPaint = Paint().apply { color = FLASH_COLOUR; isAntiAlias = true }
    private val spritePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    /**
     * Two reusable frame buffers, drawn into alternately.
     *
     * Alternating matters: Compose compares the `ImageBitmap` it is given, so handing back the *same* bitmap instance
     * would leave the previous frame on screen even though its pixels had changed. Two buffers also mean the bitmap
     * being drawn into is never the one currently on screen.
     */
    private val buffers = arrayOf(
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888),
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888),
    )
    private val canvases = arrayOf(Canvas(buffers[0]), Canvas(buffers[1]))
    private var next = 0

    /** Draws the frame at [instantMs] into the next buffer and returns it, ready to be shown. */
    fun nextFrame(instantMs: Long): Bitmap {
        next = 1 - next
        draw(canvases[next], instantMs)
        return buffers[next]
    }

    /** The scene at [instantMs], without drawing it. */
    fun sceneAt(instantMs: Long): SmilTimeline.Scene = timeline.sceneAt(instantMs)

    /**
     * A cheap key for what a frame would look like.
     *
     * A vsync tick that produces the same key draws nothing, so a still stretch of the timeline never costs a redraw.
     */
    fun frameKey(instantMs: Long): String {
        val scene = timeline.sceneAt(instantMs)
        val key = StringBuilder(64)
        for (ghost in scene.ghosts) {
            key.append((ghost.x * 4).toInt()).append(',').append((ghost.y * 4).toInt()).append(ghost.symbol)
        }
        val pacman = scene.pacman
        key.append((pacman.x * 4).toInt()).append(',').append((pacman.y * 4).toInt())
        key.append(pacman.rotationDegrees.toInt()).append(pacman.fill).append(pacman.pathData.hashCode())
        key.append(scene.litFlashes.size)
        return key.toString()
    }

    /** Draws the frame at [instantMs] into [canvas], which must be [width] × [height]. */
    fun draw(canvas: Canvas, instantMs: Long) {
        // The scenery first: it is opaque and covers the whole picture, so nothing needs clearing.
        canvas.drawBitmap(base, 0f, 0f, null)
        val scene = timeline.sceneAt(instantMs)

        // Cells Pacman has not eaten yet are the eaten-green of the generator's own ramp.
        for (flash in scene.litFlashes) {
            canvas.drawRoundRect(
                flash.x * scale,
                flash.y * scale,
                (flash.x + flash.width) * scale,
                (flash.y + flash.height) * scale,
                flash.radius * scale,
                flash.radius * scale,
                flashPaint,
            )
        }

        // Ghosts: a translation and, at most, a sprite swap.
        for (ghost in scene.ghosts) {
            val sprite = sprites[ghost.symbol] ?: continue
            canvas.drawBitmap(sprite, ghost.x * scale, ghost.y * scale, spritePaint)
        }

        // Pacman: a translation, a quarter-turn about the middle of his cell, and one of his mouth shapes.
        val pacman = scene.pacman
        val variant = pacmanVariant(pacman) ?: return
        canvas.save()
        canvas.translate(pacman.x * scale, pacman.y * scale)
        if (pacman.rotationDegrees != 0f) {
            canvas.rotate(pacman.rotationDegrees, pacman.rotationCentreX * scale, pacman.rotationCentreY * scale)
        }
        canvas.drawBitmap(variant, 0f, 0f, spritePaint)
        canvas.restore()
    }

    /** Pacman's bitmap for this instant, or null when the document gave no mouth geometry. */
    private fun pacmanVariant(pacman: SmilTimeline.PacmanState): Bitmap? {
        if (pacman.pathData.isBlank()) return pacmanVariants.values.firstOrNull()
        val key = pacman.pathData.trim().hashCode()
        return pacmanVariants[key.toString() + "|" + pacman.fill]
            ?: pacmanVariants.entries.firstOrNull { it.key.startsWith(key.toString() + "|") }?.value
            ?: pacmanVariants.values.firstOrNull()
    }

    /** Releases the bitmaps this compositor owns. */
    fun release() {
        // The frame buffers are not recycled: Compose may still be holding one when the composition goes away, and a
        // recycled bitmap in a draw call is a crash. They are collected normally instead.
        if (!base.isRecycled) base.recycle()
        sprites.values.forEach { if (!it.isRecycled) it.recycle() }
        pacmanVariants.values.forEach { if (!it.isRecycled) it.recycle() }
    }

    companion object {

        /** The generator's eaten-green, `#0e4429` from the dark ramp. */
        private const val FLASH_COLOUR = 0xFF0E4429.toInt()

        /** A 20-unit sprite: the size of a day cell, and therefore of Pacman and every ghost. */
        private const val SPRITE_UNITS = 20

        /** A `<symbol>` whose content is a base64 PNG sprite. */
        private val SPRITE_SYMBOL = Regex(
            "<symbol\\b[^>]*\\bid\\s*=\\s*\"([^\"]+)\"[^>]*>[\\s\\S]*?<image\\b[^>]*data:image/png;base64,([^\"]+)\"",
            RegexOption.IGNORE_CASE,
        )

        private val ROOT_TAG = Regex("<svg\\b[^>]*>", RegexOption.IGNORE_CASE)

        /**
         * Builds a compositor for [document], or null when the document cannot be split.
         *
         * [maxWidth] is the width in pixels the picture is drawn at; the scenery and every sprite are rendered at
         * exactly that size, once. A null result means the caller keeps the whole-document renderer.
         */
        fun build(document: String, maxWidth: Int): PacmanCompositor? {
            val timeline = SmilTimeline.detect(document) ?: return null
            val root = ROOT_TAG.find(document)?.value ?: return null
            val documentWidth = length(root, "width") ?: return null
            val documentHeight = length(root, "height") ?: return null
            if (documentWidth <= 0f || documentHeight <= 0f) return null

            val width = maxWidth.coerceIn(64, 4_096)
            val height = (width * documentHeight / documentWidth).toInt().coerceAtLeast(1)
            val scale = width / documentWidth

            val base = render(timeline.baseDocument, width, height) ?: return null
            return PacmanCompositor(
                width = width,
                height = height,
                timeline = timeline,
                base = base,
                sprites = decodeSprites(document, scale),
                pacmanVariants = rasterisePacman(timeline, scale),
                scale = scale,
            )
        }

        /** Rasterises [document] at exactly [width] × [height]. */
        private fun render(document: String, width: Int, height: Int): Bitmap? = runCatching {
            val svg = SVG.getFromString(document)
            svg.setDocumentWidth(width.toFloat())
            svg.setDocumentHeight(height.toFloat())
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            svg.renderToCanvas(Canvas(bitmap))
            bitmap
        }.getOrNull()

        /** Every `<symbol>`'s PNG, decoded once and scaled to the size a sprite is drawn at. */
        private fun decodeSprites(document: String, scale: Float): Map<String, Bitmap> {
            val size = (SPRITE_UNITS * scale).toInt().coerceAtLeast(1)
            val out = HashMap<String, Bitmap>()
            for (match in SPRITE_SYMBOL.findAll(document)) {
                val id = match.groupValues[1]
                val bytes = runCatching { Base64.decode(match.groupValues[2], Base64.DEFAULT) }.getOrNull()
                    ?: continue
                val decoded = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                    ?: continue
                out[id] = if (decoded.width == size && decoded.height == size) {
                    decoded
                } else {
                    Bitmap.createScaledBitmap(decoded, size, size, true).also { decoded.recycle() }
                }
            }
            return out
        }

        /**
         * Pacman's mouth shapes, rendered by `AndroidSVG` from the document's own path data.
         *
         * Each distinct `d` value becomes a 20×20 document rendered once at the display scale, so the geometry is the
         * renderer's and matches the still picture exactly.
         */
        private fun rasterisePacman(timeline: SmilTimeline, scale: Float): Map<String, Bitmap> {
            val pacman = timeline.movers.firstOrNull { it.kind == SmilTimeline.Kind.PACMAN } ?: return emptyMap()
            val shapes = pacman.shape?.values?.map { it.trim() }?.distinct() ?: return emptyMap()
            val colours = pacman.fill?.values?.map { it.trim() }?.distinct() ?: listOf("#ffff00")
            val size = (SPRITE_UNITS * scale).toInt().coerceAtLeast(1)
            val out = HashMap<String, Bitmap>()
            for (shape in shapes) {
                val key = shape.hashCode().toString()
                for (colour in colours) {
                    val document = "<svg xmlns=\"http://www.w3.org/2000/svg\" " +
                        "width=\"$SPRITE_UNITS\" height=\"$SPRITE_UNITS\" " +
                        "viewBox=\"0 0 $SPRITE_UNITS $SPRITE_UNITS\">" +
                        "<path d=\"" + escape(shape) + "\" fill=\"" + escape(colour) + "\"/></svg>"
                    val bitmap = render(document, size, size) ?: continue
                    out[key + "|" + colour] = bitmap
                }
            }
            return out
        }

        /** XML-escapes a value that is about to be written into an attribute. */
        private fun escape(value: String): String = value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        /** A numeric attribute of the root tag. */
        private fun length(root: String, name: String): Float? =
            Regex("\\b" + Regex.escape(name) + "\\s*=\\s*\"([0-9.]+)\"").find(root)
                ?.groupValues?.get(1)?.toFloatOrNull()
    }
}
