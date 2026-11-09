// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.author

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Pacman contribution graph's animation (v2.6.0 crash-fix 1).
 *
 * The fixture is the real generated document (`app/src/test/resources/author/pacman-contribution-graph.svg`,
 * 85 444 bytes, 1166×184): 54 `<animate>` elements that eat cells and six `<animateTransform>` elements that move
 * and turn Pacman, all over a declared `dur` of 29 400 ms at 5 frames per second.
 *
 * These assertions are what separate "we parsed an SVG" from "the source animates": the timeline is read from the
 * document, different instants produce different documents, and the animation is gone from the frame so the
 * renderer cannot silently fall back to the first frame.
 */
class SmilAnimationTest {

    private fun fixture(): String = requireNotNull(
        javaClass.getResourceAsStream("/author/pacman-contribution-graph.svg"),
    ) { "the Pacman fixture is missing from the test resources" }.bufferedReader().use { it.readText() }

    @Test fun theRealGraphIsRecognisedAsAnimated() {
        val animation = SmilAnimation.detect(fixture())
        assertNotNull("the generated graph declares SMIL animation", animation)
        animation!!
        assertEquals("the document declares its own loop length", 29_400L, animation.durationMs)
        assertEquals("the document declares its own playback rate", 5, animation.framesPerSecond)
        assertEquals("29.4 s at 5 fps is the 147 frames the generator reports", 147, animation.frameCount)
        assertTrue("many elements animate: ${animation.animatedElementCount}", animation.animatedElementCount >= 50)
    }

    @Test fun aStaticDocumentIsNotAnAnimation() {
        assertNull(SmilAnimation.detect("<svg xmlns=\"http://www.w3.org/2000/svg\"><rect width=\"10\" height=\"10\"/></svg>"))
        assertNull(SmilAnimation.detect(""))
        assertFalse(SmilAnimation.isAnimated("<svg><text>no animation here</text></svg>"))
    }

    @Test fun animationWithoutADurationIsRefusedRatherThanGuessed() {
        val noDuration = """
            <svg xmlns="http://www.w3.org/2000/svg">
              <rect id="c" width="10" height="10">
                <animate attributeName="fill" repeatCount="indefinite" values="#fff;#000" keyTimes="0;1"/>
              </rect>
            </svg>
        """.trimIndent()
        assertNull(SmilAnimation.detect(noDuration))
    }

    @Test fun aFrameIsAStillDocumentWithNoAnimationLeftInIt() {
        val animation = SmilAnimation.detect(fixture())!!
        val frame = animation.frameDocument(0)
        assertFalse("a rendered frame must not carry the animation", frame.contains("<animate", ignoreCase = true))
        assertFalse(frame.contains("animateTransform", ignoreCase = true))
        assertTrue("the geometry is kept", frame.contains("<rect"))
        assertTrue("the embedded ghost sprites are kept", frame.contains("data:image/png;base64"))
    }

    @Test fun differentInstantsProduceDifferentPictures() {
        val animation = SmilAnimation.detect(fixture())!!
        val first = animation.frameDocument(0)
        val middle = animation.frameDocument(animation.durationMs / 2)
        val late = animation.frameDocument(animation.durationMs - 1)
        assertTrue("the graph must change over the loop, not stay frozen", first != middle)
        assertTrue("the graph must keep changing late in the loop", middle != late)
        assertTrue("the loop repeats", first == animation.frameDocument(animation.durationMs))
    }

    @Test fun pacmanMovesAndTurns() {
        val animation = SmilAnimation.detect(fixture())!!
        val first = pacmanTag(animation.frameDocument(0))
        val later = pacmanTag(animation.frameDocument(animation.durationMs / 3))
        assertTrue("Pacman gets a transform, not just a static path: $first", first.contains("transform=\"translate("))
        assertTrue("Pacman turns as well: $first", first.contains("rotate("))
        assertTrue("Pacman must be somewhere else later in the loop", first != later)
    }

    @Test fun cellsAreEatenOverTime() {
        val animation = SmilAnimation.detect(fixture())!!
        val start = eatenCells(animation.frameDocument(0))
        val end = eatenCells(animation.frameDocument(animation.durationMs - 1))
        assertTrue(
            "the eaten cells must grow as Pacman passes: start=$start end=$end",
            end > start,
        )
    }

    @Test fun theWholeSourceSurvivesTheRewrite() {
        val animation = SmilAnimation.detect(fixture())!!
        val frame = animation.frameDocument(7_000)
        // Every element of the original document is still there; only attribute values were rewritten.
        assertEquals(count("<rect", fixture()), count("<rect", frame))
        assertEquals(count("<use", fixture()), count("<use", frame))
        assertEquals(count("<path", fixture()), count("<path", frame))
    }

    /** The `<path id="pacman" …>` open tag of a frame. */
    private fun pacmanTag(frame: String): String {
        val at = frame.indexOf("id=\"pacman\"")
        require(at > 0) { "the pacman path is missing from the frame" }
        val start = frame.lastIndexOf('<', at)
        val end = frame.indexOf('>', at)
        return frame.substring(start, end + 1)
    }

    /**
     * A minimal document with one interpolating animation and one stepping animation, so the two `calcMode`s can be
     * asserted apart from the real graph (v2.6.0 crash-fix 3).
     */
    private fun blenders(): String = """
        <svg xmlns="http://www.w3.org/2000/svg" width="100" height="20">
          <rect id="cell" width="10" height="10" fill="#000000">
            <animate attributeName="fill" dur="1000ms" repeatCount="indefinite" calcMode="discrete"
                     values="#000000;#ffffff" keyTimes="0;0.5"/>
          </rect>
          <path id="walker" d="M0,0" fill="red">
            <animateTransform attributeName="transform" type="translate" dur="1000ms" repeatCount="indefinite"
                              values="0,0;100,0" keyTimes="0;1"/>
          </path>
        </svg>
    """.trimIndent()

    @Test fun pacmanMovesSmoothlyBetweenTheSourcesOwnFrames() {
        // The generator writes one position per 200 ms; SMIL's default calcMode is linear, so an instant between two
        // keyTimes has a real value and the page can sample the same timeline at 18 fps instead of 5 — without
        // moving Pacman any faster (v2.6.0 crash-fix 3).
        val animation = SmilAnimation.detect(blenders())!!
        assertTrue("the document has continuous motion", animation.hasContinuousMotion)
        val quarter = animation.frameDocument(250)
        val half = animation.frameDocument(500)
        val threeQuarters = animation.frameDocument(750)
        assertTrue("a quarter through is a quarter of the way: $quarter", quarter.contains("translate(25,0)"))
        assertTrue("half way is half: $half", half.contains("translate(50,0)"))
        assertTrue("three quarters is three quarters: $threeQuarters", threeQuarters.contains("translate(75,0)"))
    }

    @Test fun aCellNeverRendersHalfEaten() {
        // `calcMode="discrete"` means a cell is its level colour or eaten, never a blend: at 40 % of the way it must
        // still be the first value, not 40 % of the way to white.
        val animation = SmilAnimation.detect(blenders())!!
        assertTrue(animation.frameDocument(400).contains("fill=\"#000000\""))
        assertTrue(animation.frameDocument(600).contains("fill=\"#ffffff\""))
    }

    @Test fun theRealGraphInterpolatesPacmanAndStepsItsCells() {
        val animation = SmilAnimation.detect(fixture())!!
        assertTrue("the graph moves continuously", animation.hasContinuousMotion)
        // The same instant sampled inside one source frame differs from the frame before it, which is what makes a
        // higher playback rate meaningful rather than a repeat of the same picture.
        val a = pacmanTag(animation.frameDocument(1_000))
        val b = pacmanTag(animation.frameDocument(1_040))
        assertTrue("40 ms apart must differ inside a source frame: $a / $b", a != b)
        // And the source's timing is untouched: one loop is still 29.4 s.
        assertEquals(29_400L, animation.durationMs)
    }

    @Test fun theSmoothRateIsIndependentOfTheSourceRate() {
        val animation = SmilAnimation.detect(fixture())!!
        assertEquals("the generator's own rate is still read from the document", 5, animation.framesPerSecond)
        assertEquals("and the page samples it at its own rate", 18, SmilAnimation.SMOOTH_FPS)
        assertTrue("18 fps is within the requested band", SmilAnimation.SMOOTH_FPS in 15..20)
    }

    /** How many day cells currently show the eaten grey. */
    private fun eatenCells(frame: String): Int = Regex("<rect[^>]*fill=\"#ebedf0\"").findAll(frame).count()

    private fun count(needle: String, text: String): Int = Regex.escape(needle).toRegex().findAll(text).count()
}
