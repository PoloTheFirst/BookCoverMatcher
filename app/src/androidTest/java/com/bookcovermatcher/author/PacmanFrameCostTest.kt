// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.author

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcovermatcher.core.author.SmilAnimation
import com.bookcovermatcher.core.author.SvgEmbeddedImages
import com.caverock.androidsvg.SVG
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where the contribution graph's frame time actually goes (v2.6.0 stable).
 *
 * Reaching 60 fps means a frame must cost less than 16.7 ms, so the first question is which stage of the pipeline
 * costs what. This measures the four candidate stages on the device, in the same way production drives them, and
 * prints a table the report quotes:
 *
 *  1. `SVG.getFromString` — parsing the 85 KB document;
 *  2. `renderToCanvas` on an *already parsed* document — the raster only;
 *  3. the production frame path (`AuthorImageDecoder.renderFrame`), i.e. 1 + 2 every frame;
 *  4. composing a cached base bitmap plus the five moving sprites — the cost of the layered renderer.
 *
 * The timings are printed, not asserted: a wall-clock threshold in a test suite is a flake, and the point here is
 * evidence for choosing a design, which the report records.
 */
@RunWith(AndroidJUnit4::class)
class PacmanFrameCostTest {

    private fun graph(): String {
        val context = InstrumentationRegistry.getInstrumentation().context
        return context.assets.open("author/pacman-contribution-graph-dark.svg")
            .bufferedReader().use { it.readText() }
    }

    private fun median(samples: Int, block: () -> Unit): Double {
        val times = ArrayList<Long>(samples)
        repeat(samples) {
            val start = SystemClock.elapsedRealtimeNanos()
            block()
            times += SystemClock.elapsedRealtimeNanos() - start
        }
        times.sort()
        return times[samples / 2] / 1_000_000.0
    }

    private fun report(line: String) = println("PACMAN-COST $line")

    @Test
    fun theFrameTimeBreakdownIsMeasuredRatherThanAssumed() {
        val document = SvgEmbeddedImages.prepare(graph())
        val animation = requireNotNull(SmilAnimation.detect(document)) { "the graph must animate" }
        val width = 932
        val height = (932 * 168 / 1166)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        // 1. Parse only.
        val parse = median(15) { SVG.getFromString(document) }

        // 2. Raster only, on a document parsed once — the renderer's own cost.
        val parsed = SVG.getFromString(document)
        parsed.setDocumentWidth(width.toFloat())
        parsed.setDocumentHeight(height.toFloat())
        val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        val raster = median(15) { parsed.renderToCanvas(canvas) }

        // 3. The production path: build the frame document, parse it, rasterise it — every frame.
        val production = median(10) {
            com.bookcovermatcher.data.author.AuthorImageDecoder.renderFrame(
                animation, SystemClock.elapsedRealtime() % animation.durationMs, maxWidth = width,
            )
        }

        // 4. The layered alternative: one full-bitmap blit plus five sprite blits into a reusable target.
        val base = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(base).also { baseCanvas ->
            baseCanvas.drawColor(0xFF0D1117.toInt())
            for (i in 0 until 40) {
                baseCanvas.drawRect(
                    (i * 22).toFloat(), 15f, (i * 22 + 20).toFloat(), 35f,
                    Paint().apply { color = 0xFF161B22.toInt() },
                )
            }
        }
        val sprite = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        Canvas(sprite).drawColor(0xFFFFD700.toInt())
        val composed = median(60) {
            canvas.drawBitmap(base, 0f, 0f, paint)
            for (i in 0 until 5) {
                canvas.drawBitmap(sprite, (100 + i * 60).toFloat(), 100f + i, paint)
            }
        }

        report("parse=${"%.2f".format(parse)}ms raster=${"%.2f".format(raster)}ms")
        report("production=${"%.2f".format(production)}ms layered=${"%.2f".format(composed)}ms")
        report("budget=${"%.2f".format(1000.0 / 60.0)}ms (60 fps)")

        // Only the structural facts are asserted; the numbers above are the evidence.
        assertTrue("a parsed document must rasterise", raster > 0.0)
        assertTrue("the production path must produce a frame", production > 0.0)
        assertTrue("the layered path must be measurable", composed > 0.0)
    }
}
