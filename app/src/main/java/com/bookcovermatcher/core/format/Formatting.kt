package com.bookcovermatcher.core.format

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Text formatting shared by the UI and the wishlist, kept free of Android types. */
object Formatting {
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

    /** `YYYY-MM-DD HH:MM` in [zone] (same layout as v1's wishlist). */
    fun timestamp(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        try { STAMP.format(Instant.ofEpochMilli(epochMillis).atZone(zone)) } catch (e: RuntimeException) { "—" }

    /** One decimal, dot separator: `83.4` (JavaScript's `toFixed(1)`). */
    fun percent(value: Float): String = String.format(Locale.US, "%.1f", value)

    fun cosine(value: Float): String = String.format(Locale.US, "%.3f", value)

    /** Width of the result bar, 2..100 % (v1 never drew an empty bar). */
    fun barFraction(percent: Float): Float = max(2f, min(100f, percent)) / 100f

    fun fileSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        else -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
    }
}
