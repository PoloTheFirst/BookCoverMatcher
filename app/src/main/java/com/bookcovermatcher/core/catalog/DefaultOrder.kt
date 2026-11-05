// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.catalog

import java.math.BigDecimal

/**
 * One record's Default Order value, as the workbook spells it, with the comparison it implies.
 *
 * The raw text is kept (`0012` stays `0012`, a text id stays a text id) because nothing here rewrites a
 * source value; [number] is only how the value is *compared*. The order is:
 *
 *  1. numbers, ascending — `2` before `10`, and `0012` is the same number as `12`;
 *  2. other text, case-insensitive ascending — a text serial still sorts predictably;
 *  3. records with no value, last.
 *
 * A record with a missing or unreadable order is therefore never given a made-up position; it simply comes
 * after the records that have one, and the caller's final tie-break (the verified source identity) decides
 * among them.
 */
data class DefaultOrderValue(val raw: String?) : Comparable<DefaultOrderValue> {

    /** The trimmed source text, or null when the cell was blank. */
    val text: String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    /** The value as a number, when the whole cell is one. Leading zeros and `+` do not change it. */
    val number: BigDecimal? = text?.let { runCatching { BigDecimal(it) }.getOrNull() }

    val isMissing: Boolean get() = text == null

    /** 0 = numeric, 1 = text, 2 = missing — the comparison groups, in order. */
    val group: Int get() = when {
        text == null -> 2
        number != null -> 0
        else -> 1
    }

    override fun compareTo(other: DefaultOrderValue): Int {
        if (group != other.group) return group - other.group
        return when (group) {
            0 -> number!!.compareTo(other.number!!)
            1 -> text!!.compareTo(other.text!!, ignoreCase = true)
            else -> 0
        }
    }
}

/** Reading the Default Order value of a catalog entry. */
object DefaultOrdering {

    fun of(entry: CatalogEntry): DefaultOrderValue = DefaultOrderValue(entry.defaultOrder)
}
