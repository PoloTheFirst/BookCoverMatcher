// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.catalog

/**
 * The two date meanings the Timeline offers, kept apart on purpose.
 *
 * There is deliberately no "imported" mode: the app's own import timestamp is workbook metadata, not a
 * date the collection has, and a timeline built from it duplicated both other modes for every record.
 */
enum class TimelineMode(val label: String) {
    /** When the work was published, from the column the user chose for it. */
    PUBLICATION("Published"),

    /** When the user acquired the work, from the column the user chose for it. */
    ACQUISITION("Acquired"),
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
    /**
     * The record's Default Order value (Section 7.2 of the release scope), exactly as the workbook spells it.
     * It breaks a tie between two records that share a date; it never replaces the date as the primary key.
     */
    val defaultOrder: String? = null,
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
    /** Per-worksheet state of the chosen date column. */
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
        date1904: Boolean = false,
    ): CollectionTimeline = dated(catalog, TimelineMode.PUBLICATION, settings, ascending, date1904)

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
        date1904: Boolean = false,
    ): CollectionTimeline = dated(catalog, TimelineMode.ACQUISITION, settings, ascending, date1904)

    private fun dated(
        catalog: WorkbookCatalog,
        mode: TimelineMode,
        settings: TimelineDateSettings,
        ascending: Boolean,
        date1904: Boolean = false,
    ): CollectionTimeline {
        val reports = TimelineDates.inspectAll(catalog, settings, mode, date1904)
        val bySheet = reports.associateBy { it.sheetName }

        val dated = ArrayList<Pair<CatalogDate, TimelineEntry>>()
        val unknown = ArrayList<TimelineEntry>()
        for (entry in catalog.entries) {
            val table = catalog.tables[entry.sheetName]
            val column = bySheet[entry.sheetName]?.column
            val raw = if (table != null && column != null) TimelineDates.valueAt(table, entry.row, column) else null
            val base = entryOf(entry, CatalogDates.parse(raw, allowSerial = true, date1904 = date1904))
            val date = base.date
            if (date == null) unknown.add(base) else dated.add(date to base)
        }

        val ordered = entriesInOrder(dated, ascending)
        val available = dated.isNotEmpty()
        return CollectionTimeline(
            mode = mode,
            available = available,
            unavailableReason = if (available) null else unavailableReason(mode, reports),
            sections = sectionsOf(ordered),
            unknown = unknown.sortedWith(compareBy({ it.sheetName.lowercase() }, { it.row }, { it.recordId })),
            ascending = ascending,
            dateColumns = reports,
        )
    }

    /**
     * The chronological order of a timeline: the date first, then the record's Default Order, then the
     * verified source identity.
     *
     * The date direction follows [ascending]; the Default Order tie-break is **always ascending**, so the
     * same two records keep their workbook order whether the user reads newest first or oldest first. A
     * record without a readable Default Order sorts after the ones that have one instead of being given a
     * position it does not claim; the record identity is the final, fully deterministic fallback.
     */
    fun entriesInOrder(
        dated: List<Pair<CatalogDate, TimelineEntry>>,
        ascending: Boolean,
    ): List<TimelineEntry> {
        val order = compareBy<Pair<CatalogDate, TimelineEntry>> {
            DefaultOrderValue(it.second.defaultOrder)
        }.thenBy { it.second.recordId }
        val byDate = if (ascending) {
            compareBy<Pair<CatalogDate, TimelineEntry>> { it.first.sortKey }
        } else {
            compareByDescending<Pair<CatalogDate, TimelineEntry>> { it.first.sortKey }
        }
        return dated.sortedWith(byDate.then(order)).map { it.second }
    }

    /**
     * The same timeline in the other chronological direction, without rebuilding it.
     *
     * Entries are re-read from their own dates and Default Order, so flipping the direction never reverses
     * the tie-break and never moves an unknown-date record into a year.
     */
    fun ordered(sections: List<TimelineSection>, ascending: Boolean): List<TimelineSection> {
        val dated = sections.flatMap { section -> section.entries }.mapNotNull { entry ->
            entry.date?.let { it to entry }
        }
        return sectionsOf(entriesInOrder(dated, ascending))
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
     * One record as a timeline entry, with the date this timeline established (null = unknown).
     *
     * Kept private and shared by both modes, so a Published entry and an Acquired entry of the same record
     * can never describe it differently.
     */
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
        defaultOrder = entry.defaultOrder,
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