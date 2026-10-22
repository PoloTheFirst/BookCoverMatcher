package com.bookcovermatcher.core.online

/**
 * Turns the raw OCR text that `chrome-lens-py` returns into something worth asking a book
 * catalogue about.
 *
 * A photographed cover produces far more than a title: series banners, blurbs, the publisher's
 * imprint, barcodes, prices. Lens hands all of it back as text blocks in reading order. This
 * object picks a title (and an author when one is recognisable) out of that, and nothing more —
 * it never guesses a book that the OCR did not actually contain.
 *
 * The rules are deliberately simple and deterministic so they can be unit-tested; the user can
 * always edit the resulting query before searching.
 *
 * Plain Kotlin/JVM (no `android.*`).
 */
object OcrQueryExtractor {

    /** A best-effort reading of one cover. [confidence] is 0 (nothing), 1 (title) or 2 (title + author). */
    class Suggestion(val title: String, val author: String?, val confidence: Int) {
        val query: String
            get() = if (author == null) title else "$title $author"
    }

    private const val MIN_LINE = 3
    private const val MAX_LINE = 90
    private const val MAX_QUERY = 120

    /** How many of the leading lines are considered as the title. */
    private const val TITLE_WINDOW = 3

    /**
     * How much the glyph height counts against the other terms in [rank]. Lens reports heights as
     * fractions of the image (0.01–0.10 for cover type), so this makes a bigger block outweigh any
     * plausible difference in letter count or position.
     */
    private const val HEIGHT_WEIGHT = 1000f

    /** Words that mark a line as metadata rather than an author name. */
    private val NOISE_WORDS = setOf(
        "isbn", "issn", "doi", "ean", "upc", "vol", "vol.", "volume", "edition", "ed.", "edn",
        "series", "book", "part", "no.", "nr.", "number", "price", "net", "pbk", "hbk", "paperback",
        "hardcover", "paperbacks", "translated", "translator", "edited", "editor", "introduction", "foreword",
        "afterword", "illustrated", "illustrator", "photography", "photographs", "adaptation",
        "adapted", "retold", "illustration", "cover", "publication", "published", "publisher",
        "press", "presses", "books", "group", "media", "ltd", "llc", "inc", "gmbh", "sarl",
        "manga", "anime", "novel", "trilogy", "duology", "tetralogy", "box", "set", "complete",
        "collector", "anniversary", "reissue", "reprint", "first", "edition.", "bestseller",
        "www", "http", "https", "com", "net", "org", "page", "pages", "chap", "chapter",
    )

    /** Splits the OCR text into cleaned, non-empty candidate lines, in reading order. */
    fun lines(ocrText: String): List<String> =
        ocrText.lineSequence()
            .map { normalise(it) }
            .filter { it.length >= MIN_LINE && it.length <= MAX_LINE }
            .filter { !isNoiseLine(it) }
            .toList()

    /** Null when the OCR text holds nothing that could plausibly be a title. */
    fun suggest(ocrText: String, blockHeights: List<Float> = emptyList()): Suggestion? {
        val lines = lines(ocrText)
        if (lines.isEmpty()) return null

        val titleIndex = pickTitle(lines, blockHeights)
        if (letters(lines[titleIndex]) < 2) return null
        val title = lines[titleIndex]

        val author = lines.drop(titleIndex + 1).firstOrNull { looksLikePersonName(it) }
            ?: lines.take(titleIndex).lastOrNull { looksLikePersonName(it) }
        val confidence = if (author == null) 1 else 2

        return if (queryLength(title, author) <= MAX_QUERY) Suggestion(title, author, confidence)
        else Suggestion(title, null, 1)
    }

    /**
     * Which line is the title.
     *
     * [blockHeights] is the normalized glyph height of each block, which chrome-lens-py returns
     * for free. On a designed cover the title is the largest type on the page, so height beats
     * reading order; when the geometry is missing we fall back to "the first line wins", which is
     * what Lens's reading order usually gives us anyway. Ties go to the earliest line.
     */
    private fun pickTitle(lines: List<String>, blockHeights: List<Float>): Int {
        val window = lines.indices.take(TITLE_WINDOW)
        return window.maxByOrNull { rank(heightAt(blockHeights, it), it) } ?: 0
    }

    private fun heightAt(heights: List<Float>, index: Int): Float =
        heights.getOrNull(index)?.takeIf { it.isFinite() && it > 0f } ?: 0f

    /**
     * Height dominates; with no geometry available every term but position is equal, so the
     * earliest line wins.
     */
    private fun rank(height: Float, index: Int): Float =
        height * HEIGHT_WEIGHT + (TITLE_WINDOW - index)

    private fun queryLength(title: String, author: String?): Int =
        if (author == null) title.length else "$title $author".length

    /** The query to send to a catalogue, or "" when the OCR text is unusable. */
    fun query(ocrText: String): String = suggest(ocrText)?.query.orEmpty()

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    /** Collapse runs of whitespace, drop the punctuation Lens keeps at the edges. */
    private fun normalise(line: String): String = line
        .replace('\t', ' ')
        .replace('–', '-')
        .replace('—', '-')
        .split(' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .trim { it.isLetterOrDigit().not() || it in "'\"“”‘’·,:;!?()[]{}<>/\\|~`*_=+-" }

    private fun letters(line: String): Int = line.count { it.isLetter() }

    /** Barcodes, prices, URLs and runs of digits are never a title. */
    private fun isNoiseLine(line: String): Boolean {
        if (line.none(Char::isLetter)) return true
        val digits = line.count(Char::isDigit)
        if (digits > letters(line)) return true
        if (line.any { it in "€$£¥₩" }) return true
        if (line.contains('@') || line.contains("://")) return true

        val words = line.lowercase().split(' ').map { it.trim { c -> c.isLetterOrDigit().not() } }
        return words.any { it in NOISE_WORDS }
    }

    /**
     * `J. R. R. Tolkien`, `Ursula K. Le Guin`, `Neil Gaiman` — two to four dotted-cap words with
     * no metadata in them. Deliberately conservative: a wrong author makes the search worse than
     * no author.
     */
    fun looksLikePersonName(line: String): Boolean {
        val words = line.split(' ')
        if (words.size < 2 || words.size > 4) return false
        if (words.any { w -> w.lowercase().trim { c -> c.isLetterOrDigit().not() } in NOISE_WORDS }) return false

        // Bylines are normally Title Case; series banners and imprints shout in capitals. Dotted
        // initials ("J.R.R. TOLKIEN") are the one all-cap byline worth letting through.
        if (line == line.uppercase() && !DOTTED_INITIAL.containsMatchIn(line)) return false

        var letters = 0
        for (word in words) {
            val core = word.trim { c -> c.isLetterOrDigit().not() && c != '.' }
            if (core.isEmpty()) return false
            if (!core[0].isUpperCase()) return false
            if (core.any { !it.isLetter() && it != '.' }) return false
            letters += core.count(Char::isLetter)
        }
        return letters >= 4
    }

    private val DOTTED_INITIAL = Regex("[A-Z]\\.")
}
