package com.bookcovermatcher.core.catalog

/**
 * What the user asked the timeline to show.
 *
 * [query] matches the descriptive fields a record actually has (title, circle, artist, product id and the
 * worksheet row). [fromYear]/[toYear] restrict the period; either bound may stand alone, and a reversed
 * pair is read in the order the user meant it. Nothing here is derived from a header guess: a year only
 * comes from the date column the user assigned to that timeline.
 */
data class TimelineFilter(
    val query: String = "",
    val fromYear: Int? = null,
    val toYear: Int? = null,
) {
    val hasRange: Boolean get() = fromYear != null || toYear != null

    val isActive: Boolean get() = query.isNotBlank() || hasRange

    /** The lower bound: the "from" year, or the smaller of the two when both are set. */
    val lowerYear: Int?
        get() = when {
            fromYear == null -> null
            toYear == null -> fromYear
            else -> minOf(fromYear, toYear ?: fromYear)
        }

    /** The upper bound: the "to" year, or the larger of the two when both are set. */
    val upperYear: Int?
        get() = when {
            toYear == null -> null
            fromYear == null -> toYear
            else -> maxOf(fromYear, toYear ?: fromYear)
        }

    /** `2019–2024`, `from 2019`, `up to 2024` or `All years`. */
    val rangeLabel: String
        get() = when {
            !hasRange -> "All years"
            lowerYear == null -> "up to ${upperYear ?: 0}"
            upperYear == null -> "from ${lowerYear ?: 0}"
            lowerYear == upperYear -> "${lowerYear ?: 0}"
            else -> "${lowerYear ?: 0}\u2013${upperYear ?: 0}"
        }

    fun withQuery(text: String): TimelineFilter = copy(query = text)

    /** Sets (or clears) the range; a single bound is an open-ended range. */
    fun withRange(from: Int? = fromYear, to: Int? = toYear): TimelineFilter = copy(fromYear = from, toYear = to)

    fun cleared(): TimelineFilter = TimelineFilter()

    /** Identity of this filter, for "has the displayed selection changed" comparisons. */
    val key: String
        get() = "${query.trim()}|${lowerYear ?: ""}|${upperYear ?: ""}"
}

/**
 * A timeline after the filter, plus what the filter had to leave out.
 *
 * [withoutDateExcluded] counts records the date range could not judge because their own date is absent or
 * unreadable. They are never given a year to make them fit, and the screen says how many there are instead
 * of dropping them silently.
 */
data class TimelineView(
    val timeline: CollectionTimeline,
    val filter: TimelineFilter,
    val withoutDateExcluded: Int = 0,
) {
    val isEmpty: Boolean get() = timeline.sections.isEmpty() && timeline.unknown.isEmpty()
    val shown: Int get() = timeline.datedCount + timeline.unknownCount
}

/**
 * Filtering a timeline that was already built from real records.
 *
 * Nothing is re-parsed and nothing is invented: a record keeps its own date (or its place in the unknown
 * bucket), the year groups keep their order, and an empty result is an empty result — not a fallback to
 * the whole collection.
 */
object TimelineFiltering {

    /** True when [entry] matches the text of the filter; an empty query matches everything. */
    fun matchesQuery(entry: TimelineEntry, query: String): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        return listOf(
            entry.title,
            entry.circle,
            entry.artist,
            entry.identifier,
            entry.locationLabel,
        ).any { value -> value?.contains(needle, ignoreCase = true) == true }
    }

    /** True when [entry]'s own date falls inside the range; a record without a date never does. */
    fun matchesRange(entry: TimelineEntry, filter: TimelineFilter): Boolean {
        if (!filter.hasRange) return true
        val year = entry.date?.year ?: return false
        val lower = filter.lowerYear
        val upper = filter.upperYear
        return (lower == null || year >= lower) && (upper == null || year <= upper)
    }

    fun matches(entry: TimelineEntry, filter: TimelineFilter): Boolean =
        matchesQuery(entry, filter.query) && matchesRange(entry, filter)

    /**
     * Applies [filter] to [timeline].
     *
     * Unknown-date records stay visible while no range is chosen (a text search still applies to them); a
     * range excludes them and reports the number in [TimelineView.withoutDateExcluded].
     */
    fun apply(timeline: CollectionTimeline, filter: TimelineFilter): TimelineView {
        if (!filter.isActive) return TimelineView(timeline, filter)
        val sections = timeline.sections.mapNotNull { section ->
            val entries = section.entries.filter { matches(it, filter) }
            if (entries.isEmpty()) null else TimelineSection(section.year, entries)
        }
        val unknown = timeline.unknown.filter { entry ->
            matchesQuery(entry, filter.query) && !filter.hasRange
        }
        val excluded = if (filter.hasRange) {
            timeline.unknown.count { matchesQuery(it, filter.query) }
        } else {
            0
        }
        return TimelineView(
            timeline = timeline.copy(
                sections = sections,
                unknown = unknown,
                // A filter narrows what is shown; it does not make an unconfigured timeline usable.
                available = timeline.available,
            ),
            filter = filter,
            withoutDateExcluded = excluded,
        )
    }
}
