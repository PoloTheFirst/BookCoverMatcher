package com.bookcovermatcher.core.wishlist

/**
 * A small JSON reader / writer (objects, arrays, strings, numbers, booleans, null).
 * The wishlist is the only thing stored as JSON, and keeping the codec here means it is unit-tested
 * on the desktop JVM and the app needs no JSON library.
 *
 * Values are `Map<String, Any?>`, `List<Any?>`, `String`, `Double`, `Boolean` or `null`.
 */
object MiniJson {
    class JsonException(message: String) : RuntimeException(message)

    private const val MAX_DEPTH = 64

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value(0)
        p.skipWs()
        if (!p.atEnd()) throw JsonException("unexpected trailing data at ${p.pos}")
        return v
    }

    fun write(value: Any?): String = StringBuilder().also { write(value, it) }.toString()

    @Suppress("UNCHECKED_CAST")
    private fun write(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(v, sb)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long -> sb.append(v.toString())
            is Number -> {
                val d = v.toDouble()
                if (d.isNaN() || d.isInfinite()) sb.append("null")
                else if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append(d.toLong().toString())
                else sb.append(d.toString())
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v as Map<String, Any?>) {
                    if (!first) sb.append(',')
                    first = false
                    quote(k, sb)
                    sb.append(':')
                    write(x, sb)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                v.forEachIndexed { i, x ->
                    if (i > 0) sb.append(',')
                    write(x, sb)
                }
                sb.append(']')
            }
            else -> throw JsonException("cannot serialise ${v.javaClass.name}")
        }
    }

    private fun quote(s: String, sb: StringBuilder) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWs() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t')) pos++
        }

        private fun fail(msg: String): Nothing = throw JsonException("$msg at $pos")

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("nesting too deep")
            if (atEnd()) fail("unexpected end")
            return when (val c = s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else fail("unexpected '$c'")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) fail("bad literal")
            pos += word.length
            return v
        }

        private fun num(): Double {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos] in '0'..'9' || s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E' || s[pos] == '+' || s[pos] == '-')) pos++
            return s.substring(start, pos).toDoubleOrNull() ?: fail("bad number")
        }

        private fun str(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (atEnd()) fail("bad escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (pos + 4 > s.length) fail("bad unicode escape")
                                val code = s.substring(pos, pos + 4).toIntOrNull(16) ?: fail("bad unicode escape")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> fail("bad escape '\\$e'")
                        }
                    }
                    c < ' ' -> fail("control character in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            pos++
            val out = ArrayList<Any?>()
            skipWs()
            if (!atEnd() && s[pos] == ']') { pos++; return out }
            while (true) {
                skipWs()
                out.add(value(depth + 1))
                skipWs()
                if (atEnd()) fail("unterminated array")
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun obj(depth: Int): Map<String, Any?> {
            pos++
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (!atEnd() && s[pos] == '}') { pos++; return out }
            while (true) {
                skipWs()
                if (atEnd() || s[pos] != '"') fail("expected string key")
                val key = str()
                skipWs()
                if (atEnd() || s[pos++] != ':') fail("expected ':'")
                skipWs()
                out[key] = value(depth + 1)
                skipWs()
                if (atEnd()) fail("unterminated object")
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> fail("expected ',' or '}'")
                }
            }
        }
    }
}
