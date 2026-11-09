// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.markdown

import com.bookcovermatcher.core.markdown.MdBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitHub emoji shortcodes (v2.6.0 crash-fix 1).
 *
 * The author's README writes `## :computer: Tech Stack`; GitHub draws `💻 Tech Stack` and this app drew the
 * literal text. The rules that matter are that a *known* name becomes an emoji, and that everything else — an
 * unknown name, a time, a URL, code — is left exactly as it was written.
 */
class EmojiShortcodeTest {

    @Test fun theReadmesComputerShortcodeBecomesItsEmoji() {
        assertEquals("\uD83D\uDCBB", EmojiShortcodes.expand(":computer:"))
        assertEquals(
            "## \uD83D\uDCBB Tech Stack",
            EmojiShortcodes.expand("## :computer: Tech Stack"),
        )
    }

    @Test fun theHeadingIsExpandedByTheParserSoTheRendererDrawsEmoji() {
        val heading = Markdown.parse("## :computer: Tech Stack").filterIsInstance<MdBlock.Heading>().single()
        assertEquals("\uD83D\uDCBB Tech Stack", heading.runs.joinToString("") { it.text })
    }

    @Test fun otherShortcodesInUseAreExpanded() {
        assertEquals("\uD83C\uDF89", EmojiShortcodes.expand(":tada:"))
        assertEquals("\u2728", EmojiShortcodes.expand(":sparkles:"))
        assertEquals("\uD83D\uDD25", EmojiShortcodes.expand(":fire:"))
        assertEquals("\uD83D\uDC4D", EmojiShortcodes.expand(":+1:"))
        assertEquals("I \uD83D\uDC4B you", EmojiShortcodes.expand("I :wave: you"))
    }

    @Test fun anUnknownShortcodeIsKeptRatherThanDeleted() {
        assertEquals(":not_a_real_emoji:", EmojiShortcodes.expand(":not_a_real_emoji:"))
        // A known shortcode next to an unknown one: only the known one changes.
        assertEquals("a :nope: b \uD83D\uDCBB c", EmojiShortcodes.expand("a :nope: b :computer: c"))
    }

    @Test fun ordinaryColonDelimitedTextAndUrlsAreUntouched() {
        assertEquals("12:30:45", EmojiShortcodes.expand("12:30:45"))
        assertEquals("key:value:other", EmojiShortcodes.expand("key:value:other"))
        assertEquals("C:\\Users\\x", EmojiShortcodes.expand("C:\\Users\\x"))
        val url = "https://img.shields.io/badge/Python-3670A0?logo=python"
        assertEquals(url, EmojiShortcodes.expand(url))
        // A link target is never prose, so its colons stay colons.
        val link = Markdown.parse("see [the docs](https://example.org/a:b:c)").filterIsInstance<MdBlock.Paragraph>().single()
        val run = link.runs.single { it.url != null }
        assertEquals("https://example.org/a:b:c", run.url)
        assertFalse(run.text.contains("\uD83D"))
    }

    @Test fun shortcodeLookingTextInsideInlineCodeIsNotExpanded() {
        val paragraph = Markdown.parse("write `:computer:` to get an emoji")
            .filterIsInstance<MdBlock.Paragraph>()
            .single()
        val code = paragraph.runs.single { it.code }
        assertEquals(":computer:", code.text)
        assertTrue(paragraph.runs.none { it.text.contains("\uD83D\uDCBB") })
    }

    @Test fun shortcodeLookingTextInsideAFencedBlockIsNotExpanded() {
        val blocks = Markdown.parse("```\n:computer: Tech Stack\n```")
        val code = blocks.filterIsInstance<MdBlock.Code>().single()
        assertEquals(":computer: Tech Stack", code.text)
    }

    @Test fun aSingleColonIsLeftAlone() {
        assertEquals(":", EmojiShortcodes.expand(":"))
        assertEquals("a:b", EmojiShortcodes.expand("a:b"))
        assertEquals("", EmojiShortcodes.expand(""))
    }
}
