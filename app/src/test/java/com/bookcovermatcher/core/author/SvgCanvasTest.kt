// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.author

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Canvas normalisation for the generated contribution graph (v2.6.0 crash-fix 4).
 *
 * The fixture is the real dark graph the README points at: `<svg width="1166" height="184">` with **no `viewBox`**,
 * a `<rect width="100%" height="100%" fill="#0d1117"/>` background, 53 × 7 day cells spanning x 0–1164 and
 * y 15–167, and month labels on the y=10 baseline.
 *
 * The defect these guard: without a `viewBox` the percentage background resolves against the shrunken viewport the
 * decoder asks for (1000×157) instead of the canvas (1166×184), so the newest weeks — October and November — and the
 * band under the last row were never painted and showed the page behind them.
 */
class SvgCanvasTest {

    private fun darkGraph(): String = requireNotNull(
        javaClass.getResourceAsStream("/author/pacman-contribution-graph-dark.svg"),
    ) { "the dark graph fixture is missing" }.bufferedReader().use { it.readText() }

    private fun lightGraph(): String = requireNotNull(
        javaClass.getResourceAsStream("/author/pacman-contribution-graph.svg"),
    ) { "the light graph fixture is missing" }.bufferedReader().use { it.readText() }

    private fun rootOf(svg: String): String = Regex("<svg[^>]*>").find(svg)!!.value

    @Test
    fun theSourceHasNoViewBoxWhichIsTheWholeProblem() {
        val source = darkGraph()
        val root = rootOf(source)
        assertTrue("the document declares a width", root.contains("width=\"1166\""))
        assertTrue("and a height", root.contains("height=\"184\""))
        assertFalse("but the root has no viewBox — that is the defect: $root", root.contains("viewBox"))
        assertTrue(
            "its background is sized in percentages, so it needs a viewBox to mean the whole canvas",
            source.contains("<rect width=\"100%\" height=\"100%\" fill=\"#0d1117\"/>"),
        )
        // The ghost sprites do carry viewBoxes, which is why the check above is on the root only.
        assertTrue("the symbols keep their own viewBoxes", source.contains("<symbol id=\"ghost-blinky-up\" viewBox="))
    }

    @Test
    fun normalisingAddsAViewBoxThatCoversTheWholeCanvas() {
        val root = rootOf(SvgCanvas.normalise(darkGraph()))
        assertTrue("a viewBox is added: $root", root.contains("viewBox="))
        assertTrue("it keeps the full canvas width: $root", root.contains("viewBox=\"0 0 1166 "))
    }

    @Test
    fun theSurplusBandIsTrimmedToTheLastContributionRow() {
        // All 53 × 7 cells sit at y = 15…167 (row spacing 22, cell height 20), and the sprite translations only reach
        // y = 147, so 168 is the lowest point any drawing reaches. The canvas declared 184: 16 units of empty space.
        val root = rootOf(SvgCanvas.normalise(darkGraph()))
        assertTrue("the box is trimmed to the content: $root", root.contains("viewBox=\"0 0 1166 168\""))
        assertFalse("the padded canvas height is gone: $root", root.contains(" 184\""))
        // The declared size is rewritten as well, or the shorter viewBox would be letterboxed into a 184-tall canvas
        // and the empty band would come back as transparent pixels.
        assertTrue("the declared height follows the box: $root", root.contains("height=\"168\""))
        assertTrue("the width is unchanged: $root", root.contains("width=\"1166\""))
    }

    @Test
    fun noContributionRowIsCutByTheTrim() {
        val normalised = SvgCanvas.normalise(darkGraph())
        val bottom = Regex("viewBox=\"0 0 [0-9.]+ ([0-9.]+)\"").find(normalised)!!.groupValues[1].toFloat()
        val cells = Regex("<rect id=\"c-\\d+-\\d+\"[^>]*\\by=\"(\\d+)\"[^>]*\\bheight=\"(\\d+)\"")
            .findAll(normalised)
            .map { it.groupValues[1].toFloat() + it.groupValues[2].toFloat() }
            .toList()
        assertEquals("the fixture carries 53 columns × 7 rows", 371, cells.size)
        assertEquals("the lowest cell still ends at 167", 167f, cells.max())
        assertTrue("the viewBox contains every row: bottom=$bottom", cells.all { it <= bottom })
    }

    @Test
    fun theTrimIsTheSameForTheLightGraphBecauseOnlyThePaletteDiffers() {
        val root = rootOf(SvgCanvas.normalise(lightGraph()))
        assertTrue("the light graph is corrected identically: $root", root.contains("viewBox=\"0 0 1166 168\""))
    }


    @Test
    fun aDocumentThatAlreadyHasAViewBoxIsUntouched() {
        val icon = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"><path d=\"M0 0h24v24H0z\"/></svg>"
        assertEquals("identity — there is nothing to correct", icon, SvgCanvas.normalise(icon))
    }

    @Test
    fun aDocumentWithoutANumericSizeIsLeftAlone() {
        val percentageRoot = "<svg width=\"100%\" height=\"100%\"><rect height=\"10\"/></svg>"
        assertEquals(percentageRoot, SvgCanvas.normalise(percentageRoot))
        val noSize = "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect height=\"10\"/></svg>"
        assertEquals(noSize, SvgCanvas.normalise(noSize))
        assertEquals("", SvgCanvas.normalise(""))
    }

    @Test
    fun aCanvasThatIsNotPaddedKeepsItsDeclaredHeight() {
        // Nothing is trimmed when the drawing already reaches the bottom, so an ordinary picture keeps its size.
        val document = "<svg width=\"100\" height=\"50\"><rect y=\"40\" height=\"10\"/></svg>"
        val root = rootOf(SvgCanvas.normalise(document))
        assertTrue("the declared height is kept: $root", root.contains("viewBox=\"0 0 100 50\""))
    }

    @Test
    fun aShieldsBadgeGainsItsOwnCanvasBox() {
        // The badge declares 59×20 and no viewBox either, so it is normalised the same way — which is also why the
        // decoder must no longer scale the canvas as well (v2.6.0 crash-fix 4).
        val badge = javaClass.getResourceAsStream("/author/badge-kotlin.svg")!!
            .bufferedReader().use { it.readText() }
        val root = rootOf(SvgCanvas.normalise(badge))
        assertTrue("the badge keeps its own size as the box: $root", root.contains("viewBox=\"0 0 59 20\""))
        assertTrue("and its declared size: $root", root.contains("width=\"59\"") && root.contains("height=\"20\""))
    }

    @Test
    fun malformedInputCannotThrow() {
        assertTrue(SvgCanvas.normalise("<svg").isNotEmpty())
        assertTrue(SvgCanvas.normalise("<svg width=\"x\" height=\"y\">").isNotEmpty())
        assertTrue(SvgCanvas.normalise("<svg width=\"10\" height=\"10\">").isNotEmpty())
    }

    @Test
    fun theBackgroundSurvivesNormalisationAndFrameGeneration() {
        // The dark canvas must be inside the corrected box on every animation frame, not just the first.
        val normalised = SvgCanvas.normalise(darkGraph())
        assertTrue("the background rect is untouched", normalised.contains("fill=\"#0d1117\""))
        val animation = SmilAnimation.detect(normalised)!!
        for (instant in listOf(0L, animation.durationMs / 2, animation.durationMs - 1)) {
            val frame = animation.frameDocument(instant)
            assertTrue("frame at ${instant}ms keeps its viewBox", frame.contains("viewBox=\"0 0 1166 168\""))
            assertTrue("frame at ${instant}ms keeps its background", frame.contains("fill=\"#0d1117\""))
        }
    }
}
