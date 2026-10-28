package com.bookcovermatcher.core.excel.comments

import com.bookcovermatcher.core.excel.CellRange
import com.bookcovermatcher.core.excel.ZipSource
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/** A cell that carried a value: zero-based row/column plus its text. */
data class CellValue(val row: Int, val col: Int, val text: String)

/**
 * Reads a worksheet's cells and the workbook's shared strings.
 *
 * Only what the catalogue features need: text values (shared, inline, formula strings), numbers and
 * booleans. A number stays the text Excel stored — nothing is guessed, and no date conversion is
 * invented. Reading is bounded by [maxRows]/[maxCols] so a hostile sheet cannot exhaust memory.
 */
object SheetCells {

    const val MAX_ROWS = 20_000
    const val MAX_COLS = 512

    /** `A1` / `$C$5` -> zero-based (row, col); null when it is not a single cell. */
    fun refToCell(ref: String?): Pair<Int, Int>? {
        val cleaned = (ref ?: "").trim().uppercase().replace("$", "")
        if (cleaned.isEmpty()) return null
        val letters = cleaned.takeWhile { it in 'A'..'Z' }
        val digits = cleaned.drop(letters.length)
        if (letters.isEmpty() || digits.isEmpty()) return null
        val row = digits.toLongOrNull()?.minus(1) ?: return null
        if (row < 0 || row > Int.MAX_VALUE) return null
        return row.toInt() to CellRange.columnLettersToIndex(letters)
    }

    /** Shared-string table (`xl/sharedStrings.xml`), index -> text. Empty when the part is absent. */
    fun sharedStrings(zip: ZipSource): List<String> {
        val xml = zip.readText("xl/sharedStrings.xml") ?: return emptyList()
        val out = ArrayList<String>()
        var current: StringBuilder? = null
        var inItem = false
        var inText = false

        val handler = object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes?) {
                when (XlsxXml.local(qName ?: "")) {
                    "si" -> { inItem = true; current = StringBuilder() }
                    "t" -> if (inItem) inText = true
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inText) current?.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String) {
                when (XlsxXml.local(qName ?: "")) {
                    "t" -> inText = false
                    "si" -> {
                        if (inItem) out.add(current?.toString().orEmpty())
                        inItem = false
                        current = null
                    }
                }
            }
        }
        XlsxXml.parse(xml, handler)
        return out
    }

    /** Signals the cell handler to stop at the row limit; the parse aborts and what was read stays. */
    private object StopReading : RuntimeException() {
        private fun readResolve(): Any = StopReading
    }

    /**
     * The worksheet's **declared** used range (`<dimension ref="A1:P120">`), or null when the part has
     * none. This is Excel's own declaration, not a scan of the cells; an empty sheet may declare `A1`.
     */
    fun dimension(zip: ZipSource, sheetPath: String): String? {
        val xml = zip.readText(sheetPath) ?: return null
        var ref: String? = null
        val handler = object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes?) {
                if (ref != null || XlsxXml.local(qName ?: "") != "dimension") return
                ref = attributes?.let { WorkbookSheets.attributeByLocal(it, "ref") }
            }
        }
        XlsxXml.parse(xml, handler)
        return ref
    }

    /**
     * Every non-blank cell of [sheetPath], in document order. Malformed XML yields what could be read
     * before the error, never an exception.
     */
    fun read(
        zip: ZipSource,
        sheetPath: String,
        shared: List<String> = sharedStrings(zip),
        maxRows: Int = MAX_ROWS,
        maxCols: Int = MAX_COLS,
    ): List<CellValue> {
        val xml = zip.readText(sheetPath) ?: return emptyList()
        val out = ArrayList<CellValue>()

        var row = -1
        var col = -1
        var type = ""
        var value: StringBuilder? = null
        var inValue = false
        var inline: StringBuilder? = null
        var inInline = false

        val handler = object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes?) {
                when (XlsxXml.local(qName ?: "")) {
                    "row" -> {
                        val declared = attributes?.let { WorkbookSheets.attributeByLocal(it, "r") }?.toIntOrNull()
                        row = if (declared != null) declared - 1 else row + 1
                        if (row >= maxRows) throw StopReading
                        col = -1
                    }
                    "c" -> {
                        val cell = refToCell(attributes?.let { WorkbookSheets.attributeByLocal(it, "r") })
                        col = cell?.second ?: (col + 1)
                        type = attributes?.let { WorkbookSheets.attributeByLocal(it, "t") } ?: ""
                        value = StringBuilder()
                        inline = StringBuilder()
                        inValue = false
                        inInline = false
                    }
                    "v" -> if (value != null) inValue = true
                    "is" -> inInline = true
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inValue) value?.append(ch, start, length) else if (inInline) inline?.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String) {
                when (XlsxXml.local(qName ?: "")) {
                    "v" -> inValue = false
                    "is" -> inInline = false
                    "c" -> {
                        val raw = value?.toString().orEmpty()
                        val text = when (type.lowercase()) {
                            "s" -> raw.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                            "inlinestr" -> inline?.toString().orEmpty()
                            "b" -> when (raw.trim()) { "1" -> "TRUE"; "0" -> "FALSE"; else -> raw }
                            else -> raw
                        }.trim()
                        if (text.isNotEmpty() && row >= 0 && col >= 0 && col < maxCols) {
                            out.add(CellValue(row, col, text))
                        }
                        value = null
                        inline = null
                        inValue = false
                        inInline = false
                    }
                }
            }
        }

        // The row limit aborts the SAX run; everything collected before it is what the caller gets.
        XlsxXml.parse(xml, handler)
        return out
    }
}
