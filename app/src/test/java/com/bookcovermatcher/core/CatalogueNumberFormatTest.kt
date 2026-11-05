package com.bookcovermatcher.core

import com.bookcovermatcher.core.excel.comments.CellFormatKind
import com.bookcovermatcher.core.excel.comments.CellNumberFormat
import com.bookcovermatcher.core.excel.comments.NumberFormatCodes
import com.bookcovermatcher.core.excel.comments.WorkbookStyles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Date and percentage display for the Catalogue table.
 *
 * Excel stores a date as a serial number and a percentage as a fraction; the format is cell metadata, not
 * something to guess from the value. These tests pin the reading of that metadata, the two date systems,
 * and the rule that an ordinary number is never turned into a date or a percentage.
 */
class CatalogueNumberFormatTest {

    private val date = CellNumberFormat(CellFormatKind.DATE)
    private val time = CellNumberFormat(CellFormatKind.TIME)
    private val dateTime = CellNumberFormat(CellFormatKind.DATE_TIME)
    private val percent = CellNumberFormat(CellFormatKind.PERCENT)

    // ---- dates ---------------------------------------------------------------------------------

    @Test
    fun `a serial in a date cell is shown as a date, in both date systems`() {
        // 45292 = 2024-01-01 in the 1900 system; the same serial in the 1904 system is four years later.
        assertEquals("2024-01-01", date.display("45292"))
        assertEquals("1900-01-01", date.display("1"))
        // The same serial is four years later in the 1904 system: the epoch is not interchangeable.
        assertEquals("2028-01-02", date.display("45292", date1904 = true))
        assertEquals("1904-01-01", date.display("0", date1904 = true))
        // The fictitious 1900-02-29 (serial 60) and serial 0 have no real date: the stored text is kept.
        assertNull(date.display("60"))
        assertNull(date.display("0"))
        assertNull(date.display("-5"))
    }

    @Test
    fun `a date-time cell keeps the time it holds`() {
        // 45292.6041666667 = 2024-01-01 14:30:00.
        assertEquals("2024-01-01 14:30", dateTime.display("45292.6041666667"))
        assertEquals("2024-01-01", dateTime.display("45292"))
        // A time-only value in a date-time cell is shown as a time, not as a date in 1899.
        assertEquals("12:00", dateTime.display("0.5"))
        assertEquals("14:30", time.display("0.6041666667"))
        // A date-only format does not invent a time that the format does not show.
        assertEquals("2024-01-01", date.display("45292.6041666667"))
    }

    @Test
    fun `seconds survive only when they are really there`() {
        assertEquals("14:30", time.display("0.6041666667"))
        assertEquals("14:30:05", time.display(secondsOf(14, 30, 5)))
    }

    @Test
    fun `a text cell is never read as a date`() {
        // Only numbers are date-formattable: a text cell keeps its own text.
        assertNull(date.display("not a date"))
        assertNull(date.display("2024-01-01"))
        assertNull(date.display(""))
        assertNull(date.display("   "))
        assertNull(WorkbookStyles.EMPTY.display(1, "45292", numeric = false))
    }

    // ---- percentages ---------------------------------------------------------------------------

    @Test
    fun `a percentage is rounded to at most two decimals`() {
        assertEquals("12.35%", percent.display("0.123456"))
        assertEquals("50%", percent.display("0.5"))
        assertEquals("100%", percent.display("1"))
        assertEquals("0%", percent.display("0"))
        assertEquals("0%", percent.display("0.00004"))
        assertEquals("250%", percent.display("2.5"))
    }

    @Test
    fun `negative, rounding up and rounding down are all covered`() {
        assertEquals("-12.35%", percent.display("-0.123456"))
        assertEquals("12.35%", percent.display("0.123456"))   // rounds up
        assertEquals("12.34%", percent.display("0.12344"))    // rounds down
        assertEquals("100%", percent.display("0.999999"))     // rounds up to the next whole percent
        // A value that rounds to zero never shows a minus sign it does not have.
        assertEquals("0%", percent.display("-0.00004"))
    }

    @Test
    fun `a percentage is scaled exactly once`() {
        // The stored value is the fraction; the display multiplies by 100 once and appends one '%'.
        assertEquals(1, Regex("%").findAll(percent.display("0.123456")!!).count())
        // A shared string that already reads "12.35%" is text, so it is never formatted again.
        assertNull(WorkbookStyles.EMPTY.display(2, "12.35%", numeric = false))
    }

    // ---- formats that are not dates ------------------------------------------------------------

    @Test
    fun `ordinary numbers keep the text the workbook stored`() {
        assertNull(CellNumberFormat().display("1234"))
        assertNull(CellNumberFormat(CellFormatKind.OTHER).display("1234"))
        // A General or integer cell is left exactly as stored: it may be an identifier, a page count or a row
        // number, so the app does not decide that it is a measurement.
        assertNull(NumberFormatCodes.of(0, null).display("42"))
        assertNull(NumberFormatCodes.of(1, null).display("42"))
        assertNull(NumberFormatCodes.of(3, null).display("1234"))
        // A cell that declares decimals is a measurement, and follows the two-decimal display rule.
        assertEquals("1234.5", NumberFormatCodes.of(2, null).display("1234.5"))
        assertEquals("1234.57", NumberFormatCodes.of(2, null).display("1234.567"))
    }

    @Test
    fun `a format code is classified from its own tokens`() {
        assertEquals(CellFormatKind.DATE, NumberFormatCodes.classify("yyyy\"\u5E74\"m\"\u6708\"d\"\u65E5\";@"))
        assertEquals(CellFormatKind.DATE, NumberFormatCodes.classify("mm-dd-yy"))
        assertEquals(CellFormatKind.DATE_TIME, NumberFormatCodes.classify("yyyy-mm-dd hh:mm"))
        assertEquals(CellFormatKind.DATE_TIME, NumberFormatCodes.classify("m/d/yy h:mm"))
        assertEquals(CellFormatKind.TIME, NumberFormatCodes.classify("h:mm AM/PM"))
        assertEquals(CellFormatKind.TIME, NumberFormatCodes.classify("[h]:mm:ss"))
        assertEquals(CellFormatKind.PERCENT, NumberFormatCodes.classify("0.00%"))
        assertEquals(CellFormatKind.PERCENT, NumberFormatCodes.classify("0%"))
        assertEquals(CellFormatKind.GENERAL, NumberFormatCodes.classify("General"))
        // A currency format that declares decimals is a measurement too — the app shows the number, and the
        // workbook's currency symbol stays in the workbook.
        assertEquals(CellFormatKind.NUMBER, NumberFormatCodes.classify("#,##0.00 \"\u5186\""))
        assertEquals(CellFormatKind.GENERAL, NumberFormatCodes.classify("@"))
        // Scientific notation still declares decimals, so it is a measurement as well.
        assertEquals(CellFormatKind.NUMBER, NumberFormatCodes.classify("0.0000E+00"))
    }

    @Test
    fun `a decimal-formatted measurement follows the two-decimal display rule`() {
        val measured = NumberFormatCodes.of(2, null)
        assertEquals(CellFormatKind.NUMBER, measured.kind)
        assertEquals("1234.57", measured.display("1234.5678"))
        assertEquals("1200", measured.display("1200"))
        assertEquals("0.5", measured.display("0.5"))
        // A cell whose format declares decimals, whether built in or declared by the workbook.
        assertEquals(CellFormatKind.NUMBER, NumberFormatCodes.classify("#,##0.00"))
        assertEquals(CellFormatKind.NUMBER, NumberFormatCodes.classify("0.0"))
        assertEquals(CellFormatKind.NUMBER, NumberFormatCodes.of(4, null).kind)
        assertEquals("12.35", CellFormatKind.NUMBER.let { CellNumberFormat(it).display("12.3456") })
    }

    @Test
    fun `an integer or general cell is never reformatted`() {
        // Identifiers, serial numbers and page counts live in General/integer cells: their digits are theirs.
        assertEquals(CellFormatKind.GENERAL, NumberFormatCodes.of(0, null).kind)
        assertEquals(CellFormatKind.GENERAL, NumberFormatCodes.of(1, null).kind)
        assertEquals(CellFormatKind.GENERAL, NumberFormatCodes.of(3, null).kind)
        assertEquals(CellFormatKind.GENERAL, NumberFormatCodes.classify("0"))
        assertEquals(CellFormatKind.GENERAL, NumberFormatCodes.classify("#,##0"))
        assertNull(NumberFormatCodes.of(0, null).display("0012"))
        assertNull(NumberFormatCodes.of(1, null).display("00042"))
    }

    /** The fraction of a day that [hours]:[minutes]:[seconds] is, as Excel stores it. */
    private fun secondsOf(hours: Int, minutes: Int, seconds: Int): String =
        ((hours * 3600 + minutes * 60 + seconds) / 86_400.0).toString()
}
