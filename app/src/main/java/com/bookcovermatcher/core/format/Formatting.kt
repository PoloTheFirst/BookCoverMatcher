// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.format

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Text formatting shared by the UI and the wishlist, kept free of Android types. */
object Formatting {

    /**
     * The most decimals any measurement is displayed with, app-wide.
     *
     * This is a **display** rule only: no stored, aggregated, sorted, filtered or exported value is rounded
     * anywhere, and a value that is not a measurement (an identifier, a serial number, a row number, a cell
     * reference, a date) never goes through [decimal] in the first place.
     */
    const val MAX_DECIMALS = 2

    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

    /** `YYYY-MM-DD HH:MM` in [zone] (same layout as v1's wishlist). */
    fun timestamp(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        try { STAMP.format(Instant.ofEpochMilli(epochMillis).atZone(zone)) } catch (e: RuntimeException) { "—" }

    /**
     * A measurement as text: at most [MAX_DECIMALS] decimals, dot separator, no unnecessary trailing zeros.
     *
     * `12.3456` -> `12.35`, `1200.0` -> `1200`, `0.5` -> `0.5`. A value that is not finite has no number to
     * show, so it reads `—` rather than `NaN` or `Infinity`.
     */
    fun decimal(value: Double): String {
        if (!value.isFinite()) return "—"
        var text = String.format(Locale.US, "%.${MAX_DECIMALS}f", value)
        if ('.' in text) text = text.trimEnd('0').trimEnd('.')
        if (text == "-0") text = "0"
        return text
    }

    /** [decimal] for a workbook cell's text, or null when the text is not a finite number at all. */
    fun decimalOrNull(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val value = text.toDoubleOrNull() ?: return null
        return if (value.isFinite()) decimal(value) else null
    }

    /** One decimal, dot separator: `83.4` (JavaScript's `toFixed(1)`). */
    fun percent(value: Float): String = String.format(Locale.US, "%.1f", value)

    /** A similarity score is a measurement, so it follows the same two-decimal display rule. */
    fun cosine(value: Float): String = decimal(value.toDouble())

    /** Width of the result bar, 2..100 % (v1 never drew an empty bar). */
    fun barFraction(percent: Float): Float = max(2f, min(100f, percent)) / 100f

    fun fileSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        else -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
    }
}
