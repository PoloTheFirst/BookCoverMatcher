package com.bookcovermatcher.core.excel.comments

import com.bookcovermatcher.core.excel.CellRange
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * Parser for a legacy comments part (`xl/comments1.xml` and friends).
 *
 * The comments XML is the **authoritative** source for which cell a note belongs to and for its text.
 * It never references an image: images live in the VML drawing.
 */
object LegacyCommentParser {

    data class Comment(val ref: String, val row: Int, val col: Int, val text: String, val author: String?)

    data class Parsed(val authors: List<String>, val comments: List<Comment>, val wellFormed: Boolean)

    /** `C5` / `$C$5` / `aA10` -> zero-based (row, col); null when the ref is not a single cell. */
    fun cellOf(ref: String): Pair<Int, Int>? {
        val cleaned = ref.trim().uppercase().replace("$", "")
        val letters = cleaned.takeWhile { it in 'A'..'Z' }
        val digits = cleaned.drop(letters.length)
        if (letters.isEmpty() || digits.isEmpty()) return null
        val row = digits.toLongOrNull()?.minus(1) ?: return null
        if (row < 0 || row > Int.MAX_VALUE) return null
        return row.toInt() to CellRange.columnLettersToIndex(letters)
    }

    fun parse(xml: String): Parsed {
        val authors = ArrayList<String>()
        val comments = ArrayList<Comment>()

        var inAuthor = false
        var authorBuf: StringBuilder? = null

        var ref: String? = null
        var authorId: Int? = null
        var textBuf: StringBuilder? = null
        var inText = false
        var inRunText = false
        var phoneticDepth = 0

        val handler = object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes?) {
                when (XlsxXml.local(qName ?: "")) {
                    "author" -> { inAuthor = true; authorBuf = StringBuilder() }
                    "comment" -> {
                        if (attributes != null) {
                            ref = WorkbookSheets.attributeByLocal(attributes, "ref")
                            authorId = WorkbookSheets.attributeByLocal(attributes, "authorid")?.toIntOrNull()
                        }
                        textBuf = StringBuilder()
                        inText = false
                    }
                    "text" -> inText = true
                    "rph" -> if (inText) phoneticDepth++
                    "t" -> if (inText && phoneticDepth == 0) inRunText = true
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inAuthor) authorBuf?.append(ch, start, length)
                if (inRunText) textBuf?.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String) {
                when (XlsxXml.local(qName ?: "")) {
                    "author" -> {
                        if (inAuthor) authors.add((authorBuf?.toString() ?: "").trim())
                        inAuthor = false; authorBuf = null
                    }
                    "t" -> inRunText = false
                    "rph" -> if (phoneticDepth > 0) phoneticDepth--
                    "text" -> inText = false
                    "comment" -> {
                        val cellRef = ref
                        if (cellRef != null) {
                            val cell = cellOf(cellRef)
                            if (cell == null) {
                                // Kept as a diagnostic by the scanner: a ref that is not a single cell
                                // (e.g. "A1:B2") cannot be shown on a grid.
                            } else {
                                comments.add(
                                    Comment(
                                        ref = cellRef.trim().uppercase().replace("$", ""),
                                        row = cell.first,
                                        col = cell.second,
                                        text = (textBuf?.toString() ?: "").replace("\r\n", "\n"),
                                        author = authorId?.let { authors.getOrNull(it) }?.takeIf { it.isNotBlank() },
                                    ),
                                )
                            }
                        }
                        ref = null; authorId = null; textBuf = null; inText = false
                    }
                }
            }
        }

        val ok = XlsxXml.parse(xml, handler)
        return Parsed(authors, comments, ok)
    }
}
