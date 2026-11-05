// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.online

/** Which identifying attribute an [OnlineQuery] carries, and which a provider can actually use. */
enum class SearchField {
    TITLE,
    CIRCLE,
    AUTHOR,
    CODE,
    IMAGE_REFERENCE,
}

/**
 * One search, as the user expressed it, split into the identifying attributes the app knows about.
 *
 * A single free-text box cannot say *which* attribute a word is: `Blue Archive` typed into a keyword
 * field means a title to one provider and an author to another. This model keeps the parts apart so a
 * provider is given only what it can use ([keyword] for keyword providers, [title]/[author] for the
 * bibliographic ones), and so the UI can say which parts a provider ignored instead of pretending it
 * searched them.
 *
 * Normalization is deliberately conservative: the original string is always kept for display and
 * diagnostics, identifiers are compared with their separators removed, and nothing that carries meaning
 * in a Japanese title or a product code is rewritten.
 *
 * Plain Kotlin/JVM (no `android.*`), so query handling is unit-tested without a device.
 */
data class OnlineQuery(
    val title: String = "",
    val circle: String = "",
    val author: String = "",
    /** A product identifier, e.g. a DLsite `RJ01234567` code. */
    val code: String = "",
    /** A local cover/record the user compares against; never sent to a provider as an image. */
    val imageReference: String? = null,
    /** What the user actually typed, untouched. */
    val original: String = "",
) {

    val isBlank: Boolean
        get() = title.isBlank() && circle.isBlank() && author.isBlank() && code.isBlank() &&
            imageReference.isNullOrBlank()

    /** The fields this query actually constrains, in a stable order. */
    val fields: List<SearchField>
        get() = buildList {
            if (title.isNotBlank()) add(SearchField.TITLE)
            if (circle.isNotBlank()) add(SearchField.CIRCLE)
            if (author.isNotBlank()) add(SearchField.AUTHOR)
            if (code.isNotBlank()) add(SearchField.CODE)
            if (!imageReference.isNullOrBlank()) add(SearchField.IMAGE_REFERENCE)
        }

    /**
     * The text a keyword-search provider receives: the parts joined with single spaces, so a provider
     * that understands only one string is not handed a hollow query. A code is included verbatim because
     * it is the most identifying thing the user can give.
     */
    fun keyword(): String = listOf(code, title, circle, author)
        .filter { it.isNotBlank() }
        .joinToString(" ")

    /** This query with [field]'s value dropped — used when a provider cannot use that field. */
    fun without(field: SearchField): OnlineQuery = when (field) {
        SearchField.TITLE -> copy(title = "")
        SearchField.CIRCLE -> copy(circle = "")
        SearchField.AUTHOR -> copy(author = "")
        SearchField.CODE -> copy(code = "")
        SearchField.IMAGE_REFERENCE -> copy(imageReference = null)
    }

    /** True when nothing is left to search with once [unsupported] fields are removed. */
    fun isEmptyWithout(unsupported: Collection<SearchField>): Boolean =
        unsupported.fold(this) { query, field -> query.without(field) }.isBlank

    companion object {

        /** `title:…`, `circle:…`, `author:…`, `code:…` (also `t:`/`c:`/`a:`/`id:`); anything else is a title. */
        private val PREFIX = Regex("^(title|t|circle|c|author|a|code|id|r):(.*)$", RegexOption.IGNORE_CASE)

        /** A shop's own product code, e.g. DLsite's `RJ01234567`. */
        private val CODE = Regex("\\bRJ\\d{6,10}\\b", RegexOption.IGNORE_CASE)

        /** The same code as people type it: `RJ 01234567`, `RJ-01234567`. */
        private val SPACED_CODE = Regex("\\bRJ[\\s\\-]?\\d{6,10}\\b", RegexOption.IGNORE_CASE)

        /**
         * Reads [raw] into its parts. A bare `RJ…` code anywhere in the text is taken out and used as the
         * code, because that is what the user means by it; everything unprefixed becomes the title. Runs of
         * whitespace collapse and full-width ASCII folds to half-width, but the text is never otherwise
         * rewritten.
         */
        fun parse(raw: String, imageReference: String? = null): OnlineQuery {
            val text = foldWidth(raw).trim()
            val title = StringBuilder()
            var circle = ""
            var author = ""
            var code = ""
            for (token in text.split(' ')) {
                if (token.isBlank()) continue
                val match = PREFIX.matchEntire(token)
                if (match != null) {
                    val value = collapse(match.groupValues[2])
                    when (match.groupValues[1].lowercase()) {
                        "title", "t" -> title.append(' ').append(value)
                        "circle", "c" -> circle = value
                        "author", "a" -> author = value
                        "code", "id", "r" -> code = value
                    }
                    continue
                }
                val codeHit = CODE.find(token)
                if (codeHit != null) {
                    code = codeHit.value.uppercase()
                    val rest = token.replace(codeHit.value, "")
                    if (rest.isNotBlank()) title.append(' ').append(rest)
                    continue
                }
                title.append(' ').append(token)
            }
            var cleanTitle = collapse(title.toString())
            // A code typed without spaces around it — or with one — is still evidence, not part of the title.
            if (code.isEmpty()) {
                val hit = SPACED_CODE.find(text) ?: CODE.find(cleanTitle)
                if (hit != null) {
                    code = normalizeCode(hit.value)
                    cleanTitle = collapse(cleanTitle.replace(hit.value, ""))
                }
            }
            // The `rj` half of a spaced code must not survive as a title word.
            if (code.isNotEmpty()) cleanTitle = collapse(cleanTitle.replace(Regex("(?i)\\brj\\b"), ""))
            if (code.isNotEmpty()) code = normalizeCode(code)
            return OnlineQuery(
                title = cleanTitle,
                circle = circle,
                author = author,
                code = code,
                imageReference = imageReference?.takeIf { it.isNotBlank() },
                original = raw,
            )
        }

        /** Trims and collapses runs of whitespace to one space; nothing else. */
        fun collapse(text: String): String = text.trim().replace(Regex("\\s+"), " ")

        /**
         * Folds full-width ASCII (U+FF01–U+FF5E) to half-width and the ideographic space to a space.
         *
         * That is what a Japanese IME produces when a Latin word or a code is typed in full-width mode, and
         * it is the only transformation safe for a product identity: kana, kanji, long-vowel marks and
         * punctuation such as `～` or `・` are left exactly as typed.
         */
        fun foldWidth(text: String): String = buildString(text.length) {
            for (ch in text) {
                append(
                    when (ch) {
                        '\u3000' -> ' '
                        in '\uFF01'..'\uFF5E' -> (ch.code - 0xFEE0).toChar()
                        else -> ch
                    },
                )
            }
        }

        /**
         * Canonical form of a product identifier: folded, upper-cased, and stripped of the separators people
         * insert (`RJ 01234567`, `RJ-01234567`). Only comparison uses it; the query keeps what was typed.
         */
        fun normalizeCode(text: String): String =
            foldWidth(text).uppercase().replace(Regex("[\\s\\-_]"), "")

        /**
         * Canonical form of a title/circle/author for comparison: folded, lower-cased, whitespace collapsed,
         * and decorative punctuation removed. Japanese characters, digits and Latin letters are preserved.
         */
        fun normalizeText(text: String): String {
            val folded = foldWidth(text).lowercase()
            val stripped = folded.map { ch ->
                if (ch.isLetterOrDigit() || ch == ' ' || ch == '#' || ch == '+' || ch == '&') ch else ' '
            }.joinToString("")
            return collapse(stripped)
        }
    }
}
