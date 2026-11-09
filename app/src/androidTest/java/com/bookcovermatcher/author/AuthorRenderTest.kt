// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.author

import android.graphics.Bitmap
import android.graphics.Canvas
import com.bookcovermatcher.core.author.SmilAnimation
import com.bookcovermatcher.core.author.SvgCanvas
import com.bookcovermatcher.core.author.SvgEmbeddedImages
import com.bookcovermatcher.data.author.AuthorImageDecoder
import com.caverock.androidsvg.SVG
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Renders the real assets with the real renderer, on a device (v2.6.0 crash-fix 2).
 *
 * A parser test that finds `<svg>` proves nothing about what a user sees, and a generated-string comparison proves
 * nothing about AndroidSVG. These tests rasterise the documents with `AndroidSVG` itself and then count pixels in
 * the region where the picture is supposed to be, so "the icon is visible" and "the graph changes" are measured
 * facts rather than inference.
 */
@RunWith(AndroidJUnit4::class)
class AuthorRenderTest {

    /** Detects a root that already carries a viewBox, so the canvas is not scaled twice. */
    private val VIEW_BOX = Regex("<svg\\b[^>]*viewBox", RegexOption.IGNORE_CASE)

    /** The generator's dark canvas, `#0D1117`, and the Author page's navy panel, `#062442`. */
    private val CANVAS = intArrayOf(0x0D, 0x11, 0x17)
    private val PANEL = intArrayOf(0x06, 0x24, 0x42)

    private fun fixture(name: String): String {
        val context = InstrumentationRegistry.getInstrumentation().context
        return context.assets.open("author/$name").bufferedReader().use { it.readText() }
    }

    /** Rasterises [document] at [scale]Ã— its intrinsic size, exactly as the app does. */
    private fun rasterise(document: String, scale: Float = 1f): Bitmap {
        // The canvas is normalised exactly as AuthorImageDecoder does; whether the embedded icons are prepared is the
        // caller's business, because one test must render the document *unprepared* to prove the difference.
        val prepared = SvgCanvas.normalise(document)
        val svg = SVG.getFromString(prepared)
        val intrinsicWidth = svg.documentWidth.takeIf { it > 0f } ?: 320f
        val intrinsicHeight = svg.documentHeight.takeIf { it > 0f } ?: 120f
        val width = (intrinsicWidth * scale).toInt().coerceAtLeast(1)
        val height = (intrinsicHeight * scale).toInt().coerceAtLeast(1)
        svg.setDocumentWidth(width.toFloat())
        svg.setDocumentHeight(height.toFloat())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (!VIEW_BOX.containsMatchIn(prepared)) {
            // A document without a viewBox draws itself 1:1, so the canvas must be scaled explicitly or the
            // right/bottom of the picture is cropped; one with a viewBox is mapped by the renderer instead.
            canvas.scale(width / intrinsicWidth, height / intrinsicHeight)
        }
        svg.renderToCanvas(canvas)
        return bitmap
    }

    /** The contribution graph the README's dark `<source>` points at, ready to animate. */
    private fun darkGraphAnimation(): SmilAnimation = requireNotNull(
        SmilAnimation.detect(SvgEmbeddedImages.prepare(fixture("pacman-contribution-graph-dark.svg"))),
    ) { "the dark graph must be recognised as animated" }

    /** How many pixels in a box are near-white â€” the colour a badge icon is drawn in. */
    private fun whitePixels(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Int {
        var count = 0
        for (y in top until minOf(bottom, bitmap.height)) {
            for (x in left until minOf(right, bitmap.width)) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                if (r > 200 && g > 200 && b > 200) count++
            }
        }
        return count
    }

    /** How many pixels differ between two same-sized bitmaps. */
    private fun differing(a: Bitmap, b: Bitmap): Int {
        if (a.width != b.width || a.height != b.height) return Int.MAX_VALUE
        var count = 0
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) count++
            }
        }
        return count
    }

    /** The largest per-channel difference between two ARGB pixels. */
    private fun differs(pixel: Int, reference: Int): Int {
        val dr = Math.abs(((pixel shr 16) and 0xFF) - ((reference shr 16) and 0xFF))
        val dg = Math.abs(((pixel shr 8) and 0xFF) - ((reference shr 8) and 0xFF))
        val db = Math.abs((pixel and 0xFF) - (reference and 0xFF))
        return maxOf(dr, dg, db)
    }

    @Test fun theJavaBadgeIconIsBlankWithoutPreparationAndDrawnWithIt() {
        val raw = fixture("badge-java.svg")
        val scale = 6f
        // The badge is 53Ã—20; its icon box is x 5..19, y 3..17 in the document's own units.
        val left = (5 * scale).toInt()
        val top = (3 * scale).toInt()
        val right = (19 * scale).toInt()
        val bottom = (17 * scale).toInt()

        val before = rasterise(raw, scale)
        val after = rasterise(SvgEmbeddedImages.prepare(raw), scale)

        val blank = whitePixels(before, left, top, right, bottom)
        val drawn = whitePixels(after, left, top, right, bottom)

        // The defect, measured: the icon's own box holds nothing but the badge's background.
        assertTrue("the unprepared render must leave the icon box blank, found $blank", blank == 0)
        // The fix, measured: the icon's pixels are there.
        assertTrue("the prepared render must draw the icon, found $drawn", drawn > 100)
        // And the badge around it is unchanged: the same text is still white in both.
        val textLeft = (24 * scale).toInt()
        assertTrue(
            "the badge text must render before and after",
            whitePixels(before, textLeft, 0, before.width, before.height) > 100 &&
                whitePixels(after, textLeft, 0, after.width, after.height) > 100,
        )
    }

    @Test fun everyIconBearingBadgeInTheReadmeDrawsItsIcon() {
        for (name in listOf("badge-java.svg", "badge-kotlin.svg", "badge-git.svg")) {
            val prepared = SvgEmbeddedImages.prepare(fixture(name))
            val bitmap = rasterise(prepared, 6f)
            // The badge background is sampled just outside the icon's column, so the check works whatever colour
            // the icon is drawn in: Java's is white, Kotlin's is #7F52FF, Git's is white.
            val background = bitmap.getPixel(1, bitmap.height / 2)
            val right = (bitmap.width * 0.30f).toInt()
            val bottom = (bitmap.height * 0.85f).toInt()
            var iconPixels = 0
            for (y in 0 until bottom) {
                for (x in 0 until right) {
                    val pixel = bitmap.getPixel(x, y)
                    if (differs(pixel, background) > 60) iconPixels++
                }
            }
            assertTrue("$name: the icon must be drawn, found $iconPixels icon pixels", iconPixels > 100)
        }
    }

    @Test fun theContributionGraphRendersDifferentPicturesOverItsTimeline() {
        val animation = SmilAnimation.detect(SvgEmbeddedImages.prepare(fixture("pacman-contribution-graph.svg")))
        assertTrue("the graph must be recognised as animated", animation != null)
        animation!!

        val first = rasterise(animation.frameDocument(0))
        val later = rasterise(animation.frameDocument(animation.durationMs / 2))
        val differingPixels = differing(first, later)

        // A still picture would give 0. The graph changes cells and moves Pacman, so this is thousands of pixels.
        assertTrue("the rendered frames must differ, found $differingPixels", differingPixels > 1_000)
        assertTrue("the canvas must keep its size", first.width == later.width && first.height == later.height)
    }

    @Test fun consecutiveFramesOfTheLoopAlsoDiffer() {
        val animation = SmilAnimation.detect(SvgEmbeddedImages.prepare(fixture("pacman-contribution-graph.svg")))!!
        val step = 1_000L / animation.framesPerSecond
        val a = rasterise(animation.frameDocument(step * 3))
        val b = rasterise(animation.frameDocument(step * 4))
        assertTrue("one frame apart must differ too", differing(a, b) > 100)
    }


    /** The prepared badge must fill the bitmap it was asked for, not be drawn 1:1 and cropped. */
    @Test fun thePreparedJavaBadgeIsNotCroppedAndKeepsItsSize() {
        val prepared = SvgEmbeddedImages.prepare(fixture("badge-java.svg"))
        val native = rasterise(prepared, 1f)
        val scaled = rasterise(prepared, 8f)
        assertTrue("the badge keeps its declared size", native.width == 53 && native.height == 20)
        assertTrue("a scaled render keeps the ratio", scaled.width == 53 * 8 && scaled.height == 20 * 8)
        val centre = scaled.getPixel(scaled.width / 2, scaled.height / 2)
        assertTrue("the scaled render must be drawn, not transparent", ((centre ushr 24) and 0xFF) > 200)
        // Nothing may be cut off either: with a viewBox normalised in, the renderer maps the badge onto the whole
        // bitmap. If the canvas were scaled as well the content would be ~2.6× too large and the right half of the
        // badge would fall outside the picture, leaving a transparent right edge (v2.6.0 crash-fix 4).
        val leftEdge = scaled.getPixel(2, scaled.height / 2)
        val rightEdge = scaled.getPixel(scaled.width - 3, scaled.height / 2)
        assertTrue("the left edge must be drawn", ((leftEdge ushr 24) and 0xFF) > 200)
        assertTrue("the right edge must be drawn", ((rightEdge ushr 24) and 0xFF) > 200)
        var opaque = 0
        for (y in 0 until scaled.height) {
            for (x in 0 until scaled.width) {
                if (((scaled.getPixel(x, y) ushr 24) and 0xFF) > 200) opaque++
            }
        }
        val total = scaled.width * scaled.height
        assertTrue("the badge must fill its bitmap, not a corner of it: $opaque of $total", opaque > total * 95 / 100)
    }

    /**
     * The icon is drawn from a bitmap that is at least as large as the pixels it occupies (v2.6.0 crash-fix 3).
     *
     * Before the fix every vector was rasterised at most at its intrinsic size, so a 53×20 badge became a 53×20
     * bitmap drawn at 139×52 px on a 2.625× display — a 2.6× enlargement of a small bitmap, which is exactly what
     * "soft icons" looks like. The production decoder is exercised here, not a copy of its arithmetic.
     */
    @Test fun theBadgeIsRasterisedAtTheSizeItIsDrawn() {
        val bytes = badgeBytes("badge-java.svg")
        val intrinsic = requireNotNull(AuthorImageDecoder.decode(bytes, density = 1f))
        val onScreen = requireNotNull(AuthorImageDecoder.decode(bytes, density = 2.625f))

        assertEquals("intrinsic size is the document's own 53×20", 53, intrinsic.bitmap.width)
        assertEquals(20, intrinsic.bitmap.height)
        // 20 dp at 2.625× is 52.5 px, and 53 units wide becomes 139 px: the bitmap now matches the drawn size.
        assertTrue("the raster must be tall enough for 20 dp: ${onScreen.bitmap.height}", onScreen.bitmap.height >= 52)
        assertTrue("and wide enough for its aspect: ${onScreen.bitmap.width}", onScreen.bitmap.width >= 139)
        assertEquals(53f / 20f, onScreen.bitmap.width.toFloat() / onScreen.bitmap.height, 0.06f)
    }

    @Test fun theDensityRenderedIconIsSharperThanAnEnlargedSmallOne() {
        val bytes = badgeBytes("badge-java.svg")
        val small = requireNotNull(AuthorImageDecoder.decode(bytes, density = 1f))
        val direct = requireNotNull(AuthorImageDecoder.decode(bytes, density = 2.625f))
        // What the compositor used to do: enlarge the 53×20 bitmap to the size on screen, with filtering.
        val enlarged = Bitmap.createScaledBitmap(small.bitmap, direct.bitmap.width, direct.bitmap.height, true)

        val blurFromEnlarging = intermediatePixels(enlarged)
        val blurFromRendering = intermediatePixels(direct.bitmap)
        assertTrue(
            "rendering the vector at the display size must be sharper than enlarging a small bitmap: " +
                "enlarged=$blurFromEnlarging direct=$blurFromRendering",
            blurFromRendering < blurFromEnlarging,
        )
    }

    /**
     * The graph uses its own dark palette, with the light labels that belong to it (v2.6.0 crash-fix 3).
     *
     * The document is the one the README's `prefers-color-scheme: dark` source points at, so the background, the
     * labels and the contribution ramp are the generator's own dark theme rather than a recolour of the light one.
     */
    @Test fun theDarkGraphIsDarkWithLegibleLabelsAtEveryInstant() {
        val animation = SmilAnimation.detect(
            SvgEmbeddedImages.prepare(fixture("pacman-contribution-graph-dark.svg")),
        )
        assertTrue("the dark graph must be recognised as animated", animation != null)
        animation!!
        for (instant in listOf(0L, animation.durationMs / 3, animation.durationMs - 1)) {
            val bitmap = rasterise(animation.frameDocument(instant))
            val corner = bitmap.getPixel(2, 2)
            val r = (corner shr 16) and 0xFF
            val g = (corner shr 8) and 0xFF
            val b = corner and 0xFF
            assertTrue("the canvas must stay dark at ${instant}ms, found ($r,$g,$b)", r < 40 && g < 40 && b < 40)

            // The month labels sit on the top row; they must be light on that dark canvas.
            var brightest = 0
            for (y in 0 until (bitmap.height * 0.12f).toInt()) {
                for (x in 0 until bitmap.width) {
                    val pixel = bitmap.getPixel(x, y)
                    val luminance = ((pixel shr 16 and 0xFF) + (pixel shr 8 and 0xFF) + (pixel and 0xFF)) / 3
                    if (luminance > brightest) brightest = luminance
                }
            }
            assertTrue("the labels must be legible on the dark canvas, brightest=$brightest", brightest > 100)
        }
    }

    @Test fun theDarkCanvasIsNeverWhiteEvenOnTheFirstFrame() {
        val animation = SmilAnimation.detect(
            SvgEmbeddedImages.prepare(fixture("pacman-contribution-graph-dark.svg")),
        )!!
        // The very first frame is what appears before any animation tick, so it must already be dark.
        val first = rasterise(animation.frameDocument(0))
        var pale = 0
        for (y in 0 until first.height) {
            for (x in 0 until first.width) {
                val pixel = first.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                if (r > 200 && g > 200 && b > 200) pale++
            }
        }
        // Only the maze walls are white; the canvas behind them must not be.
        val total = first.width * first.height
        assertTrue("no white background: $pale of $total pixels are near-white", pale < total / 8)
    }

    /**
     * The dark canvas covers the whole graph, October and November included (v2.6.0 crash-fix 4).
     *
     * The document declares no `viewBox`, so `width="100%"` on its background rectangle was resolved against the
     * *shrunken viewport* the decoder asks for (1000 × 157) rather than against the canvas (1166 × 184). The last 166
     * units across — the newest weeks, October and November — and the bottom 27 units were never painted, so the
     * Author page's navy panel showed through exactly there, which read as "the dark theme stops at October" and as
     * "an extra empty row at the bottom".
     *
     * Two independent measurements: nothing in the picture may be left transparent (an uncovered canvas is), and no
     * pixel may be the panel colour (the page behind a hole).
     */
    @Test fun theDarkCanvasCoversEveryCornerIncludingOctoberAndNovember() {
        val animation = darkGraphAnimation()
        for (instant in listOf(0L, animation.durationMs / 3, animation.durationMs - 1)) {
            val bitmap = rasterise(animation.frameDocument(instant))
            var transparent = 0
            var panel = 0
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    val pixel = bitmap.getPixel(x, y)
                    if (((pixel ushr 24) and 0xFF) < 250) transparent++
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    if (Math.abs(r - PANEL[0]) < 12 && Math.abs(g - PANEL[1]) < 12 && Math.abs(b - PANEL[2]) < 14) {
                        panel++
                    }
                }
            }
            assertEquals("${instant}ms: an uncovered canvas is left transparent", 0, transparent)
            // A hole would leave thousands of panel-coloured pixels (the old defect left ~26 000); antialiasing on a
            // sprite edge can legitimately land near that colour on a few pixels.
            assertTrue("${instant}ms: the page must not show through the graph, found $panel", panel < 100)
        }
    }

    /** Every column band is painted — the newest weeks get the canvas, not just the older ones. */
    @Test fun everyColumnBandOfTheGraphIsPaintedWithTheCanvas() {
        val bitmap = rasterise(darkGraphAnimation().frameDocument(0))
        val bands = 20
        for (band in 0 until bands) {
            val from = bitmap.width * band / bands
            val until = bitmap.width * (band + 1) / bands
            var canvas = 0
            for (x in from until until) {
                for (y in 0 until bitmap.height) {
                    val pixel = bitmap.getPixel(x, y)
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    // The 2-unit gap between every two cells is bare canvas, so any covered band contains it.
                    if (Math.abs(r - CANVAS[0]) < 8 && Math.abs(g - CANVAS[1]) < 8 && Math.abs(b - CANVAS[2]) < 8) {
                        canvas++
                    }
                }
            }
            assertTrue("column band $band (x $from..$until) has no canvas: $canvas pixels", canvas > 0)
        }
    }

    /**
     * Seven contribution rows, and no surplus band under them (v2.6.0 crash-fix 4).
     *
     * The grid is 53 columns × 7 weekday rows of 20-unit cells on a 22-unit pitch, so the rows end at y = 167 while
     * the canvas declared 184 — 16 units of empty space that showed up as an extra row once the background stopped
     * short. Counting the rows in the rendered picture is the only way to be sure the *surplus* row was removed and
     * not a real one: the count must be 7 before and after, with the empty band gone.
     */
    @Test fun theGraphKeepsSevenRowsAndLosesTheSurplusBandBelow() {
        val bitmap = rasterise(darkGraphAnimation().frameDocument(0))
        assertEquals("the canvas keeps the generator's width", 1166, bitmap.width)
        assertEquals("the canvas is trimmed to the last row (167 + 1)", 168, bitmap.height)

        val rows = mutableListOf<Int>()
        for (y in 0 until bitmap.height) {
            var inked = 0
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val isCanvas = Math.abs(r - CANVAS[0]) < 8 && Math.abs(g - CANVAS[1]) < 8 && Math.abs(b - CANVAS[2]) < 8
                // Maze walls and labels are not contribution cells; a cell row is mostly cell.
                val isWhite = r > 200 && g > 200 && b > 200
                if (!isCanvas && !isWhite) inked++
            }
            if (inked > bitmap.width / 2) rows += y
        }
        val bands = mutableListOf<IntRange>()
        for (y in rows) {
            if (bands.isNotEmpty() && y == bands.last().last + 1) {
                bands[bands.size - 1] = bands.last().first..y
            } else {
                bands += y..y
            }
        }
        assertEquals("seven weekday rows must remain: ${bands.map { it.first }}", 7, bands.size)
        assertTrue("the last row must not be cut: ${bands.last()}", bands.last().last >= 166)
        assertTrue(
            "no band below the last row: ${bitmap.height - 1 - bands.last().last} empty rows",
            bitmap.height - 1 - bands.last().last < 3,
        )
    }

    /** The badge document's bytes, as the app receives them. */
    private fun badgeBytes(name: String): ByteArray {
        val context = InstrumentationRegistry.getInstrumentation().context
        return context.assets.open("author/$name").use { it.readBytes() }
    }

    /**
     * Pixels whose colour is neither the badge's background nor its icon colour — the antialiasing band.
     *
     * A sharp edge has a narrow band; an enlarged bitmap has a wide one, which is what "blurred" measures as.
     */
    private fun intermediatePixels(bitmap: Bitmap): Int {
        val background = bitmap.getPixel(1, bitmap.height / 2)
        val bgR = (background shr 16) and 0xFF
        val bgG = (background shr 8) and 0xFF
        val bgB = background and 0xFF
        var count = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val nearBackground = Math.abs(r - bgR) < 24 && Math.abs(g - bgG) < 24 && Math.abs(b - bgB) < 24
                val nearInk = r > 200 && g > 200 && b > 200
                if (!nearBackground && !nearInk) count++
            }
        }
        return count
    }
}
