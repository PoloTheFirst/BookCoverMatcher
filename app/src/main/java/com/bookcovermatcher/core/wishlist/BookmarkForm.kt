package com.bookcovermatcher.core.wishlist

/**
 * Field-level rules for the bookmark form, shared by creation and editing so both behave identically.
 *
 * Field keys are stable identifiers in **visual order**, which is what the dialog uses to scroll to the
 * first invalid field.
 */
object BookmarkForm {

    const val FIELD_TITLE = "title"
    const val FIELD_URL = "url"

    /** Gray sample texts. They are placeholders, never values, and are never written to storage. */
    const val FOLDER_NAME_PLACEHOLDER = "ブルアカ"
    const val TITLE_PLACEHOLDER = "みつ"
    const val URL_PLACEHOLDER = "https://www.dlsite.com/maniax-touch/work/=/product_id/RJ01266914.html"
    const val ARTIST_PLACEHOLDER = "eno"
    const val CIRCLE_PLACEHOLDER = "榎屋"
    const val PRICE_PLACEHOLDER = "770円"
    const val NOTES_PLACEHOLDER = "先生がナギサ&サクラコとセックスします。"

    /**
     * Field -> message, in visual order. **Every** invalid field is reported in one pass (the form shows
     * them all at once); the URL rules themselves are exactly [BookmarkRules.validate]'s.
     */
    fun validate(title: String, url: String): Map<String, String> {
        val errors = LinkedHashMap<String, String>()
        val name = title.trim()
        val link = url.trim()
        if (name.isEmpty()) {
            errors[FIELD_TITLE] = BookmarkRules.ERROR_TITLE
        }
        when {
            link.isEmpty() -> errors[FIELD_URL] = BookmarkRules.ERROR_URL_MISSING
            !link.startsWith("https://", ignoreCase = true) -> errors[FIELD_URL] = BookmarkRules.ERROR_HTTPS
            BookmarkRules.domainOf(link) == null -> errors[FIELD_URL] = BookmarkRules.ERROR_URL_SHAPE
        }
        return errors
    }

    /** The topmost invalid field, or null when everything is valid. */
    fun firstInvalid(errors: Map<String, String>): String? = errors.keys.firstOrNull()
}

/**
 * The Artist rows of the bookmark form: at least one row, any number of additional rows, each
 * independently editable and removable, and blank rows are dropped before saving.
 *
 * Arrangement is fixed: **row 0 always carries the `+`** (add) control, and **every additional row
 * carries its own `×`** (remove) control. The `+` never moves to the newest row.
 */
object ArtistFields {

    /** Which trailing control a row shows: `+` for the first row only, `×` for every other row. */
    enum class RowAction { ADD, REMOVE }

    fun actionFor(index: Int, count: Int): RowAction = if (index == 0) RowAction.ADD else RowAction.REMOVE

    /** The form always shows one row, even when the bookmark has no artist. */
    fun rows(artists: List<String>): List<String> = artists.ifEmpty { listOf("") }

    /** `+`: appends one more empty, independently editable row; row 0 keeps the `+`. */
    fun add(artists: List<String>): List<String> = artists + ""

    /** Removes one row without touching the others; the form keeps at least one row. */
    fun removeAt(artists: List<String>, index: Int): List<String> {
        if (index !in artists.indices) return artists
        val next = artists.filterIndexed { i, _ -> i != index }
        return next.ifEmpty { listOf("") }
    }

    /** What gets stored: trimmed, non-blank, order preserved (no silent merging of distinct names). */
    fun values(artists: List<String>): List<String> =
        artists.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
