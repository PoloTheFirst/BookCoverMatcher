// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.author

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The display box each README picture gets (v2.6.0 crash-fix 1).
 *
 * The defect was that every picture was drawn at the full width, so a 100×20 Shields.io badge became a
 * screen-wide band. These tests are the regression guard: a badge can never exceed its configured bounds, its own
 * aspect ratio is preserved, and the banner and the contribution graph keep the full width they need.
 */
class AuthorImageSizingTest {

    private val pythonBadge = "https://img.shields.io/badge/Python-3670A0?&logo=python&logoColor=ffdd54"
    private val banner = "https://github.com/PoloTheFirst/PoloTheFirst/blob/main/assets/2_PC-A.png"
    private val graph = "https://raw.githubusercontent.com/PoloTheFirst/PoloTheFirst/output/pacman-contribution-graph.svg"

    @Test fun badgesAreRecognisedByHost() {
        assertTrue(AuthorImageSizing.isBadge(pythonBadge))
        assertTrue(AuthorImageSizing.isBadge("https://img.shields.io/badge/-Linux-grey?logo=linux"))
        assertFalse(AuthorImageSizing.isBadge(banner))
        assertFalse(AuthorImageSizing.isBadge(graph))
        assertFalse(AuthorImageSizing.isBadge(null))
        assertFalse("a look-alike host must not pass", AuthorImageSizing.isBadge("https://img.shields.io.evil.example/b.svg"))
    }

    @Test fun aBadgeCannotExpandBeyondItsConfiguredBounds() {
        // A real Shields.io badge is about 100×20, i.e. 5:1.
        val box = AuthorImageSizing.box(pythonBadge, aspectRatio = 5f, availableWidthDp = 360f)
        assertEquals("a badge has a fixed display height", AuthorImageSizing.BADGE_HEIGHT_DP, box.heightDp, 0.001f)
        assertTrue(
            "a badge must never be wider than its share of the row: ${box.widthDp}",
            box.widthDp <= AuthorImageSizing.badgeMaxWidthDp(360f) + 0.001f,
        )
        assertTrue("and it must stay readable", box.widthDp >= 1f)
    }

    @Test fun aBadgeKeepsItsOwnAspectRatioWhenItFits() {
        val box = AuthorImageSizing.box(pythonBadge, aspectRatio = 5f, availableWidthDp = 360f)
        assertEquals(5f, box.aspectRatio, 0.001f)
    }

    @Test fun aBadgeIsNeverStretchedToTheFullWidthHoweverWideItClaimsToBe() {
        // A hostile or broken SVG could report an enormous intrinsic width; the badge rule must still cap it.
        val box = AuthorImageSizing.box(pythonBadge, aspectRatio = 500f, availableWidthDp = 360f)
        assertEquals(AuthorImageSizing.badgeMaxWidthDp(360f), box.widthDp, 0.001f)
        assertEquals(AuthorImageSizing.BADGE_HEIGHT_DP, box.heightDp, 0.001f)
    }

    @Test fun theBannerAndTheGraphKeepTheFullWidth() {
        val bannerBox = AuthorImageSizing.box(banner, aspectRatio = 3840f / 2160f, availableWidthDp = 360f)
        assertEquals(360f, bannerBox.widthDp, 0.001f)
        assertEquals(360f / (3840f / 2160f), bannerBox.heightDp, 0.5f)

        val graphBox = AuthorImageSizing.box(graph, aspectRatio = 1166f / 184f, availableWidthDp = 360f)
        assertEquals(360f, graphBox.widthDp, 0.001f)
        assertTrue("the graph is wide and short, so it stays short", graphBox.heightDp < 70f)
    }

    @Test fun aNonsensicalAspectRatioCannotProduceAnUnusableBox() {
        for (ratio in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val box = AuthorImageSizing.box(banner, ratio, availableWidthDp = 360f)
            assertTrue("width must be positive for $ratio", box.widthDp > 0f)
            assertTrue("height must be positive for $ratio", box.heightDp > 0f)
            assertTrue("height must stay finite for $ratio", box.heightDp.isFinite())
        }
    }

    @Test fun aVeryTallPictureIsBoundedSoItCannotOwnTheScreen() {
        val box = AuthorImageSizing.box(banner, aspectRatio = 0.01f, availableWidthDp = 360f)
        assertEquals(360f * 3f, box.heightDp, 0.001f)
    }
}
