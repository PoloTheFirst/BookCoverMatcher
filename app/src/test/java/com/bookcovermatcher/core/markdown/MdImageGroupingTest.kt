// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.markdown

import com.bookcovermatcher.core.author.AuthorImageSizing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Badge grouping (v2.6.0 crash-fix 1).
 *
 * The README lists thirteen Shields.io badges inside one `<div align="center">`; drawn one block per row they
 * stack down the page. The grouping must collect them without disturbing anything else: the same blocks, in the
 * same order, with only the runs of consecutive badges joined.
 */
class MdImageGroupingTest {

    private fun image(url: String, alt: String = "img") = MdBlock.Image(alt, url)
    private fun badge(name: String) = image("https://img.shields.io/badge/$name-000", name)

    @Test fun consecutiveBadgesBecomeOneGroup() {
        val blocks = listOf(badge("Python"), badge("HTML5"), badge("CSS3"))
        val groups = MdImageGrouping.group(blocks) { AuthorImageSizing.isBadge(it) }
        assertEquals(1, groups.size)
        assertEquals(3, groups.single().size)
    }

    @Test fun theBannerAndTheGraphAreNeverGrouped() {
        val banner = image("https://github.com/o/r/blob/main/assets/banner.png", "banner")
        val graph = image("https://raw.githubusercontent.com/o/r/output/g.svg", "graph")
        val blocks = listOf(banner, badge("Python"), badge("Git"), graph)
        val groups = MdImageGrouping.group(blocks) { AuthorImageSizing.isBadge(it) }
        assertEquals(3, groups.size)
        assertEquals(listOf(banner), groups[0])
        assertEquals(2, groups[1].size)
        assertEquals(listOf(graph), groups[2])
    }

    @Test fun noBlockIsDroppedOrReordered() {
        val blocks = listOf(
            MdBlock.Heading(2, listOf(MdRun("Hi"))),
            badge("Python"),
            MdBlock.Paragraph(listOf(MdRun("text"))),
            badge("Git"),
            MdBlock.Image("graph", "https://raw.githubusercontent.com/o/r/output/g.svg"),
            MdBlock.Rule,
        )
        val groups = MdImageGrouping.group(blocks) { AuthorImageSizing.isBadge(it) }
        assertEquals("every block appears exactly once", blocks, groups.flatten())
    }

    @Test fun aRunAtTheEndIsStillGrouped() {
        val blocks = listOf(MdBlock.Rule, badge("A"), badge("B"))
        val groups = MdImageGrouping.group(blocks) { AuthorImageSizing.isBadge(it) }
        assertEquals(2, groups.size)
        assertEquals(2, groups.last().size)
    }

    @Test fun aDocumentWithNoBadgesIsUnchanged() {
        val blocks = listOf(MdBlock.Rule, MdBlock.Code(null, "x"), MdBlock.Rule)
        val groups = MdImageGrouping.group(blocks) { AuthorImageSizing.isBadge(it) }
        assertEquals(blocks, groups.flatten())
        assertTrue(groups.all { it.size == 1 })
    }
}
