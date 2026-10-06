package com.bookcovermatcher.core.excel

/** Thrown for a malformed range string. The message is shown to the user as-is. */
class InvalidRangeException(message: String) : IllegalArgumentException(message)

/**
 * Zero-based, inclusive cell rectangle. Port of `parseRange` from `js/excel.js`.
 */
data class CellRange(val r1: Int, val r2: Int, val c1: Int, val c2: Int) {

    fun contains(row: Int, col: Int): Boolean = row in r1..r2 && col in c1..c2

    companion object {
        const val ERROR_MESSAGE = "Invalid range — use a format like A1:B20."

        private val PATTERN = Regex("^([A-Z]+)(\\d+)\\s*:\\s*([A-Z]+)(\\d+)$")

        /** `A` -> 0, `Z` -> 25, `AA` -> 26 ... */
        fun columnLettersToIndex(letters: String): Int {
            var n = 0L
            for (ch in letters) {
                n = n * 26 + (ch.code - 64)
                if (n > Int.MAX_VALUE) n = Int.MAX_VALUE.toLong() + 1
            }
            return (n - 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        /** 0 -> `A`, 25 -> `Z`, 26 -> `AA` ... */
        fun indexToColumnLetters(index: Int): String {
            val sb = StringBuilder()
            var n = index + 1
            while (n > 0) {
                val r = (n - 1) % 26
                sb.insert(0, ('A'.code + r).toChar())
                n = (n - 1) / 26
            }
            return sb.toString()
        }

        /**
         * Accepts `A1:B20` (case-insensitive, `$` allowed, spaces around `:` allowed).
         * Corners may be given in any order.
         */
        fun parse(input: String?): CellRange {
            val cleaned = (input ?: "").trim().uppercase().replace("$", "")
            val m = PATTERN.matchEntire(cleaned) ?: throw InvalidRangeException(ERROR_MESSAGE)

            val cA = columnLettersToIndex(m.groupValues[1])
            val rA = (m.groupValues[2].toLongOrNull() ?: throw InvalidRangeException(ERROR_MESSAGE)) - 1
            val cB = columnLettersToIndex(m.groupValues[3])
            val rB = (m.groupValues[4].toLongOrNull() ?: throw InvalidRangeException(ERROR_MESSAGE)) - 1
            if (rA > Int.MAX_VALUE || rB > Int.MAX_VALUE) throw InvalidRangeException(ERROR_MESSAGE)

            return CellRange(
                r1 = minOf(rA, rB).toInt(), r2 = maxOf(rA, rB).toInt(),
                c1 = minOf(cA, cB), c2 = maxOf(cA, cB),
            )
        }
    }
}
