// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.markdown

/**
 * One span of inline text: the words the reader draws, plus the styling and an optional link.
 *
 * A flat run list rather than a nested tree keeps the reader simple and the tests readable: emphasis inside a
 * link becomes two runs that both carry the same `url`.
 */
data class MdRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    val url: String? = null,
    /** True when this run came from `![alt](src)`, so the reader can treat it as a picture, not a link. */
    val image: Boolean = false,
)

/** One row of a list. [level] is 0 for a top-level item and 1 for an item nested under it. */
data class MdItem(
    val level: Int,
    val ordered: Boolean,
    val number: Int,
    val runs: List<MdRun>,
)

/** A block the reader can draw. */
sealed interface MdBlock {
    data class Heading(val level: Int, val runs: List<MdRun>) : MdBlock

    data class Paragraph(val runs: List<MdRun>) : MdBlock

    data class Listing(val items: List<MdItem>) : MdBlock

    data class Code(val language: String?, val text: String) : MdBlock

    data class Quote(val runs: List<MdRun>) : MdBlock

    data class Table(val header: List<List<MdRun>>, val rows: List<List<List<MdRun>>>) : MdBlock

    data object Rule : MdBlock

    data class Image(val alt: String, val url: String) : MdBlock

    /**
     * Embedded HTML this app will not render, with the alt texts it contained.
     *
     * The app never executes HTML, scripts or embedded content: a `<div>` of badge images becomes one line that
     * says what was not displayed. `<one line to give the program's name>` is **not** HTML and is kept as text —
     * see [Markdown.HTML_ELEMENTS], which exists precisely so licence placeholders survive.
     */
    data class Omitted(val alts: List<String>) : MdBlock
}

/**
 * A deliberately small Markdown reader (v2.6.0).
 *
 * It exists because the app must show two very different documents with no new dependency and no network: the
 * canonical GPL text (plain text with `<year>` placeholders) and the third-party notices plus the author's
 * profile README (Markdown, with badge HTML the reader must not execute).
 *
 * Design rules:
 *
 *  * **Nothing is dropped from prose.** Blank lines separate blocks; lines inside a paragraph join with a space,
 *    which is standard Markdown and keeps every word of a licence. A test asserts that each non-blank line of
 *    the shipped `LICENSE` survives parsing.
 *  * **HTML is recognised conservatively.** Only known element names start an HTML run, so
 *    `<one line to give the program's name…>` stays text instead of vanishing.
 *  * **Unknown input is text.** Anything this reader does not understand is drawn literally rather than being
 *    silently swallowed.
 */
object Markdown {

    /** Element names that start an HTML run. Deliberately an allowlist: licence placeholders are not tags. */
    val HTML_ELEMENTS: Set<String> = setOf(
        "a", "b", "blockquote", "br", "center", "code", "dd", "details", "div", "dl", "dt", "em", "font",
        "h1", "h2", "h3", "h4", "h5", "h6", "hr", "i", "iframe", "img", "kbd", "li", "main", "nav", "ol", "p",
        "picture", "pre", "s", "section", "source", "span", "strong", "sub", "summary", "sup", "svg", "table",
        "tbody", "td", "tfoot", "th", "thead", "tr", "u", "ul", "video", "audio", "figure", "figcaption",
    )

    fun parse(source: String): List<MdBlock> = Parser(source).blocks()

    fun parseInline(text: String): List<MdRun> = Inline.parse(text)

    /** True when [trimmed] opens or closes a known HTML element, e.g. `<div align="center">` or `</picture>`. */
    fun opensHtml(trimmed: String): Boolean {
        val tag = TAG_NAME.find(trimmed) ?: return false
        return tag.groupValues[1].lowercase() in HTML_ELEMENTS
    }

    private val TAG_NAME = Regex("^</?([A-Za-z][A-Za-z0-9]*)")
}
