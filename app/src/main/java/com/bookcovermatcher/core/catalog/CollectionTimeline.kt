package com.bookcovermatcher.core.catalog

import java.time.Instant
import java.time.ZoneId

/** The three date meanings, kept apart on purpose. */
enum class TimelineMode(val label: String) {
    /** When the work was published, from the mapped publication column. */
    PUBLICATION("Published"),

    /** When the user acquired the work. The app does not record this, so the mode is unavailable. */
    ACQUISITION("Acquired"),

    /** When the workbook copy was imported into the app, from the saved-workbook library. */
    IMPORT("Imported"),
}

/** One record on the timeline. [date] is null only in the unknown bucket. */
data class TimelineEntry(
    val recordId: String,
    val title: String,
    val circle: String?,
    val artist: String?,
    val identifier: String?,
    val mediaPath: String?,
    val date: CatalogDate?,
    val sheetName: String,
    val row: Int,
) {
    /** `LIST!128`, the one-based source row. */
    val locationLabel: String get() = "$sheetName!${row + 1}"

    /** `2024-05-17`, or "No date" — never a guessed date. */
    val dateLabel: String get() = date?.label ?: "No date"
}

/** The entries of one year, newest first unless the timeline is ascending. */
data class TimelineSection(val year: Int, val entries: List<TimelineEntry>)

/**
 * A timeline for one date meaning.
 *
 * Records whose date is absent or unreadable are **never dropped**: they are listed in [unknown] so the
 * collection stays complete and the gap stays visible. A mode the app cannot substantiate is reported as
 * unavailable with a reason instead of showing an empty timeline.
 */
data class CollectionTimeline(
    val mode: TimelineMode,
    val available: Boolean,
    val unavailableReason: String? = null,
    val sections: List<TimelineSection> = emptyList(),
    val unknown: List<TimelineEntry> = emptyList(),
    val ascending: Boolean = false,
) {
    val datedCount: Int get() = sections.sumOf { it.entries.size }
    val unknownCount: Int get() = unknown.size
    val total: Int get() = datedCount + unknownCount
}

object CollectionTimelineBuilder {

    /** Publication dates come from the mapped publication column, at the precision it has. */
    fun publication(catalog: WorkbookCatalog, ascending: Boolean = false): CollectionTimeline {
        val mapped = catalog.entries.any { it.state(CatalogField.PUBLISHED) != FieldState.UNMAPPED }
        val dated = ArrayList<Pair<CatalogDate, TimelineEntry>>()
        val unknown = ArrayList<TimelineEntry>()
        for (entry in catalog.entries) {
            val base = entryOf(entry, CatalogDates.parse(entry.text(CatalogField.PUBLISHED)))
            val date = base.date
            if (date == null) unknown.add(base) else dated.add(date to base)
        }
        val ordered = dated.sortedWith(
            compareBy<Pair<CatalogDate, TimelineEntry>> { it.first.sortKey }.thenBy { it.second.recordId },
        ).let { if (ascending) it else it.reversed() }
        return CollectionTimeline(
            mode = TimelineMode.PUBLICATION,
            available = mapped && dated.isNotEmpty(),
            unavailableReason = if (!mapped) {
                "No publication column is mapped for this workbook, so records cannot be placed on a timeline " +
                    "by publication date. Map a date column in Fields, or use the unknown-date list below."
            } else if (dated.isEmpty()) {
                "The mapped publication column has no readable dates, so every record is in the unknown bucket."
            } else {
                null
            },
            sections = sectionsOf(ordered.map { it.second }),
            unknown = unknown.sortedBy { it.recordId },
            ascending = ascending,
        )
    }

    /**
     * Acquisition dates are not part of the data model: the app records when a *bookmark* was created,
     * not when a physical work was acquired, so this mode says so instead of substituting another date.
     */
    fun acquisition(catalog: WorkbookCatalog): CollectionTimeline = CollectionTimeline(
        mode = TimelineMode.ACQUISITION,
        available = false,
        unavailableReason = "The app does not record an acquisition date for workbook records, and an import " +
            "date is not an acquisition date. Nothing is substituted.",
        unknown = catalog.entries.map { entryOf(it, null) }.sortedBy { it.recordId },
    )

    /**
     * Import dates come from the saved-workbook library (`importedAt`). A workbook that was only staged,
     * never imported, has no import date and the mode reports that instead of inventing one.
     */
    fun imported(
        catalog: WorkbookCatalog,
        importedAtMillis: Long?,
        zone: ZoneId = ZoneId.systemDefault(),
        ascending: Boolean = false,
    ): CollectionTimeline {
        if (importedAtMillis == null || importedAtMillis <= 0L) {
            return CollectionTimeline(
                mode = TimelineMode.IMPORT,
                available = false,
                unavailableReason = "This workbook was not opened from the saved-workbook library, so the app has " +
                    "no import date for it. Import it in More \u2192 Saved workbooks to record one.",
                unknown = catalog.entries.map { entryOf(it, null) }.sortedBy { it.recordId },
            )
        }
        val day = Instant.ofEpochMilli(importedAtMillis).atZone(zone).toLocalDate()
        val date = CatalogDate(day.year, day.monthValue, day.dayOfMonth)
        val entries = catalog.entries.map { entryOf(it, date) }
            .sortedWith(compareBy({ it.sheetName.lowercase() }, { it.row }, { it.recordId }))
        return CollectionTimeline(
            mode = TimelineMode.IMPORT,
            available = true,
            sections = sectionsOf(if (ascending) entries else entries.reversed()),
            ascending = ascending,
        )
    }

    private fun entryOf(entry: CatalogEntry, date: CatalogDate?) = TimelineEntry(
        recordId = entry.id,
        title = entry.title.orEmpty(),
        circle = entry.text(CatalogField.CIRCLE),
        artist = entry.text(CatalogField.ARTIST),
        identifier = entry.text(CatalogField.IDENTIFIER),
        mediaPath = entry.images.firstOrNull { it.isVerified }?.mediaPath,
        date = date,
        sheetName = entry.sheetName,
        row = entry.row,
    )

    /** Groups by year, keeping the order the entries arrived in (newest first unless ascending). */
    private fun sectionsOf(entries: List<TimelineEntry>): List<TimelineSection> {
        val byYear = LinkedHashMap<Int, MutableList<TimelineEntry>>()
        for (entry in entries) {
            val year = entry.date?.year ?: continue
            byYear.getOrPut(year) { ArrayList() }.add(entry)
        }
        return byYear.map { (year, items) -> TimelineSection(year, items) }
    }
}