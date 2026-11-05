package com.bookcovermatcher.core

import com.bookcovermatcher.core.excel.CellRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **UI-01** — the matcher's default range must cover the layout this app documents as authentic.
 *
 * The shipped default was `A1:B20`, two columns wide, while the doujin catalogue keeps its 24 columns in
 * `A:X` and its cover pictures in the title column `D`. A first run against a real workbook therefore found
 * nothing and only said so in a toast. The constant is pinned here so the regression cannot come back
 * silently, and the reader's own record cap is used as the row bound rather than an unrelated number.
 */
class DefaultRangeTest {

    @Test
    fun `the default range covers the documented title column and the record cap`() {
        val range = CellRange.parse(Config.DEFAULT_RANGE)
        // Column D (index 3) is where the authentic workbook keeps the title, and its cover pictures.
        assertTrue("column D is inside ${Config.DEFAULT_RANGE}", range.contains(1, 3))
        // The catalogue's first column and its widest documented one (A–X, index 0..23).
        assertTrue(range.contains(0, 0))
        assertTrue("column X is inside ${Config.DEFAULT_RANGE}", range.contains(1, 23))
        // Rows: 5000 of them cover the authentic catalogue (124 records) with room to spare.
        assertEquals(0, range.r1)
        assertEquals(4999, range.r2)
    }

    @Test
    fun `a fresh state starts on the default range`() {
        assertEquals(Config.DEFAULT_RANGE, com.bookcovermatcher.UiState().rangeText)
    }

    @Test
    fun `the old two-column default would have missed the title column`() {
        // Keeps the reason for the change executable: this is what the defect looked like.
        val old = CellRange.parse("A1:B20")
        assertTrue("the old default excluded column D", !old.contains(1, 3))
    }
}
