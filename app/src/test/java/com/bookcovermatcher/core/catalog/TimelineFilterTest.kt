package com.bookcovermatcher.core.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The timeline filter: a text search over the record's own fields and a year range over its own date.
 *
 * The rules the tests pin are the honest ones: an unknown date never becomes a year, a range that cannot
 * judge a record says how many it left out, and clearing the filter restores exactly what was there.
 */
class TimelineFilterTest {

    private fun timeline(vararg published: String) = CollectionTimelineBuilder.published(
        CatalogFixtures.catalog(
            records = published.mapIndexed { index, date ->
                CatalogFixtures.catalogueRecord(
                    index + 1,
                    title = "Book ${index + 1}",
                    circle = if (index == 0) "Circle Alpha" else "Circle ${index + 1}",
                    published = date,
                )
            },
        ),
        CatalogFixtures.dates(),
    )

    @Test
    fun `an empty filter shows the whole timeline`() {
        val source = timeline("2024", "2020")
        val view = TimelineFiltering.apply(source, TimelineFilter())
        assertFalse(view.filter.isActive)
        assertEquals(2, view.shown)
        assertEquals(0, view.withoutDateExcluded)
        assertEquals(source.sections.map { it.year }, view.timeline.sections.map { it.year })
    }

    @Test
    fun `the text search matches the descriptive fields of a record`() {
        val source = timeline("2024", "2021", "2019")
        val byTitle = TimelineFiltering.apply(source, TimelineFilter(query = "book 2"))
        assertEquals(listOf(2021), byTitle.timeline.sections.map { it.year })

        val byCircle = TimelineFiltering.apply(source, TimelineFilter(query = "circle alpha"))
        assertEquals(listOf(2024), byCircle.timeline.sections.map { it.year })

        // Case does not matter, and a row label is searchable too.
        assertEquals(1, TimelineFiltering.apply(source, TimelineFilter(query = "ALPHA")).shown)
        assertEquals(1, TimelineFiltering.apply(source, TimelineFilter(query = "LIST!3")).shown)
    }

    @Test
    fun `a search without a match is empty, not the whole collection`() {
        val view = TimelineFiltering.apply(timeline("2024", "2020"), TimelineFilter(query = "nothing here"))
        assertTrue(view.isEmpty)
        assertEquals(0, view.shown)
    }

    @Test
    fun `a year range keeps only the years inside it and keeps their order`() {
        val source = timeline("2024", "2022-05", "2020", "2018")
        val view = TimelineFiltering.apply(source, TimelineFilter(fromYear = 2020, toYear = 2023))
        assertEquals(listOf(2022, 2020), view.timeline.sections.map { it.year })
        assertEquals(2, view.shown)
        assertEquals("2020\u20132023", TimelineFilter(fromYear = 2020, toYear = 2023).rangeLabel)
    }

    @Test
    fun `a single bound is an open-ended range and reversed bounds are read as a range`() {
        val source = timeline("2024", "2020", "2018")
        assertEquals(
            listOf(2024),
            TimelineFiltering.apply(source, TimelineFilter(fromYear = 2021)).timeline.sections.map { it.year },
        )
        assertEquals(
            listOf(2020, 2018),
            TimelineFiltering.apply(source, TimelineFilter(toYear = 2020)).timeline.sections.map { it.year },
        )
        // 2024 -> 2018 was chosen backwards; it is still the same range.
        assertEquals(
            listOf(2024, 2020, 2018),
            TimelineFiltering.apply(source, TimelineFilter(fromYear = 2024, toYear = 2018))
                .timeline.sections.map { it.year },
        )
        assertEquals("from 2021", TimelineFilter(fromYear = 2021).rangeLabel)
        assertEquals("up to 2020", TimelineFilter(toYear = 2020).rangeLabel)
    }

    @Test
    fun `records without a usable date stay visible until a range is chosen, then are counted`() {
        val source = timeline("2024", "", "17/05/2024")
        val unfiltered = TimelineFiltering.apply(source, TimelineFilter())
        assertEquals(2, unfiltered.timeline.unknownCount)
        assertEquals(0, unfiltered.withoutDateExcluded)

        val ranged = TimelineFiltering.apply(source, TimelineFilter(fromYear = 2024, toYear = 2024))
        assertEquals(1, ranged.shown)
        assertEquals(0, ranged.timeline.unknownCount)
        assertEquals("the unreadable dates are reported, never guessed", 2, ranged.withoutDateExcluded)
    }

    @Test
    fun `a text search still applies to the unknown-date bucket`() {
        val source = timeline("2024", "")
        val view = TimelineFiltering.apply(source, TimelineFilter(query = "Book 2"))
        assertEquals(0, view.timeline.datedCount)
        assertEquals(1, view.timeline.unknownCount)
        assertEquals("Book 2", view.timeline.unknown.single().title)
    }

    @Test
    fun `clearing the filter restores exactly the built timeline`() {
        val source = timeline("2024", "2020", "")
        val filtered = TimelineFiltering.apply(source, TimelineFilter(query = "Book 1", fromYear = 2024))
        assertEquals(1, filtered.shown)

        val cleared = TimelineFiltering.apply(source, TimelineFilter().cleared())
        assertEquals(source.datedCount, cleared.timeline.datedCount)
        assertEquals(source.unknownCount, cleared.timeline.unknownCount)
        assertEquals(source.available, cleared.timeline.available)
    }

    @Test
    fun `a filter never makes an unconfigured timeline usable`() {
        val source = CollectionTimelineBuilder.published(
            CatalogFixtures.catalog(),
            CatalogFixtures.dates(published = null),
        )
        val view = TimelineFiltering.apply(source, TimelineFilter(query = "Book"))
        assertFalse(view.timeline.available)
        assertEquals(source.unavailableReason, view.timeline.unavailableReason)
        assertEquals(TimelineMode.PUBLICATION, view.timeline.mode)
    }

    @Test
    fun `the filter identity changes with the query and the range only`() {
        val base = TimelineFilter()
        assertEquals(base.key, TimelineFilter().key)
        assertFalse(base.key == base.withQuery("a").key)
        assertFalse(base.key == base.withRange(2020, 2024).key)
        // Whitespace is not a query.
        assertEquals(base.key, base.withQuery("   ").key)
    }
}

