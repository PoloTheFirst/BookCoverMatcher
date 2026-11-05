// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.excel.comments

import com.bookcovermatcher.core.excel.ZipSource
import com.bookcovermatcher.core.format.Formatting
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.time.LocalDate
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * What a **numeric** cell's own number format says the number is.
 *
 * This is read from the workbook's format metadata — `xl/styles.xml` and the cell's `s` attribute — and
 * never from the value: `45292` is a date only when its cell is date-formatted, and a bare number in a
 * `General` cell is never guessed into one. [GENERAL] (and [OTHER]) mean "show the stored text as it is",
 * which is what protects identifiers, serial numbers and `General` cells from being reformatted.
 */
enum class CellFormatKind { GENERAL, DATE, TIME, DATE_TIME, PERCENT, NUMBER, OTHER }

/**
 * One resolved number format: a display rule for a numeric value.
 *
 * [display] returns null when nothing should be changed, which is the honest answer for a general cell,
 * a text cell and a value that cannot be read as a number. The *stored* value is never touched — the
 * caller keeps the raw text and puts this string beside it.
 *
 * Percentages are shown with **at most** [MAX_PERCENT_DECIMALS] decimals, because the stored fraction
 * (`0.123456`) is what the user needs rounded, not truncated; trailing zeros are dropped so `0.5` is
 * `50%` rather than `50.00%`. A cell that is *not* percentage-formatted never gets a `%`.
 */
data class CellNumberFormat(
    val kind: CellFormatKind = CellFormatKind.GENERAL,
) {
    val affectsDisplay: Boolean
        get() = kind == CellFormatKind.DATE || kind == CellFormatKind.TIME ||
            kind == CellFormatKind.DATE_TIME || kind == CellFormatKind.PERCENT ||
            kind == CellFormatKind.NUMBER

    /**
     * The cell's text as a person should read it, or null to keep the stored text.
     *
     * @param date1904 the workbook's own date system (`workbookPr date1904`); 1904 shifts the epoch by
     * 1 462 days, so the same serial is a different day.
     */
    fun display(raw: String, date1904: Boolean = false): String? {
        if (!affectsDisplay) return null
        val text = raw.trim()
        if (text.isEmpty()) return null
        val value = text.toDoubleOrNull() ?: return null
        if (!value.isFinite()) return null
        if (kind == CellFormatKind.PERCENT) return NumberFormats.percent(value)
        // A decimal-formatted measurement follows the app-wide two-decimal display rule.
        if (kind == CellFormatKind.NUMBER) return NumberFormats.decimal(value)
        val parts = NumberFormats.parts(value, date1904) ?: return null
        return when (kind) {
            // A date-only format shows only the date, exactly as the workbook does.
            CellFormatKind.DATE -> parts.date
            CellFormatKind.TIME -> parts.time ?: "00:00"
            CellFormatKind.DATE_TIME -> when {
                parts.date == null -> parts.time
                parts.time == null -> parts.date
                else -> "${parts.date} ${parts.time}"
            }
            else -> null
        }
    }

    companion object {
        /** The most decimals a percentage may show; the underlying value is never rounded or stored. */
        const val MAX_PERCENT_DECIMALS = 2

        val GENERAL = CellNumberFormat(CellFormatKind.GENERAL)
    }
}

/** Date and percentage rendering for [CellNumberFormat]. Nothing here is locale-aware by accident. */
object NumberFormats {

    /** The readable halves of one serial number: either may be absent, both are never invented. */
    data class Parts(val date: String?, val time: String?)

    /**
     * A serial number split into its date and its clock time.
     *
     * Excel counts days from 1899-12-30 for serials of 61 and above, keeps 1899-12-31 as the base for
     * 1..59, and has no real date for serial 0 or for serial 60 (its non-existent 1900-02-29) — those two
     * are reported as unreadable instead of being shifted into a neighbouring day. The 1904 system counts
     * from 1904-01-01 instead. A value with no readable day but a real time still returns that time, which
     * is what a time-only cell (`0.604…` = 14:30) contains.
     */
    fun parts(value: Double, date1904: Boolean): Parts? {
        var days = floor(value).toLong()
        var seconds = ((value - days) * 86_400.0).roundToLong()
        if (seconds >= 86_400L) {
            seconds -= 86_400L
            days += 1
        }
        val day = runCatching {
            when {
                date1904 -> if (days < 0) null else LocalDate.of(1904, 1, 1).plusDays(days)
                days >= 61 -> LocalDate.of(1899, 12, 30).plusDays(days)
                days in 1..59 -> LocalDate.of(1899, 12, 31).plusDays(days)
                // Serial 0 and the fictitious 1900-02-29 have no date to show.
                else -> null
            }
        }.getOrNull()
        if (day == null && seconds == 0L) return null
        val date = day?.let { String.format(Locale.US, "%04d-%02d-%02d", it.year, it.monthValue, it.dayOfMonth) }
        if (seconds == 0L) return Parts(date, null)
        val hours = seconds / 3_600L
        val minutes = (seconds % 3_600L) / 60L
        val rest = seconds % 60L
        val time = if (rest == 0L) {
            String.format(Locale.US, "%02d:%02d", hours, minutes)
        } else {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, rest)
        }
        return Parts(date, time)
    }

    /** A serial number as a date alone; null when it has no readable day. */
    fun date(value: Double, date1904: Boolean): String? = parts(value, date1904)?.date

    /**
     * A stored fraction as a percentage: `0.123456` -> `12.35%`.
     *
     * The fraction is scaled **once** by 100 — a value that is already a percentage (a `12.35%` string)
     * is text, not a numeric cell, so it never reaches this function and is never scaled twice. A result
     * that rounds to zero loses its sign so a tiny negative shows `0%` and not `-0%`.
     */
    fun percent(fraction: Double): String {
        val scaled = fraction * 100.0
        var text = String.format(Locale.US, "%.${CellNumberFormat.MAX_PERCENT_DECIMALS}f", scaled)
        if ('.' in text) text = text.trimEnd('0').trimEnd('.')
        if (text.startsWith("-") && text.drop(1).all { it == '0' || it == '.' }) text = text.drop(1)
        return "$text%"
    }

    /** A decimal-formatted measurement, through the app's one shared display rule. */
    fun decimal(value: Double): String = Formatting.decimal(value)
}

/**
 * The number formats a workbook declares, resolved per cell style index.
 *
 * `xl/styles.xml` gives every `cellXfs/xf` entry a `numFmtId`; ids below 164 are the built-in table in
 * ECMA-376 §18.8.30 and the rest are declared in `numFmts`. Reading them is what lets the app tell a date
 * cell from a price cell without guessing from the number, and it changes nothing in the file.
 *
 * [EMPTY] is what a workbook without a readable `styles.xml` produces: every cell keeps its stored text,
 * exactly as before this feature existed.
 */
class WorkbookStyles private constructor(
    /** True when the workbook counts serial dates from 1904-01-01 instead of 1900-01-01. */
    val date1904: Boolean,
    private val formats: List<CellNumberFormat>,
) {
    val isEmpty: Boolean get() = formats.isEmpty() && !date1904

    /** The format of the style at [styleIndex]; a style the workbook does not declare is general. */
    fun formatAt(styleIndex: Int): CellNumberFormat =
        formats.getOrNull(styleIndex) ?: CellNumberFormat.GENERAL

    /**
     * The display text of one cell, or null when the stored text should be shown unchanged.
     *
     * A text cell is never formatted, even when its style says "percentage": Excel formats numbers, and a
     * shared string that already reads `12.35%` must not be multiplied a second time.
     */
    fun display(styleIndex: Int, raw: String, numeric: Boolean): String? =
        if (!numeric) null else formatAt(styleIndex).display(raw, date1904)

    companion object {
        val EMPTY = WorkbookStyles(false, emptyList())

        /** Reads `xl/styles.xml` and `workbookPr` once for the whole package. */
        fun read(zip: ZipSource): WorkbookStyles {
            val date1904 = runCatching { date1904Of(zip) }.getOrDefault(false)
            val custom = runCatching { customCodes(zip) }.getOrDefault(emptyMap())
            val ids = runCatching { styleFormatIds(zip) }.getOrDefault(emptyList())
            return WorkbookStyles(date1904, ids.map { id -> NumberFormatCodes.of(id, custom[id]) })
        }

        /** `workbookPr date1904="1"`; anything else (including a missing part) is the 1900 system. */
        private fun date1904Of(zip: ZipSource): Boolean {
            val xml = zip.readText("xl/workbook.xml") ?: return false
            var flagged = false
            val handler = object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes?) {
                    if (XlsxXml.local(qName ?: "") != "workbookpr" || attributes == null) return
                    val value = WorkbookSheets.attributeByLocal(attributes, "date1904")?.trim()?.lowercase()
                    flagged = value == "1" || value == "true"
                }
            }
            XlsxXml.parse(xml, handler)
            return flagged
        }

        /** `numFmts/numFmt` (id -> code). Only the declarations inside `numFmts` are read. */
        private fun customCodes(zip: ZipSource): Map<Int, String> {
            val xml = zip.readText("xl/styles.xml") ?: return emptyMap()
            val out = HashMap<Int, String>()
            var inNumFmts = false
            val handler = object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes?) {
                    when (XlsxXml.local(qName ?: "")) {
                        "numfmts" -> inNumFmts = true
                        "numfmt" -> if (inNumFmts && attributes != null) {
                            val id = WorkbookSheets.attributeByLocal(attributes, "numfmtid")?.toIntOrNull()
                            val code = WorkbookSheets.attributeByLocal(attributes, "formatcode")
                            if (id != null && code != null) out[id] = code
                        }
                    }
                }

                override fun endElement(uri: String?, localName: String?, qName: String) {
                    if (XlsxXml.local(qName ?: "") == "numfmts") inNumFmts = false
                }
            }
            XlsxXml.parse(xml, handler)
            return out
        }

        /** `cellXfs/xf/@numFmtId` in order: index = the `s` attribute of a cell. */
        private fun styleFormatIds(zip: ZipSource): List<Int> {
            val xml = zip.readText("xl/styles.xml") ?: return emptyList()
            val out = ArrayList<Int>()
            var inCellXfs = false
            val handler = object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes?) {
                    when (XlsxXml.local(qName ?: "")) {
                        "cellxfs" -> inCellXfs = true
                        "xf" -> if (inCellXfs && attributes != null) {
                            out.add(WorkbookSheets.attributeByLocal(attributes, "numfmtid")?.toIntOrNull() ?: 0)
                        }
                    }
                }

                override fun endElement(uri: String?, localName: String?, qName: String) {
                    if (XlsxXml.local(qName ?: "") == "cellxfs") inCellXfs = false
                }
            }
            XlsxXml.parse(xml, handler)
            return out
        }
    }
}

/**
 * The built-in number format table and the classifier for custom codes.
 *
 * A custom code is classified by its own tokens after literals are removed: quoted text (`"kg"`), the
 * character after `\`, `_` or `*`, and colour/condition brackets carry no meaning for the value. A bracket
 * that holds only `h`/`m`/`s` is elapsed time and is kept.
 */
object NumberFormatCodes {

    /** ECMA-376 §18.8.30 ids whose meaning is fixed. Ids 27..36 and 50..58 are locale date/time. */
    private val BUILT_IN: Map<Int, CellFormatKind> = buildMap {
        put(9, CellFormatKind.PERCENT)
        put(10, CellFormatKind.PERCENT)
        for (id in 14..17) put(id, CellFormatKind.DATE)
        for (id in 18..21) put(id, CellFormatKind.TIME)
        put(22, CellFormatKind.DATE_TIME)
        put(45, CellFormatKind.TIME)
        put(46, CellFormatKind.TIME)
        put(47, CellFormatKind.TIME)
        // Locale-dependent date/time formats: "a date, with a time when the value has one".
        for (id in 27..31) put(id, CellFormatKind.DATE_TIME)
        for (id in 32..36) put(id, CellFormatKind.TIME)
        for (id in 50..54) put(id, CellFormatKind.DATE_TIME)
        for (id in 55..58) put(id, CellFormatKind.TIME)
        // Decimal-formatted measurements (`0.00`, `#,##0.00`, the accounting variants). Their integer
        // counterparts (`0`, `#,##0`) are left alone: an integer cell is as likely an identifier.
        put(2, CellFormatKind.NUMBER)
        put(4, CellFormatKind.NUMBER)
        for (id in 39..44) put(id, CellFormatKind.NUMBER)
    }

    /** Resolves one `cellXfs` entry: a declared code wins, otherwise the built-in table decides. */
    fun of(numFmtId: Int, code: String?): CellNumberFormat {
        val declared = code?.trim()?.takeIf { it.isNotEmpty() }
        val kind = if (declared != null) classify(declared) else BUILT_IN[numFmtId] ?: CellFormatKind.GENERAL
        return if (kind == CellFormatKind.GENERAL) CellNumberFormat.GENERAL else CellNumberFormat(kind)
    }



    /** Classifies a format code from its first section (positive values), never from a value. */
    fun classify(code: String): CellFormatKind {
        val section = code.substringBefore(';').ifEmpty { code }
        val text = stripLiterals(section).lowercase(Locale.US)
        if (text.isEmpty()) return CellFormatKind.GENERAL
        if ('%' in text) return CellFormatKind.PERCENT
        val hasDate = text.any { it == 'y' || it == 'd' }
        // `am/pm` carries an `m` that is neither a month nor a minute; it is removed before the token scan.
        val tokens = text.replace("am/pm", "").replace("a/p", "")
        val hasTime = tokens.any { it == 'h' || it == 's' }
        val hasMinuteOrMonth = tokens.any { it == 'm' }
        return when {
            hasDate && hasTime -> CellFormatKind.DATE_TIME
            hasDate -> CellFormatKind.DATE
            hasTime -> CellFormatKind.TIME
            // A bare `m`/`mm`/`mmm` is Excel's month, so it is a date rather than nothing.
            hasMinuteOrMonth -> CellFormatKind.DATE
            // A numeric pattern that declares decimals is a measurement: `0.00`, `#,##0.0`, `0.000`.
            // An integer pattern (`0`, `#,##0`) stays general, because an integer cell can be an identifier.
            declaresDecimals(tokens) -> CellFormatKind.NUMBER
            else -> CellFormatKind.GENERAL
        }
    }

    /** True when the numeric part of a format code keeps decimal places (`0.00`, `#,##0.0`). */
    private fun declaresDecimals(code: String): Boolean {
        val withoutExponent = code.substringBefore('e').substringBefore('E')
        val dot = withoutExponent.indexOf('.')
        if (dot < 0) return false
        val after = withoutExponent.drop(dot + 1)
        val places = after.takeWhile { it == '0' || it == '#' || it == '?' }
        return places.isNotEmpty()
    }

    private fun stripLiterals(code: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < code.length) {
            when (val c = code[i]) {
                '"' -> {
                    i++
                    while (i < code.length && code[i] != '"') i++
                    i++
                }
                '\\', '_', '*' -> i += 2
                '[' -> {
                    val end = code.indexOf(']', i + 1)
                    if (end < 0) {
                        i = code.length
                    } else {
                        val inner = code.substring(i + 1, end)
                        if (inner.isNotEmpty() && inner.all { it.lowercaseChar() in "hms" }) out.append(inner)
                        i = end + 1
                    }
                }
                ';' -> i = code.length
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}

