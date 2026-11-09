// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.markdown

/**
 * Line-based block parser behind [Markdown.parse]. Internal on purpose: tests and the reader use the public
 * [Markdown] object, so this class can change shape without touching callers.
 */
internal class Parser(private val source: String) {

    fun blocks(): List<MdBlock> {
        val lines = stripComments(source.replace("\r\n", "\n").replace('\r', '\n')).split('\n')
        val out = mutableListOf<MdBlock>()
        val paragraph = mutableListOf<String>()
        var i = 0

        fun flush() {
            if (paragraph.isEmpty()) return
            out += paragraphBlock(paragraph.toList())
            paragraph.clear()
        }

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> {
                    flush()
                    i++
                }

                isFence(trimmed) -> {
                    flush()
                    val fence = trimmed.takeWhile { it == '`' || it == '~' }
                    val language = trimmed.drop(fence.length).trim().ifBlank { null }
                    val body = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith(fence)) {
                        if (body.isNotEmpty()) body.append('\n')
                        body.append(lines[i])
                        i++
                    }
                    if (i < lines.size) i++
                    out += MdBlock.Code(language, body.toString())
                }

                HORIZONTAL_RULE.matches(trimmed) -> {
                    flush()
                    out += MdBlock.Rule
                    i++
                }

                HEADING.find(line) != null -> {
                    flush()
                    val match = HEADING.find(line)!!
                    out += MdBlock.Heading(
                        level = match.groupValues[1].length,
                        runs = Inline.parse(match.groupValues[2].trim().trimEnd('#').trim()),
                    )
                    i++
                }

                trimmed.startsWith(">") -> {
                    flush()
                    val quoted = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) {
                        quoted += lines[i].trim().removePrefix(">").removePrefix(" ").trimEnd()
                        i++
                    }
                    out += MdBlock.Quote(Inline.parse(joinSoft(quoted)))
                }

                isTableStart(lines, i) -> {
                    flush()
                    i = readTable(lines, i, out)
                }

                marker(line) != null -> {
                    flush()
                    i = readList(lines, i, out)
                }

                Markdown.opensHtml(trimmed) -> {
                    flush()
                    i = readHtml(lines, i, out)
                }

                else -> {
                    // Keep trailing spaces: they are how Markdown asks for a hard break.
                    paragraph += line.trimStart()
                    i++
                }
            }
        }
        flush()
        return out
    }

    /** A single image on its own line becomes an [MdBlock.Image]; everything else stays inline. */
    private fun paragraphBlock(lines: List<String>): MdBlock {
        val joined = joinSoft(lines)
        if (lines.size == 1) {
            val image = STANDALONE_IMAGE.matchEntire(lines.single().trim())
            if (image != null) {
                val url = image.groupValues[2].trim()
                if (url.isNotBlank()) return MdBlock.Image(image.groupValues[1].ifBlank { "image" }, url)
            }
        }
        return MdBlock.Paragraph(Inline.parse(joined))
    }

    /**
     * Joins wrapped lines the way Markdown does: with a space, except after a hard break (two trailing spaces or
     * a trailing backslash). Every word survives, which is what a licence needs.
     */
    private fun joinSoft(lines: List<String>): String {
        val sb = StringBuilder()
        lines.forEachIndexed { index, raw ->
            if (index > 0) {
                val previous = lines[index - 1]
                sb.append(if (previous.endsWith("  ") || previous.endsWith("\\")) '\n' else ' ')
            }
            sb.append(raw.trimEnd().removeSuffix("\\").trimEnd())
        }
        return sb.toString()
    }

    private fun readList(lines: List<String>, start: Int, out: MutableList<MdBlock>): Int {
        val items = mutableListOf<MdItem>()
        var i = start
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) break
            val found = marker(line)
            if (found == null) {
                val wraps = line.firstOrNull()?.isWhitespace() == true && items.isNotEmpty()
                if (!wraps) break
                // A wrapped line inside an item: keep its words with the item above it.
                val last = items.removeAt(items.lastIndex)
                items += last.copy(runs = last.runs + Inline.parse(" " + line.trim()))
                i++
                continue
            }
            val indent = found.groupValues[1].replace("\t", "    ").length
            val token = found.groupValues[2]
            items += MdItem(
                level = (indent / 2).coerceIn(0, 3),
                ordered = token.first().isDigit(),
                number = token.dropLast(1).toIntOrNull() ?: 0,
                runs = Inline.parse(found.groupValues[3].trim()),
            )
            i++
        }
        if (items.isNotEmpty()) out += MdBlock.Listing(items)
        return i
    }

    private fun readTable(lines: List<String>, start: Int, out: MutableList<MdBlock>): Int {
        val header = cells(lines[start]).map { Inline.parse(it) }
        val rows = mutableListOf<List<List<MdRun>>>()
        var i = start + 2
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank() || !line.contains('|')) break
            rows += cells(line).map { Inline.parse(it) }
            i++
        }
        out += MdBlock.Table(header, rows)
        return i
    }

    /**
     * Collects a run of HTML and reports what it contained, without rendering any of it.
     *
     * Only runs that actually embed something (`img`, `picture`, `iframe`, `video`, `svg`, `audio`) produce a
     * notice; a bare wrapper such as `<div align="center">` around nothing adds no noise.
     */
    private fun readHtml(lines: List<String>, start: Int, out: MutableList<MdBlock>): Int {
        val run = StringBuilder()
        var i = start
        while (i < lines.size) {
            val trimmed = lines[i].trim()
            if (trimmed.isEmpty() || !Markdown.opensHtml(trimmed)) break
            run.append(trimmed).append('\n')
            i++
        }
        val html = run.toString()
        if (EMBED.containsMatchIn(html)) {
            val alts = ALT.findAll(html)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .toList()
            out += MdBlock.Omitted(alts)
        }
        return i
    }

    /** Splits a table row into trimmed cells, tolerating the optional outer pipes. */
    private fun cells(line: String): List<String> {
        var body = line.trim()
        if (body.startsWith("|")) body = body.substring(1)
        if (body.endsWith("|")) body = body.dropLast(1)
        return body.split('|').map { it.trim() }
    }

    private fun isTableStart(lines: List<String>, index: Int): Boolean {
        if (!lines[index].contains('|')) return false
        val separator = lines.getOrNull(index + 1)?.trim() ?: return false
        if (!separator.contains('|') || !separator.contains('-')) return false
        return separator.all { it == '|' || it == '-' || it == ':' || it.isWhitespace() }
    }

    private fun isFence(trimmed: String): Boolean =
        trimmed.startsWith("```") || trimmed.startsWith("~~~")

    private fun marker(line: String): MatchResult? = BULLET.find(line) ?: NUMBERED.find(line)

    /**
     * Removes HTML comments, including the multi-line comment the profile README carries. Comment content is
     * never rendered, so template instructions inside it cannot leak into the UI.
     */
    private fun stripComments(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val start = text.indexOf("<!--", i)
            if (start < 0) {
                sb.append(text, i, text.length)
                break
            }
            sb.append(text, i, start)
            val end = text.indexOf("-->", start + 4)
            if (end < 0) break
            i = end + 3
            sb.append('\n')
        }
        return sb.toString()
    }

    private companion object {
        val HEADING = Regex("^ {0,3}(#{1,6})\\s+(.*)$")
        val HORIZONTAL_RULE = Regex("^ {0,3}([-*_])(?:\\s*\\1){2,}\\s*$")
        val BULLET = Regex("^(\\s*)([-*+])\\s+(.*)$")
        val NUMBERED = Regex("^(\\s*)(\\d{1,9}[.)])\\s+(.*)$")
        val STANDALONE_IMAGE = Regex("^!\\[([^\\]]*)\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)$")
        val ALT = Regex("alt\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
        val EMBED = Regex("<\\s*(img|picture|iframe|video|svg|audio)\\b", RegexOption.IGNORE_CASE)
    }
}

