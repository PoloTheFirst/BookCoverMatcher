package com.bookcovermatcher.core.excel.comments

/**
 * Natural ("human") ordering for file names, like Windows Explorer / Finder: digit runs compare by
 * numeric value, everything else compares case-insensitively, and a final case-sensitive comparison keeps
 * the order deterministic for names that only differ in case.
 *
 * Long digit runs are compared as **strings of digits after stripping leading zeros** (length first, then
 * lexicographic), so nothing is parsed into a fixed-width integer and no run can overflow.
 */
object NaturalSort {

    /** Orders [a] before [b] (< 0), after it (> 0), or equally (0). Never depends on the device locale. */
    fun compareNames(a: String, b: String): Int {
        val primary = compareRuns(a, b)
        if (primary != 0) return primary
        // Same natural order ("image2.jpg" vs "image02.jpg"): fall back to the exact text, case included.
        return a.compareTo(b)
    }

    private fun compareRuns(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val endA = digitRunEnd(a, i)
                val endB = digitRunEnd(b, j)
                val cmp = compareDigitRuns(a, i, endA, b, j, endB)
                if (cmp != 0) return cmp
                i = endA
                j = endB
                continue
            }
            val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (cmp != 0) return cmp
            i++
            j++
        }
        // A prefix sorts before the longer name, like any dictionary.
        return (a.length - i).compareTo(b.length - j)
    }

    private fun digitRunEnd(text: String, from: Int): Int {
        var end = from
        while (end < text.length && text[end].isDigit()) end++
        return end
    }

    private fun compareDigitRuns(a: String, aFrom: Int, aEnd: Int, b: String, bFrom: Int, bEnd: Int): Int {
        val aZeroFree = trimmedStart(a, aFrom, aEnd)
        val bZeroFree = trimmedStart(b, bFrom, bEnd)
        val aLength = aEnd - aZeroFree
        val bLength = bEnd - bZeroFree
        if (aLength != bLength) return aLength.compareTo(bLength)
        for (offset in 0 until aLength) {
            val cmp = a[aZeroFree + offset].compareTo(b[bZeroFree + offset])
            if (cmp != 0) return cmp
        }
        return 0
    }

    /** Index of the first non-zero digit, or the end of the run when it is all zeros. */
    private fun trimmedStart(text: String, from: Int, end: Int): Int {
        var start = from
        while (start < end - 1 && text[start] == '0') start++
        return start
    }
}
