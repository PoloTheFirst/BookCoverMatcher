package com.bookcovermatcher.core.catalog

import java.time.Instant
import java.time.ZoneId

/** The three date meanings, kept apart on purpose. */
enum class TimelineMode(val label: String) {
    /** When the work was published, from the column the user chose for it. */
    PUBLICATION("Published"),

    /** When the user acquired the work, from the column the user chose for it. */
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
 * collection stays complete and the gap stays visible. A mode with no usable date column is reported as
 * unavailable with a reason instead of showing an empty timeline; [dateColumns] is the evidence — the
 * state of every worksheet's chosen column, including one that has since been renamed.
 */
data class CollectionTimeline(
    val mode: TimelineMode,
    val available: Boolean,
    val unavailableReason: String? = null,
    val sections: List<TimelineSection> = emptyList(),
    val unknown: List<TimelineEntry> = emptyList(),
    val ascending: Boolean = false,
    /** Per-worksheet state of the chosen date column; empty for the import timeline. */
    val dateColumns: List<DateColumnReport> = emptyList(),
) {
    val datedCount: Int get() = sections.sumOf { it.entries.size }
    val unknownCount: Int get() = unknown.size
    val total: Int get() = datedCount + unknownCount
}

object CollectionTimelineBuilder {

    /**
     * The Published timeline, read from the column the user chose for each worksheet.
     *
     * Nothing is assigned automatically: an unconfigured workbook gets a setup message instead of a
     * timeline built from whichever header happens to look like a date.
     */
    fun published(
        catalog: WorkbookCatalog,
        settings: TimelineDateSettings,
        ascending: Boolean = false,
    ): CollectionTimeline = dated(catalog, TimelineMode.PUBLICATION, settings, ascending)

    /**
     * The Acquired timeline, from its own column choice.
     *
     * It never borrows the publication date, and the publication timeline never borrows this one: the two
     * columns are chosen separately, and a workbook with only one of them configured gets one timeline.
     */
    fun acquired(
        catalog: WorkbookCatalog,
        settings: TimelineDateSettings,
        ascending: Boolean = false,
    ): CollectionTimeline = dated(catalog, TimelineMode.ACQUISITION, settings, ascending)

    private fun dated(
        catalog: WorkbookCatalog,
        mode: TimelineMode,
        settings: TimelineDateSettings,
        ascending: Boolean,
    ): CollectionTimeline {
        val reports = TimelineDates.inspectAll(catalog, settings, mode)
        val bySheet = reports.associateBy { it.sheetName }

        val dated = ArrayList<Pair<CatalogDate, TimelineEntry>>()
        val unknown = ArrayList<TimelineEntry>()
        for (entry in catalog.entries) {
            val table = catalog.tables[entry.sheetName]
            val column = bySheet[entry.sheetName]?.column
            val raw = if (table != null && column != null) TimelineDates.valueAt(table, entry.row, column) else null
            val base = entryOf(entry, CatalogDates.parse(raw, allowSerial = true))
            val date = base.date
            if (date == null) unknown.add(base) else dated.add(date to base)
        }

        val ordered = dated.sortedWith(
            compareBy<Pair<CatalogDate, TimelineEntry>> { it.first.sortKey }.thenBy { it.second.recordId },
        ).let { if (ascending) it else it.reversed() }
        val available = dated.isNotEmpty()
        return CollectionTimeline(
            mode = mode,
            available = available,
            unavailableReason = if (available) null else unavailableReason(mode, reports),
            sections = sectionsOf(ordered.map { it.second }),
            unknown = unknown.sortedWith(compareBy({ it.sheetName.lowercase() }, { it.row }, { it.recordId })),
            ascending = ascending,
            dateColumns = reports,
        )
    }

    /** Why a timeline has nothing to show — the setup case, the stale case, or an unreadable column. */
    private fun unavailableReason(mode: TimelineMode, reports: List<DateColumnReport>): String {
        val label = mode.label.lowercase()
        val chosen = reports.filter { it.hasChoice }
        return when {
            chosen.isEmpty() ->
                "No column is assigned to the $label date yet. Choose the column that holds it in " +
                    "Configure date columns \u2014 the app does not guess it from a header name."
            chosen.none { it.isUsable } ->
                "The column(s) assigned to the $label date cannot be used. " +
                    chosen.joinToString(" ") { "${it.sheetName}: ${it.message}" }
            else ->
                "The $label column is assigned, but no record has a value in it that can be read as a date."
        }
    }

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