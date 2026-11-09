// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.markdown

import com.bookcovermatcher.core.author.AuthorProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The Markdown reader added for the licence page and the author's profile README (v2.6.0).
 *
 * The most important test here is [theShippedLicenseLosesNoText]: this reader draws the GNU GPL, so a parser
 * that swallows a line would be a legal-accuracy defect, not a cosmetic one.
 */
class MarkdownTest {

    private fun blocks(source: String) = Markdown.parse(source)

    private fun plain(blocks: List<MdBlock>): String = blocks.joinToString(" ") { block ->
        when (block) {
            is MdBlock.Heading -> block.runs.joinToString("") { it.text }
            is MdBlock.Paragraph -> block.runs.joinToString("") { it.text }
            is MdBlock.Quote -> block.runs.joinToString("") { it.text }
            is MdBlock.Listing -> block.items.joinToString(" ") { item ->
                val marker = if (item.ordered) "${item.number}." else "-"
                "$marker " + item.runs.joinToString("") { it.text }
            }
            is MdBlock.Code -> block.text
            is MdBlock.Table -> buildString {
                block.header.forEach { cell -> append(cell.joinToString("") { it.text }).append(' ') }
                block.rows.forEach { row ->
                    row.forEach { cell -> append(cell.joinToString("") { it.text }).append(' ') }
                }
            }
            is MdBlock.Image -> block.alt
            is MdBlock.Omitted -> block.alts.joinToString(" ")
            MdBlock.Rule -> ""
        }
    }

    @Test fun headingsKeepTheirLevelAndText() {
        val headings = blocks("# One\n\n## Two\n\n### Three\n\n###### Six").filterIsInstance<MdBlock.Heading>()
        assertEquals(listOf(1, 2, 3, 6), headings.map { it.level })
        assertEquals(listOf("One", "Two", "Three", "Six"), headings.map { it.runs.single().text })
    }

    @Test fun aWrappedParagraphJoinsWithASingleSpace() {
        val paragraphs = blocks("Rights that are\nprotected by law\n\nSecond paragraph.")
            .filterIsInstance<MdBlock.Paragraph>()
        assertEquals(2, paragraphs.size)
        assertEquals("Rights that are protected by law", paragraphs[0].runs.single().text)
    }

    @Test fun twoTrailingSpacesForceALineBreak() {
        val text = blocks("first line  \nsecond line").filterIsInstance<MdBlock.Paragraph>()
            .single().runs.single().text
        assertEquals("first line\nsecond line", text)
    }

    @Test fun emphasisCodeAndStrikeBecomeRuns() {
        val runs = Markdown.parseInline("plain **bold** and *italic* and `code` and ~~gone~~")
        assertTrue(runs.any { it.bold && it.text == "bold" })
        assertTrue(runs.any { it.italic && it.text == "italic" })
        assertTrue(runs.any { it.code && it.text == "code" })
        assertTrue(runs.any { it.strike && it.text == "gone" })
    }

    @Test fun aLinkCarriesItsTarget() {
        val link = Markdown.parseInline("see [the terms](https://example.org/terms) now").single { it.url != null }
        assertEquals("the terms", link.text)
        assertEquals("https://example.org/terms", link.url)
        assertEquals("https://example.org/terms", AuthorProfile.linkTarget(link.url))
    }

    @Test fun anUnsafeLinkIsParsedButRefused() {
        val link = Markdown.parseInline("click [here](javascript:alert(1))").single { it.url != null }
        assertEquals("javascript:alert(1)", link.url)
        assertEquals(null, AuthorProfile.linkTarget(link.url))
    }

    @Test fun bulletsNestAndReportTheirLevel() {
        val items = blocks("- one\n- two\n  - nested\n    1. deep")
            .filterIsInstance<MdBlock.Listing>().single().items
        assertEquals(listOf(0, 0, 1, 2), items.map { it.level })
        assertFalse(items[0].ordered)
        assertTrue(items[3].ordered)
        assertEquals(1, items[3].number)
    }

    @Test fun aBlockquoteKeepsItsWords() {
        val quote = blocks("> quoted words\n> second line").filterIsInstance<MdBlock.Quote>().single()
        assertEquals("quoted words second line", quote.runs.single().text)
    }

    @Test fun aFencedBlockKeepsItsCodeVerbatim() {
        val code = blocks("```kotlin\nval x = 1\nval y = 2\n```").filterIsInstance<MdBlock.Code>().single()
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1\nval y = 2", code.text)
    }

    @Test fun aTableKeepsItsHeaderAndRows() {
        val table = blocks("| Term | Meaning |\n|---|---|\n| GPL | licence |\n| MIT | licence |")
            .filterIsInstance<MdBlock.Table>().single()
        assertEquals(listOf("Term", "Meaning"), table.header.map { it.single().text })
        assertEquals(2, table.rows.size)
        assertEquals("GPL", table.rows[0][0].single().text)
    }

    @Test fun aRuleIsItsOwnBlock() {
        assertTrue(blocks("---").single() is MdBlock.Rule)
    }

    @Test fun htmlWithBadgesIsReportedNotRendered() {
        val parsed = blocks(
            "<div align=\"center\">\n" +
                "  <img alt=\"Python\" src=\"https://img.shields.io/badge/Python-1\">\n" +
                "  <img alt=\"Kotlin\" src=\"https://img.shields.io/badge/Kotlin-2\">\n" +
                "</div>",
        )
        val omitted = parsed.filterIsInstance<MdBlock.Omitted>().single()
        assertEquals(listOf("Python", "Kotlin"), omitted.alts)
        assertTrue("HTML must not leak into the text", parsed.none { it is MdBlock.Paragraph })
    }

    @Test fun anHtmlWrapperWithoutEmbedsIsSilent() {
        assertTrue(blocks("<div align=\"center\">\n</div>").isEmpty())
    }

    @Test fun licencePlaceholdersAreTextNotHtml() {
        // The GPL uses <one line …> and <year> as placeholders; treating them as tags would delete legal text.
        val parsed = blocks("<one line to give the program's name and a brief idea of what it does.>")
        val paragraph = parsed.filterIsInstance<MdBlock.Paragraph>().single()
        assertTrue(paragraph.runs.single().text.startsWith("<one line to give the program's name"))
        assertTrue(parsed.none { it is MdBlock.Omitted })
    }

    @Test fun anHtmlCommentIsRemovedEntirely() {
        val parsed = blocks("before\n\n<!--\n**template** text with a list:\n- one\n- two\n-->\n\nafter")
        val text = plain(parsed)
        assertTrue(text.contains("before"))
        assertTrue(text.contains("after"))
        assertFalse("comment content must never render: $text", text.contains("template"))
    }

    @Test fun aStandaloneImageBecomesAnImageBlock() {
        val image = blocks("![banner](https://github.com/user-attachments/assets/abc)")
            .filterIsInstance<MdBlock.Image>().single()
        assertEquals("banner", image.alt)
        assertEquals("https://github.com/user-attachments/assets/abc", image.url)
    }

    @Test fun anInlineImageBecomesLinkedAltText() {
        val image = Markdown.parseInline("logo ![alt text](https://example.org/a.png) end")
            .single { it.url == "https://example.org/a.png" }
        assertEquals("alt text", image.text)
    }

    @Test fun unknownMarkupStaysVisible() {
        val text = plain(blocks("a <not-a-tag> and a stray * star"))
        assertTrue(text, text.contains("<not-a-tag>"))
        assertTrue(text, text.contains("stray"))
    }

    @Test fun theShippedLicenseLosesNoText() {
        val license = listOf(File("../LICENSE"), File("LICENSE")).firstOrNull { it.isFile }
        assumeTrue("LICENSE is not readable from the test working directory", license != null)
        val source = license!!.readText()

        // Compare words, not punctuation: the reader styles markers, brackets and backticks, but it must never
        // drop a word of the licence. That is the property this test guarantees.
        fun words(text: String) = text.replace(Regex("[^A-Za-z0-9]+"), " ").trim().lowercase()

        val rendered = words(plain(blocks(source)))
        val lost = source.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("<!--") || it.contains("-->") || it.startsWith("```") }
            .filter { line ->
                val needle = words(line.trimStart('#').trim())
                needle.isNotEmpty() && !rendered.contains(needle)
            }

        assertEquals("lines of the licence whose words the reader dropped: $lost", emptyList<String>(), lost)
        assertTrue("the licence must be long", rendered.length > 20_000)
    }

    @Test fun theBundledReadmeParsesIntoReadableBlocks() {
        val readme = listOf(
            File("src/main/assets/author/README.md"),
            File("app/src/main/assets/author/README.md"),
        ).firstOrNull { it.isFile }
        assumeTrue("the bundled README asset is not readable from the working directory", readme != null)

        val parsed = Markdown.parse(readme!!.readText())
        assertNotNull(parsed)
        val headings = parsed.filterIsInstance<MdBlock.Heading>()
            .map { heading -> heading.runs.joinToString("") { it.text } }
        assertTrue("the real README heading should be present: $headings", headings.any { it.contains("Polo") })
        val text = plain(parsed)
        assertFalse("the template comment must not render", text.contains("Here are some ideas"))
        assertTrue("badges should be reported, not executed", parsed.any { it is MdBlock.Omitted })
    }
}

