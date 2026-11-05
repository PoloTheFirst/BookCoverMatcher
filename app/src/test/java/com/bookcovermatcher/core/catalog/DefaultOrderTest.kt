package com.bookcovermatcher.core.catalog

import com.bookcovermatcher.core.excel.comments.CatalogueField
import com.bookcovermatcher.core.excel.comments.CatalogueRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Default Order mapping and how the Timeline breaks a tie with it.
 *
 * The date stays the primary key: Default Order only decides the order of records that share a date, is
 * always read ascending (also when the timeline shows newest first), and is never invented for a record
 * that has no value — those come last, in a deterministic order.
 */
class DefaultOrderTest {

    private companion object {
        const val ORDER_HEADER = "通し番号"
        const val ORDER_COLUMN = 9
    }

    /** The nine fixture headers plus the workbook's own order column. */
    private fun headers() = CatalogFixtures.fields() + CatalogueField(ORDER_HEADER, ORDER_COLUMN, ORDER_COLUMN)

    /** A profile that maps the fixture headers **and** the Default Order column. */
    private fun profile(orderHeader: String = ORDER_HEADER): ColumnMappingProfile = ColumnMappingProfile(
        "With order",
        CatalogFixtures.profile().columns + (CatalogField.DEFAULT_ORDER to orderHeader),
    )

    /** One ten-value record: the fixture columns in order, with the order value last. */
    private fun row(
        row: Int,
        title: String = "Book $row",
        published: String = "2024-05-17",
        order: String = "",
    ): CatalogueRecord = CatalogueRecord(
        row,
        listOf(
            title, "Circle $row", "Artist $row", published, "ID-$row",
            "https://e.example/$row", "1200", "tag-a", "note $row", order,
        ),
    )

    private fun catalog(rows: List<CatalogueRecord>, profile: ColumnMappingProfile = profile()): WorkbookCatalog {
        val analysis = CatalogFixtures.analysis(
            tables = listOf(CatalogFixtures.table(fields = headers(), records = rows)),
        )
        return WorkbookCatalogBuilder.build(
            CatalogFixtures.SHA,
            analysis.sheets,
            analysis.tables,
            analysis.images,
            profile,
        )
    }

    /** The order values as the Timeline lists them, in the direction the user is reading. */
    private fun timelineOrder(
        catalog: WorkbookCatalog,
        acquired: Boolean = false,
        ascending: Boolean = false,
    ): List<String> {
        val settings = if (acquired) {
            CatalogFixtures.dates(published = null, acquired = "発行日")
        } else {
            CatalogFixtures.dates(published = "発行日", acquired = null)
        }
        val timeline = if (acquired) {
            CollectionTimelineBuilder.acquired(catalog, settings, ascending)
        } else {
            CollectionTimelineBuilder.published(catalog, settings, ascending)
        }
        return CollectionTimelineBuilder.ordered(timeline.sections, ascending)
            .flatMap { it.entries }
            .map { it.defaultOrder ?: "-" }
    }

    @Test
    fun `the field is optional, so a record without it is not an error`() {
        assertFalse(CatalogField.DEFAULT_ORDER.required)
        val plain = catalog(listOf(row(1)), profile = CatalogFixtures.profile())
        assertNull(plain.entries.single().defaultOrder)
        // The value is the workbook's own text, kept exactly: leading zeros included.
        assertEquals("0012", catalog(listOf(row(1, order = "0012"))).entries.single().defaultOrder)
    }

    @Test
    fun `records that share a published date follow the default order, numerically`() {
        val catalog = catalog(
            listOf(
                row(1, "Ten", order = "10"),
                row(2, "Two", order = "2"),
                row(3, "Hundred", order = "100"),
            ),
        )
        // Numeric, not alphabetical: 2 < 10 < 100 (an alphabetical sort would give 10, 100, 2).
        assertEquals(listOf("2", "10", "100"), timelineOrder(catalog))
    }

    @Test
    fun `the same rule applies to the acquired date, independently`() {
        val catalog = catalog(listOf(row(1, "Ten", order = "10"), row(2, "Two", order = "2")))
        assertEquals(listOf("2", "10"), timelineOrder(catalog, acquired = true))
    }

    @Test
    fun `the date direction flips but the tie-break never does`() {
        val catalog = catalog(
            listOf(
                row(1, "Older", published = "2020-01-01", order = "9"),
                row(2, "NewerA", order = "2"),
                row(3, "NewerB", order = "10"),
            ),
        )
        // Newest first: the later date group leads, and inside it the workbook order still ascends.
        assertEquals(listOf("2", "10", "9"), timelineOrder(catalog))
        // Oldest first: the groups swap, the pair inside the shared date keeps its order.
        assertEquals(listOf("9", "2", "10"), timelineOrder(catalog, ascending = true))
    }

    @Test
    fun `a missing or unreadable order comes last, deterministically`() {
        val catalog = catalog(
            listOf(
                row(1, "NoValue", order = ""),
                row(2, "TextValue", order = "abc"),
                row(3, "Number", order = "5"),
                row(4, "AnotherMissing", order = ""),
            ),
        )
        // 5 (number) < "abc" (text) < the two blanks, which fall back to the verified source identity.
        assertEquals(listOf("5", "abc", "-", "-"), timelineOrder(catalog))
        // The blanks keep their source-row order in both directions instead of being re-invented.
        val entries = CollectionTimelineBuilder.published(
            catalog,
            CatalogFixtures.dates(published = "発行日"),
        ).sections.flatMap { it.entries }
        assertEquals(listOf(1, 4), entries.filter { it.defaultOrder == null }.map { it.row })
    }

    @Test
    fun `without the mapping the timeline keeps its documented fallback`() {
        val analysis = CatalogFixtures.analysis(
            tables = listOf(
                CatalogFixtures.table(
                    records = listOf(
                        CatalogFixtures.catalogueRecord(1, title = "Zulu"),
                        CatalogFixtures.catalogueRecord(2, title = "Alpha"),
                    ),
                ),
            ),
        )
        val catalog = WorkbookCatalogBuilder.build(
            CatalogFixtures.SHA,
            analysis.sheets,
            analysis.tables,
            analysis.images,
            CatalogFixtures.profile(),
        )
        // No Default Order column in the profile: the verified source row decides, not the title.
        assertEquals(
            listOf(1, 2),
            CollectionTimelineBuilder.published(catalog, CatalogFixtures.dates())
                .sections.flatMap { it.entries }.map { it.row },
        )
    }

    @Test
    fun `changing the mapped column changes the order`() {
        val rows = listOf(row(1, "One", order = "2"), row(2, "Two", order = "1"))
        assertEquals(listOf("1", "2"), timelineOrder(catalog(rows)))
        // The same workbook mapped through another column: the order follows the new mapping.
        val byCircle = catalog(rows, profile(orderHeader = "サークル"))
        assertEquals(listOf("Circle 1", "Circle 2"), timelineOrder(byCircle))
    }

    @Test
    fun `a text order sorts predictably and keeps its source text`() {
        val catalog = catalog(
            listOf(
                row(1, "B", order = "b-02"),
                row(2, "A", order = "A-10"),
                row(3, "C", order = "c-01"),
            ),
        )
        assertEquals(listOf("A-10", "b-02", "c-01"), timelineOrder(catalog))
    }

    @Test
    fun `the value compares as a number without being rewritten`() {
        // The stored text is untouched; only the comparison reads it as a number.
        assertEquals("0012", DefaultOrderValue("0012").text)
        assertEquals(0, DefaultOrderValue("0012").compareTo(DefaultOrderValue("12")))
        // Numbers first, then text, then nothing.
        assertEquals(0, DefaultOrderValue("7").group)
        assertEquals(1, DefaultOrderValue("seven").group)
        assertEquals(2, DefaultOrderValue(null).group)
        assertEquals(2, DefaultOrderValue("   ").group)
        assertNull(DefaultOrderValue(null).number)
        // Missing sorts after everything.
        assertTrue(DefaultOrderValue("7").compareTo(DefaultOrderValue(null)) < 0)
    }
}
