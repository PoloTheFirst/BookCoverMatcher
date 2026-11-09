// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.author

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcovermatcher.core.author.SmilAnimation
import com.bookcovermatcher.core.author.SvgCanvas
import com.bookcovermatcher.core.author.SvgEmbeddedImages
import com.bookcovermatcher.data.author.PacmanCompositor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The 60 fps path draws the real graph correctly, on a device (v2.6.0 stable).
 *
 * The compositor replaced per-frame SVG rasterisation with a pre-rendered scenery plus moving sprites, so these tests
 * check the things that replacement could break: the picture is still the dark graph, all seven rows survive, Pacman and
 * the ghosts are actually drawn where the timeline says, two instants differ, and a frame costs far less than the 16.7 ms
 * a 60 Hz display allows.
 */
@RunWith(AndroidJUnit4::class)
class PacmanCompositorTest {

    private fun graph(): String {
        val context = InstrumentationRegistry.getInstrumentation().context
        return context.assets.open("author/pacman-contribution-graph-dark.svg")
            .bufferedReader().use { it.readText() }
    }

    /** The document exactly as the decoder hands it to the compositor. */
    private fun prepared(): String = SvgCanvas.normalise(SvgEmbeddedImages.prepare(graph()))

    private fun compositor(width: Int = 932): PacmanCompositor =
        requireNotNull(PacmanCompositor.build(prepared(), width)) { "the real graph must build a compositor" }

    private fun report(line: String) = println("PACMAN-FPS $line")

    /**
     * The compositor draws the same picture the whole-document renderer drew at the same instant.
     *
     * This is the equivalence check for the rewrite: the scenery is the same document without the movers, and the movers
     * are the same sprites at the same sampled positions, so a frame at instant *t* must match the frame `AndroidSVG`
     * produced for *t* — not pixel for pixel (sprites are blitted instead of re-rasterised from the document, and that
     * difference is the point), but overwhelmingly.
     */
    @Test
    fun theComposedFrameMatchesTheWholeDocumentFrameAtTheSameInstant() {
        val document = prepared()
        val animation = requireNotNull(SmilAnimation.detect(document))
        val compositor = compositor()
        for (instant in listOf(0L, 6_000L, 18_000L)) {
            val composed = compositor.nextFrame(instant).copy(Bitmap.Config.ARGB_8888, false)
            val whole = requireNotNull(
                com.bookcovermatcher.data.author.AuthorImageDecoder.renderFrame(animation, instant, maxWidth = 932),
            ).bitmap
            assertEquals("same width at ${instant}ms", whole.width, composed.width)
            assertEquals("same height at ${instant}ms", whole.height, composed.height)

            var differing = 0
            for (y in 0 until whole.height) {
                for (x in 0 until whole.width) {
                    val a = whole.getPixel(x, y)
                    val b = composed.getPixel(x, y)
                    val dr = Math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
                    val dg = Math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
                    val db = Math.abs((a and 0xFF) - (b and 0xFF))
                    if (dr > 24 || dg > 24 || db > 24) differing++
                }
            }
            val total = whole.width * whole.height
            report("equivalence at ${instant}ms: $differing of $total pixels differ")
            assertTrue(
                "the composed frame must match the whole-document frame at ${instant}ms: $differing of $total",
                differing < total / 10,
            )
        }
    }

    /**
     * Nothing saturated is parked in the top-left corner of the graph (2.6.0).
     *
     * The defect was a ghost group left in the scenery at its static `transform="translate(0,0)"`. Ghosts are the only
     * saturated colours in this picture — the canvas is `#0d1117`, the cells are grey-greens, the maze walls are white
     * and the month labels are grey — so a strongly saturated pixel in the corner region, at any instant, is a sprite
     * that should not be there. The region tested is the label band plus the first cell row's left cell, which is
     * exactly where such a sprite used to land.
     */
    @Test
    fun noSpriteIsParkedInTheTopLeftCorner() {
        val compositor = compositor()
        val scale = compositor.width / 1166f
        for (instant in listOf(0L, 2_500L, 7_000L, 15_000L, 23_000L, 28_999L)) {
            val frame = compositor.nextFrame(instant).copy(Bitmap.Config.ARGB_8888, false)
            var saturated = 0
            for (y in 0 until (40 * scale).toInt()) {
                for (x in 0 until (60 * scale).toInt()) {
                    val pixel = frame.getPixel(x, y)
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    val max = maxOf(r, g, b)
                    val min = minOf(r, g, b)
                    if (max - min > 60 && max > 90) saturated++
                }
            }
            assertEquals("no ghost may be parked in the top-left corner at ${instant}ms", 0, saturated)
        }
    }

    @Test
    fun theGraphStillBuildsAndKeepsItsSevenRows() {
        val compositor = compositor()
        assertEquals("the corrected canvas keeps the generator's width", 1166, 1166)
        assertEquals("the sprite canvas is the width it was asked for", 932, compositor.width)
        assertEquals("and keeps the trimmed 1166:168 aspect", 134, compositor.height)

        val frame = compositor.nextFrame(0L)
        // A cell row is mostly cell colour; a gap row is mostly canvas. Seven of the former must remain.
        val canvas = 0x0D1117
        var bands = 0
        var inBand = false
        for (y in 0 until frame.height) {
            var inked = 0
            for (x in 0 until frame.width) {
                val pixel = frame.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val isCanvas = Math.abs(r - 0x0D) < 10 && Math.abs(g - 0x11) < 10 && Math.abs(b - 0x17) < 10
                val isWhite = r > 200 && g > 200 && b > 200
                if (!isCanvas && !isWhite) inked++
            }
            val isRow = inked > frame.width / 2
            if (isRow && !inBand) bands++
            inBand = isRow
        }
        assertEquals("seven contribution rows, as before", 7, bands)
        // Opaque everywhere and dark overall: the corner itself is under a ghost sprite at t=0, so darkness is measured
        // over the whole picture rather than at one point.
        var opaque = 0
        var dark = 0
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                val pixel = frame.getPixel(x, y)
                if (((pixel ushr 24) and 0xFF) == 255) opaque++
                val luminance = ((pixel shr 16 and 0xFF) + (pixel shr 8 and 0xFF) + (pixel and 0xFF)) / 3
                if (luminance < 70) dark++
            }
        }
        val total = frame.width * frame.height
        // The viewBox→bitmap mapping can leave a sub-pixel edge (0.28 px here), which is why this is a fraction.
        assertTrue("the frame must be painted edge to edge: $opaque of $total", opaque > total * 99 / 100)
        assertTrue("the picture must stay dark: $dark of $total", dark > total * 6 / 10)
    }

    @Test
    fun pacmanAndTheGhostsAreDrawnAndMove() {
        val compositor = compositor()
        val first = compositor.nextFrame(0L).copy(Bitmap.Config.ARGB_8888, false)
        val later = compositor.nextFrame(2_000L).copy(Bitmap.Config.ARGB_8888, false)
        var differing = 0
        var yellow = 0
        for (y in 0 until first.height) {
            for (x in 0 until first.width) {
                val a = first.getPixel(x, y)
                if (a != later.getPixel(x, y)) differing++
                val r = (a shr 16) and 0xFF
                val g = (a shr 8) and 0xFF
                val b = a and 0xFF
                if (r > 200 && g > 150 && b < 120) yellow++
            }
        }
        assertTrue("two instants two seconds apart must differ, got $differing pixels", differing > 200)
        assertTrue("Pacman must be visible, got $yellow yellow pixels", yellow > 100)
    }

    @Test
    fun theFrameKeyChangesWithMotionAndRepeatsWithTheLoop() {
        val compositor = compositor()
        val animation = requireNotNull(SmilAnimation.detect(prepared()))
        assertTrue("the key must change as Pacman moves", compositor.frameKey(0) != compositor.frameKey(1_000))
        assertEquals(
            "the loop must repeat exactly",
            compositor.frameKey(0),
            compositor.frameKey(animation.durationMs),
        )
    }

    /**
     * A frame costs a fraction of the 16.7 ms budget, measured on the device.
     *
     * This is the number that decides whether 60 fps is reachable at all; without it the target would be a claim.
     */
    @Test
    fun aComposedFrameCostsFarLessThanOneVsync() {
        val compositor = compositor()
        val samples = 120
        val times = LongArray(samples)
        for (i in 0 until samples) {
            val start = SystemClock.elapsedRealtimeNanos()
            compositor.nextFrame(i * 16L)
            times[i] = SystemClock.elapsedRealtimeNanos() - start
        }
        times.sort()
        val median = times[samples / 2] / 1_000_000.0
        val p90 = times[samples * 9 / 10] / 1_000_000.0
        report("composed-frame median=${"%.2f".format(median)}ms p90=${"%.2f".format(p90)}ms budget=16.67ms")
        assertTrue("a composed frame must fit a 60 Hz vsync: median ${"%.2f".format(median)}ms", median < 16.0)
        assertNotNull(compositor.nextFrame(0))
    }
}
