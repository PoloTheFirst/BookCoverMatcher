package com.bookcovermatcher.core.online

/**
 * The little bit of HTML handling a marketplace adapter needs, and nothing more.
 *
 * The app has no HTML parser dependency and does not need one: a provider adapter reads a handful
 * of fields out of a search page it already knows the shape of. What it does need is the boring,
 * easy-to-get-wrong part — entity decoding and tag stripping — kept in one tested place instead of
 * being re-implemented per provider.
 *
 * Plain Kotlin/JVM so it is unit-tested on a desktop JVM.
 */
object HtmlText {

    private val TAG = Regex("<[^>]*>")
    private val WHITESPACE = Regex("\\s+")
    private val NUMERIC_ENTITY = Regex("&#(x?)([0-9A-Fa-f]+);")

    /**
     * Named entities seen in the providers this app reads. Deliberately a short list: an unknown
     * entity is left verbatim rather than guessed.
     */
    private val NAMED_ENTITIES = listOf(
        "nbsp" to " ",
        "quot" to "\"",
        "apos" to "'",
        "lt" to "<",
        "gt" to ">",
        "hellip" to "\u2026",
        "mdash" to "\u2014",
        "ndash" to "\u2013",
        "middot" to "\u00B7",
        "times" to "\u00D7",
        "yen" to "\u00A5",
        // `amp` last: `&amp;lt;` must become `&lt;`, not `<`.
        "amp" to "&",
    )

    /** Entities decoded, tags removed, runs of whitespace collapsed. */
    fun clean(raw: String): String = collapse(unescape(stripTags(raw)))

    /** Tags replaced by a space, so `<b>a</b><b>b</b>` never becomes `ab`. */
    fun stripTags(raw: String): String = TAG.replace(raw, " ")

    fun unescape(raw: String): String {
        if ('&' !in raw) return raw
        var out = NUMERIC_ENTITY.replace(raw) { match ->
            val hex = match.groupValues[1] == "x"
            val code = match.groupValues[2].let { if (hex) it.toIntOrNull(16) else it.toIntOrNull() }
            if (code != null && code in 1..0x10FFFF && (code < 0xD800 || code > 0xDFFF)) {
                String(Character.toChars(code))
            } else {
                match.value
            }
        }
        for ((name, replacement) in NAMED_ENTITIES) {
            out = out.replace("&$name;", replacement)
        }
        return out
    }

    private fun collapse(raw: String): String = WHITESPACE.replace(raw, " ").trim()
}
