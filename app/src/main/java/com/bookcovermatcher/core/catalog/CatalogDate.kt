package com.bookcovermatcher.core.catalog

/**
 * A publication date as the workbook spells it, with the precision it actually had.
 *
 * `2024` is a year, `2024-05` a month and `2024-05-17` a day; the app never pads a year into a date it
 * does not know. A value that cannot be read unambiguously is [unknown] instead of a guessed date.
 */
data class CatalogDate(val year: Int, val month: Int? = null, val day: Int? = null) {

    /** `2024`, `2024-05` or `2024-05-17` — the precision of the source value, not padded. */
    val label: String
        get() = buildString {
            append(year)
            if (month != null) {
                append('-').append(month.toString().padStart(2, '0'))
                if (day != null) append('-').append(day.toString().padStart(2, '0'))
            }
        }

    /** Chronological key with unknown parts at zero, only used for ordering dates of the same kind. */
    val sortKey: Int get() = year * 10_000 + (month ?: 0) * 100 + (day ?: 0)

    companion object {
        /** What the timeline shows for a record without a usable date. Never a fabricated year. */
        val unknown: CatalogDate? = null

        const val MIN_YEAR = 1900
        const val MAX_YEAR = 2100
    }
}

/**
 * Date parsing for the formats a Japanese doujin catalogue actually contains.
 *
 * Accepted, with the listed precision:
 *  * `2024`, `2024年`                -> year
 *  * `2024-05`, `2024/5`, `2024.05`, `2024年5月` -> month
 *  * `2024-05-17`, `2024/5/17`, `2024.5.17`, `2024年5月17日` -> day
 *
 * An Excel serial number (`45292` = 2024-01-01) is read only when the caller says the column is a date
 * column ([allowSerial]): outside a date column a bare number is just a number, and reading it as a day
 * in 1900 would invent a date. Serials below 1927 are not read at all, because a four-digit value there
 * is far more likely a year than a serial.
 *
 * Everything else is null: `17/05/2024` (ambiguous day/month order), a range or free text. The caller
 * groups those records as unknown instead of inventing a date.
 */
object CatalogDates {

    private val YEAR = Regex("^(\\d{4})\\s*年?$")
    private val MONTH = Regex("^(\\d{4})\\s*[-/.年]\\s*(\\d{1,2})\\s*月?$")
    private val DAY = Regex("^(\\d{4})\\s*[-/.年]\\s*(\\d{1,2})\\s*[-/.月]\\s*(\\d{1,2})\\s*日?$")

    /** A serial number, with Excel's optional time fraction (`45292.5`) which is ignored. */
    private val SERIAL = Regex("^(\\d{5,7})(?:\\.\\d+)?$")

    /** 1927-05-18; a smaller value is treated as a year rather than as a 1900s serial. */
    const val MIN_SERIAL = 10_000

    /** 9999-12-31, the largest serial Excel can store. */
    const val MAX_SERIAL = 2_958_465

    /**
     * @param allowSerial read a bare number as an Excel serial date. Only pass true for a column the
     * caller knows holds dates (the timeline's configured date columns).
     */
    fun parse(raw: String?, allowSerial: Boolean = false): CatalogDate? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        YEAR.matchEntire(text)?.let { match ->
            val year = match.groupValues[1].toIntOrNull() ?: return null
            return if (year in CatalogDate.MIN_YEAR..CatalogDate.MAX_YEAR) CatalogDate(year) else null
        }
        MONTH.matchEntire(text)?.let { match ->
            val year = match.groupValues[1].toIntOrNull() ?: return null
            val month = match.groupValues[2].toIntOrNull() ?: return null
            if (year !in CatalogDate.MIN_YEAR..CatalogDate.MAX_YEAR || month !in 1..12) return null
            return CatalogDate(year, month)
        }
        DAY.matchEntire(text)?.let { match ->
            val year = match.groupValues[1].toIntOrNull() ?: return null
            val month = match.groupValues[2].toIntOrNull() ?: return null
            val day = match.groupValues[3].toIntOrNull() ?: return null
            if (year !in CatalogDate.MIN_YEAR..CatalogDate.MAX_YEAR) return null
            if (month !in 1..12 || day < 1 || day > daysIn(year, month)) return null
            return CatalogDate(year, month, day)
        }
        return if (allowSerial) serial(text) else null
    }

    /**
     * An Excel serial number as a date.
     *
     * Excel counts days from 1899-12-30 for serials of 60 and above (serial 60 is its non-existent
     * 1900-02-29), so that is the base used here; nothing is rounded or padded.
     */
    fun serial(raw: String?): CatalogDate? {
        val match = SERIAL.matchEntire(raw?.trim().orEmpty()) ?: return null
        val days = match.groupValues[1].toLongOrNull() ?: return null
        if (days < MIN_SERIAL || days > MAX_SERIAL) return null
        val date = runCatching { java.time.LocalDate.of(1899, 12, 30).plusDays(days) }.getOrNull() ?: return null
        if (date.year !in CatalogDate.MIN_YEAR..CatalogDate.MAX_YEAR) return null
        return CatalogDate(date.year, date.monthValue, date.dayOfMonth)
    }

    /** True when the text is non-blank and cannot be read as a date — the health report says so. */
    fun isMalformed(raw: String?, allowSerial: Boolean = false): Boolean {
        val text = raw?.trim().orEmpty()
        return text.isNotEmpty() && parse(text, allowSerial) == null
    }

    private fun daysIn(year: Int, month: Int): Int = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        else -> if (isLeap(year)) 29 else 28
    }

    private fun isLeap(year: Int): Boolean =
        year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
}
