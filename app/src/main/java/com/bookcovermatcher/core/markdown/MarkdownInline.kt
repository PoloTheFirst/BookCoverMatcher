// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.markdown

/**
 * Inline parser: emphasis, code spans, links, images and bare `<https://…>` autolinks.
 *
 * It produces flat [MdRun]s. Everything it does not recognise stays literal text, so a stray `*` or `<` in a
 * legal document can never delete words.
 */
internal object Inline {

    fun parse(text: String): List<MdRun> {
        val out = mutableListOf<MdRun>()
        read(text, 0, text.length, out, bold = false, italic = false, strike = false, url = null)
        return merge(out)
    }

    private fun read(
        s: String,
        start: Int,
        end: Int,
        out: MutableList<MdRun>,
        bold: Boolean,
        italic: Boolean,
        strike: Boolean,
        url: String?,
    ) {
        var i = start
        val buffer = StringBuilder()

        fun flush() {
            if (buffer.isEmpty()) return
            out += MdRun(buffer.toString(), bold = bold, italic = italic, strike = strike, url = url)
            buffer.clear()
        }

        while (i < end) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < end && s[i + 1] in ESCAPABLE -> {
                    buffer.append(s[i + 1])
                    i += 2
                }

                c == '`' -> {
                    val close = s.indexOf('`', i + 1).takeIf { it in (i + 1) until end }
                    if (close == null) {
                        buffer.append(c)
                        i++
                    } else {
                        flush()
                        out += MdRun(s.substring(i + 1, close), code = true, url = url)
                        i = close + 1
                    }
                }

                c == '!' && i + 1 < end && s[i + 1] == '[' -> {
                    val link = findLink(s, i + 1, end)
                    if (link == null) {
                        buffer.append(c)
                        i++
                    } else {
                        // An inline image is shown as its alt text, linked to the image it points at.
                        flush()
                        val alt = s.substring(link.labelStart, link.labelEnd).trim()
                        out += MdRun(alt.ifBlank { "image" }, italic = true, url = link.url)
                        i = link.next
                    }
                }

                c == '[' -> {
                    val link = findLink(s, i, end)
                    if (link == null) {
                        buffer.append(c)
                        i++
                    } else {
                        flush()
                        read(s, link.labelStart, link.labelEnd, out, bold, italic, strike, link.url)
                        i = link.next
                    }
                }

                (c == '*' || c == '_') && i + 1 < end && s[i + 1] == c -> {
                    val close = s.indexOf(s.substring(i, i + 2), i + 2).takeIf { it in (i + 2) until end }
                    if (close == null) {
                        buffer.append(c)
                        i++
                    } else {
                        flush()
                        read(s, i + 2, close, out, bold = true, italic, strike, url)
                        i = close + 2
                    }
                }

                c == '~' && i + 1 < end && s[i + 1] == '~' -> {
                    val close = s.indexOf("~~", i + 2).takeIf { it in (i + 2) until end }
                    if (close == null) {
                        buffer.append(c)
                        i++
                    } else {
                        flush()
                        read(s, i + 2, close, out, bold, italic, strike = true, url)
                        i = close + 2
                    }
                }

                c == '*' || c == '_' -> {
                    val close = s.indexOf(c, i + 1).takeIf { it in (i + 1) until end }
                    if (close == null) {
                        buffer.append(c)
                        i++
                    } else {
                        flush()
                        read(s, i + 1, close, out, bold, italic = true, strike, url)
                        i = close + 1
                    }
                }

                c == '<' -> {
                    val close = s.indexOf('>', i + 1)
                    val inner = if (close in (i + 1) until end) s.substring(i + 1, close) else ""
                    if (inner.startsWith("https://") || inner.startsWith("http://")) {
                        flush()
                        // Keep the angle brackets: the licence texts contain them, and the words must survive.
                        out += MdRun("<$inner>", bold = bold, italic = italic, strike = strike, url = inner)
                        i = close + 1
                    } else {
                        buffer.append(c)
                        i++
                    }
                }

                else -> {
                    buffer.append(c)
                    i++
                }
            }
        }
        flush()
    }

    /** `[label](url)` starting at [at] (the index of `[`); the URL may carry a `"title"`. */
    private fun findLink(s: String, at: Int, end: Int): Link? {
        var depth = 0
        var i = at
        while (i < end) {
            when (s[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            i++
        }
        if (i >= end || s[i] != ']') return null
        val labelStart = at + 1
        val labelEnd = i
        if (i + 1 >= end || s[i + 1] != '(') return null
        val open = i + 1
        var close = open + 1
        var parens = 0
        while (close < end) {
            when (s[close]) {
                '(' -> parens++
                ')' -> if (parens == 0) break else parens--
            }
            close++
        }
        if (close >= end || s[close] != ')') return null
        val target = s.substring(open + 1, close).trim().substringBefore(' ').trim()
        if (target.isEmpty()) return null
        return Link(labelStart, labelEnd, target, close + 1)
    }

    /** Merges neighbouring runs that share styling, so a paragraph is not one run per character. */
    private fun merge(runs: List<MdRun>): List<MdRun> {
        if (runs.size < 2) return runs
        val out = mutableListOf<MdRun>()
        for (run in runs) {
            val last = out.lastOrNull()
            val same = last != null &&
                last.bold == run.bold &&
                last.italic == run.italic &&
                last.code == run.code &&
                last.strike == run.strike &&
                last.url == run.url
            if (same && last != null) {
                out[out.lastIndex] = last.copy(text = last.text + run.text)
            } else {
                out += run
            }
        }
        return out
    }

    private data class Link(val labelStart: Int, val labelEnd: Int, val url: String, val next: Int)

    /** Characters a backslash may escape, following CommonMark's "ASCII punctuation" rule. */
    private val ESCAPABLE = "\\`*_{}[]()#+-.!|<>~\"'".toSet()
}

