// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.ocr

/** One recognised line, already mapped out of the wire format. */
internal data class NativeLine(
    val rank: Int,
    val score: Float,
    val vertical: Boolean,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val text: String,
) {
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
}

/** Everything one `recognise` call produced. */
internal class NativeResult(
    val regionsDetected: Int,
    val lines: List<NativeLine>,
    /** Per-region failures; the rest of the cover is still valid. */
    val errors: List<String>,
)

/**
 * Reads the `BC-OCR-RESULT 1` / `BC-OCR-INFO 1` payloads written by `native/ocr/src/wire.rs`.
 *
 * Pure Kotlin with no Android import, for the same reason [com.bookcovermatcher.online.OcrResultParser]
 * is: the format is where a silent mistake turns into plausible-looking wrong text, and a parser that
 * needs a device to run is a parser nobody tests.
 *
 * Every rule here is exercised by `NativeOcrProtocolTest`.
 */
internal object NativeOcrProtocol {

    const val RESULT_HEADER = "BC-OCR-RESULT 1"
    const val INFO_HEADER = "BC-OCR-INFO 1"
    private const val END = "END"

    /**
     * @throws IllegalArgumentException when the payload is not the one we asked for. A payload with
     *   zero lines is **not** an error: that is a cover with no text on it.
     */
    fun parseResult(payload: String): NativeResult {
        val lines = payload.split('\n').filter { it.isNotEmpty() }
        requireHeader(lines, RESULT_HEADER)
        require(lines.lastOrNull() == END) { "the result is truncated: no $END record" }

        var regions = 0
        val parsed = ArrayList<NativeLine>()
        val errors = ArrayList<String>()
        for (line in lines.subList(1, lines.size - 1)) {
            when {
                line.startsWith("REGIONS\t") -> {
                    regions = line.substringAfter('\t').trim().toIntOrNull()
                        ?: throw IllegalArgumentException("REGIONS is not a number: '$line'")
                }
                line.startsWith("LINE\t") -> parsed += parseLine(line)
                line.startsWith("ERROR\t") -> errors += unescape(line.substringAfter('\t'))
                // Unknown records are ignored on purpose: a newer native side must not break an
                // older reader, and the header already proved both sides speak the same version.
                else -> Unit
            }
        }
        return NativeResult(regions, parsed, errors)
    }

    /**
     * `BC-OCR-INFO 1` -> an ordered map of record type to its fields (the record name excluded).
     *
     * Unlike [parseResult] this is diagnostics only, so it returns what it can rather than throwing:
     * an unreadable describe() payload must not stop OCR from working.
     */
    fun parseInfo(payload: String): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        for (line in payload.split('\n')) {
            if (line.isEmpty() || line == END || line == INFO_HEADER) continue
            val parts = line.split('\t')
            if (parts.size >= 2) out[parts[0]] = parts.drop(1)
        }
        return out
    }

    /** The inverse of `wire::escape`: `\\`, `\t`, `\n`, `\r`; anything else keeps its character. */
    fun unescape(value: String): String {
        if (!value.contains('\\')) return value
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            if (ch != '\\' || index == value.length - 1) {
                out.append(ch)
                index++
                continue
            }
            when (val escaped = value[index + 1]) {
                '\\' -> out.append('\\')
                't' -> out.append('\t')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                else -> out.append(escaped)
            }
            index += 2
        }
        return out.toString()
    }

    private fun parseLine(line: String): NativeLine {
        // rank, score, vertical, corners, text — the text is last and already escaped, so a limit
        // keeps it in one piece even if a future writer forgets to escape a tab.
        val fields = line.split('\t', limit = 6)
        require(fields.size == 6) { "a LINE record has ${fields.size - 1} fields, expected 5: '$line'" }
        val rank = fields[1].toIntOrNull() ?: throw IllegalArgumentException("LINE rank: '${fields[1]}'")
        val score = fields[2].toFloatOrNull() ?: throw IllegalArgumentException("LINE score: '${fields[2]}'")
        val vertical = when (fields[3]) {
            "1" -> true
            "0" -> false
            else -> throw IllegalArgumentException("LINE vertical flag: '${fields[3]}'")
        }
        val corners = fields[4].split(',').map {
            it.trim().toFloatOrNull() ?: throw IllegalArgumentException("LINE corners: '${fields[4]}'")
        }
        require(corners.size == 8) { "a quad has 8 coordinates, got ${corners.size}: '${fields[4]}'" }
        val xs = listOf(corners[0], corners[2], corners[4], corners[6])
        val ys = listOf(corners[1], corners[3], corners[5], corners[7])
        return NativeLine(
            rank = rank,
            score = score,
            vertical = vertical,
            left = xs.min(),
            top = ys.min(),
            right = xs.max(),
            bottom = ys.max(),
            text = unescape(fields[5]),
        )
    }

    private fun requireHeader(lines: List<String>, header: String) {
        require(lines.isNotEmpty()) { "the payload is empty" }
        require(lines.first() == header) {
            "unexpected payload header '${lines.first()}', expected '$header'"
        }
    }
}
