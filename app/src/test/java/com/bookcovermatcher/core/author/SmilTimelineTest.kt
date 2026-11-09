// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.author

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scene split behind the 60 fps contribution graph, and the stationary-sprite defect of 2.6.0.
 *
 * The fixture is the real dark graph the README points at — 4 ghost groups, one Pacman path, 371 cells, 20 flashing
 * cells and 6 `animateTransform` elements — so the parse is exercised against the structure that actually ships, not a
 * simplified stand-in.
 *
 * The defect these tests pin down: the scenery is the document with every animation *and* the five movers removed, and
 * removal first dropped the movers' `<animate>` children and then **skipped the mover elements themselves**, because
 * their spans overlap those children. A ghost group left behind is drawn at its static `transform="translate(0,0)"` — a
 * sprite parked in the top-left corner of the graph, exactly what a user reports as "a ghost stuck at the top-left".
 */
class SmilTimelineTest {

    private fun graph(): String = requireNotNull(
        javaClass.getResourceAsStream("/author/pacman-contribution-graph-dark.svg"),
    ) { "the dark graph fixture is missing" }.bufferedReader().use { it.readText() }

    private fun timeline(): SmilTimeline = requireNotNull(SmilTimeline.detect(SvgCanvas.normalise(graph()))) {
        "the real graph must split into a scene"
    }

    @Test
    fun theSourceDeclaresFourGhostsAndOnePacman() {
        val source = graph()
        assertEquals("four ghost groups", 4, Regex("<g\\b[^>]*\\bid=\"ghost\\d+\"").findAll(source).count())
        assertEquals("one Pacman path", 1, Regex("<path\\b[^>]*\\bid=\"pacman\"").findAll(source).count())
        assertEquals(
            "six transform animations: four ghost translates, Pacman's translate and his rotate",
            6,
            Regex("<animateTransform").findAll(source).count(),
        )
        assertTrue(
            "each ghost starts from a static translate(0,0), which is why a survivor is stuck in the corner",
            source.contains("<g id=\"ghost0\" transform=\"translate(0,0)\">"),
        )
    }

    @Test
    fun theSceneryContainsNoMoverAtAll() {
        val base = timeline().baseDocument
        for (index in 0..3) {
            assertFalse(
                "ghost$index must not survive into the scenery, or it is drawn twice — once parked at translate(0,0)",
                base.contains("id=\"ghost$index\""),
            )
        }
        assertFalse("Pacman must not survive into the scenery either", base.contains("id=\"pacman\""))
        assertFalse("no animation may survive into the scenery", base.contains("<animate"))
    }

    @Test
    fun theSceneryKeepsEverythingElse() {
        val base = timeline().baseDocument
        assertEquals("all 371 day cells remain", 371, Regex("<rect\\b[^>]*\\bid=\"c-\\d+-\\d+\"").findAll(base).count())
        assertEquals("26 month labels remain", 26, Regex("<text\\b").findAll(base).count())
        assertTrue("the dark canvas remains", base.contains("fill=\"#0d1117\""))
        assertTrue(
            "the corrected canvas box is untouched",
            Regex("<svg[^>]*>").find(base)?.value?.contains("viewBox=\"0 0 1166 168\"") == true,
        )
    }

    @Test
    fun theSceneHasFourGhostsAndOnePacman() {
        val timeline = timeline()
        assertEquals("five movers", 5, timeline.movers.size)
        assertEquals("four ghosts", 4, timeline.movers.count { it.kind == SmilTimeline.Kind.GHOST })
        val pacman = timeline.movers.single { it.kind == SmilTimeline.Kind.PACMAN }
        assertNotNull("Pacman carries a translate", pacman.translate)
        assertNotNull("and a rotate", pacman.rotate)
        assertNotNull("and a 0.5 s mouth cycle", pacman.shape)
        assertNotNull("and a fill", pacman.fill)
        assertTrue(
            "each ghost carries its direction sprites",
            timeline.movers.filter { it.kind == SmilTimeline.Kind.GHOST }.all { it.sprites.size >= 4 },
        )
    }

    @Test
    fun everyGhostMovesAwayFromTheTopLeftCorner() {
        val timeline = timeline()
        for (instant in listOf(0L, 1_000L, 5_000L, 14_500L, 28_000L)) {
            for ((index, ghost) in timeline.sceneAt(instant).ghosts.withIndex()) {
                assertTrue(
                    "ghost $index is parked at the top-left at ${instant}ms: (${ghost.x}, ${ghost.y})",
                    ghost.x > 20f || ghost.y > 20f,
                )
            }
        }
    }

    @Test
    fun theGhostsTraceDifferentPositionsOverTimeAndDoNotStack() {
        val timeline = timeline()
        val frames = listOf(0L, 3_000L, 9_000L, 21_000L).map { timeline.sceneAt(it).ghosts.map { ghost -> ghost.x to ghost.y } }
        for (index in 0 until 4) {
            val distinct = frames.map { it[index] }.distinct()
            assertTrue("ghost $index must move, saw ${frames.map { it[index] }}", distinct.size >= 3)
        }
        assertTrue(
            "the four ghosts must not all sit on one another",
            frames.all { frame -> frame.distinct().size >= 3 },
        )
    }

    @Test
    fun pacmanMovesTurnsAndChomps() {
        val timeline = timeline()
        // The mouth cycle is 500 ms, so the instants must not all land on the same phase of it.
        val frames = listOf(0L, 250L, 2_000L, 20_250L).map { timeline.sceneAt(it).pacman }
        assertTrue("Pacman must move", frames.map { it.x to it.y }.distinct().size >= 3)
        assertTrue("and must turn", frames.map { it.rotationDegrees }.distinct().size >= 2)
        assertTrue("and must chomp, so his path data changes", frames.map { it.pathData }.distinct().size >= 2)
        val first = frames.first()
        assertTrue(
            "his rotation centre is the middle of his cell",
            first.rotationCentreX == 10f && first.rotationCentreY == 10f,
        )
    }

    @Test
    fun eachAnimationIsSampledAgainstItsOwnDuration() {
        val timeline = timeline()
        val pacman = timeline.movers.single { it.kind == SmilTimeline.Kind.PACMAN }
        val shape = requireNotNull(pacman.shape)
        assertEquals("the mouth cycle is 0.5 s inside a 29 s loop", 500L, shape.durationMs)
        assertEquals("the mouth cycle repeats every 500 ms", timeline.sceneAt(100).pacman.pathData, timeline.sceneAt(600).pacman.pathData)
        assertTrue(
            "and differs half-way through it",
            timeline.sceneAt(100).pacman.pathData != timeline.sceneAt(350).pacman.pathData,
        )
    }

    @Test
    fun theFlashCellsStepOnceEachAtTheirOwnKeyTime() {
        val timeline = timeline()
        assertEquals("20 cells flash", 20, timeline.flashes.size)
        val early = timeline.sceneAt(0).litFlashes.size
        val late = timeline.sceneAt(timeline.durationMs - 1).litFlashes.size
        assertTrue("cells are eaten as Pacman passes, so fewer are lit later: $early then $late", late < early)
    }

    @Test
    fun aShortDocumentWithOneGhostStillYieldsASceneOrNullButNeverThrows() {
        val short = "<svg width=\"100\" height=\"50\" viewBox=\"0 0 100 50\"><g id=\"ghost0\" " +
            "transform=\"translate(0,0)\"><animateTransform attributeName=\"transform\" type=\"translate\" " +
            "dur=\"1000ms\" values=\"0,0;10,10\"/></g></svg>"
        val timeline = SmilTimeline.detect(short)
        if (timeline != null) {
            assertFalse("even a short document must not keep the mover in its scenery", timeline.baseDocument.contains("id=\"ghost0\""))
            val scene = timeline.sceneAt(500)
            assertEquals("its one ghost is sampled where the animation says", 5f, scene.ghosts.single().x)
        }
    }

    @Test
    fun malformedDocumentsFailSafely() {
        assertEquals("an empty document is not a scene", null, SmilTimeline.detect(""))
        assertEquals("a still document is not a scene", null, SmilTimeline.detect("<svg width=\"10\" height=\"10\"/>"))
        assertEquals("garbage is not a scene", null, SmilTimeline.detect("<svg <<< not xml"))
    }
}

