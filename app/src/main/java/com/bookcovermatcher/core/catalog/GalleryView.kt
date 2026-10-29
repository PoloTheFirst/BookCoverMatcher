package com.bookcovermatcher.core.catalog

/** How the visual browser orders its cards. */
enum class GallerySort(val label: String) {
    TITLE("Title"),
    CIRCLE("Circle"),
    ARTIST("Artist"),
    ROW("Source order"),
    COVER("Cover first"),
}

/**
 * The gallery filters. Each one is a property the parser actually established, so a filter can never
 * hide a record for a reason the user cannot see.
 */
data class GalleryFilter(
    val sheetName: String? = null,
    val query: String = "",
    val onlyWithCover: Boolean = false,
    val onlyWithoutCover: Boolean = false,
    val onlyMissingTitle: Boolean = false,
    val onlyMissingMetadata: Boolean = false,
) {
    val isActive: Boolean
        get() = sheetName != null || query.isNotBlank() || onlyWithCover || onlyWithoutCover ||
            onlyMissingTitle || onlyMissingMetadata
}

/**
 * Pure filtering and ordering for the Visual Spreadsheet Browser.
 *
 * A missing value sorts last in ascending order and ties break on the record id, so the grid is stable
 * between recompositions and a paging test can rely on it.
 */
object GalleryView {

    fun rows(
        entries: List<CatalogEntry>,
        filter: GalleryFilter,
        sort: GallerySort,
        ascending: Boolean = true,
    ): List<CatalogEntry> {
        val needle = filter.query.trim()
        val rows = entries.filter { entry ->
            (filter.sheetName == null || entry.sheetName == filter.sheetName) &&
                (needle.isEmpty() || matches(entry, needle)) &&
                (!filter.onlyWithCover || entry.hasVerifiedCover) &&
                (!filter.onlyWithoutCover || !entry.hasVerifiedCover) &&
                (!filter.onlyMissingTitle || entry.title.isNullOrBlank()) &&
                (!filter.onlyMissingMetadata || missingMetadata(entry) > 0)
        }
        val sorted = when (sort) {
            GallerySort.TITLE -> rows.sortedWith(textOrder { it.title })
            GallerySort.CIRCLE -> rows.sortedWith(textOrder { it.text(CatalogField.CIRCLE) })
            GallerySort.ARTIST -> rows.sortedWith(textOrder { it.text(CatalogField.ARTIST) })
            GallerySort.ROW -> rows.sortedWith(
                compareBy({ it.sheetName.lowercase() }, { it.row }, { it.id }),
            )
            GallerySort.COVER -> rows.sortedWith(
                compareByDescending<CatalogEntry> { it.hasVerifiedCover }
                    .thenBy { it.title.orEmpty().lowercase() }
                    .thenBy { it.id },
            )
        }
        return if (ascending) sorted else sorted.reversed()
    }

    /** Records with at least one mapped-but-blank optional field. Unmapped fields do not count. */
    fun missingMetadata(entry: CatalogEntry): Int = CatalogField.ordered.count { field ->
        !field.required && entry.state(field) == FieldState.MISSING
    }

    fun matches(entry: CatalogEntry, needle: String): Boolean = listOf(
        entry.title,
        entry.text(CatalogField.CIRCLE),
        entry.text(CatalogField.ARTIST),
        entry.text(CatalogField.IDENTIFIER),
        entry.text(CatalogField.URL),
        entry.locationLabel,
    ).any { it?.contains(needle, ignoreCase = true) == true }

    private fun textOrder(value: (CatalogEntry) -> String?): Comparator<CatalogEntry> =
        compareBy<CatalogEntry> { value(it)?.trim()?.lowercase() ?: "\uFFFF" }.thenBy { it.id }
}