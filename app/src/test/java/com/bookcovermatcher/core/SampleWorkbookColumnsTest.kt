package com.bookcovermatcher.core

import com.bookcovermatcher.core.catalog.CatalogField
import com.bookcovermatcher.core.catalog.CatalogFixtures
import com.bookcovermatcher.core.catalog.CollectionTimelineBuilder
import com.bookcovermatcher.core.catalog.ColumnMappings
import com.bookcovermatcher.core.catalog.DateColumnState
import com.bookcovermatcher.core.catalog.SheetDateChoice
import com.bookcovermatcher.core.catalog.TimelineDateSettings
import com.bookcovermatcher.core.catalog.TimelineDates
import com.bookcovermatcher.core.catalog.TimelineMode
import com.bookcovermatcher.core.catalog.WorkbookCatalogBuilder
import com.bookcovermatcher.core.excel.JavaZipSource
import com.bookcovermatcher.core.excel.comments.CatalogueFiltering
import com.bookcovermatcher.core.excel.comments.CatalogueReader
import com.bookcovermatcher.core.excel.comments.CatalogueTable
import com.bookcovermatcher.core.excel.comments.CellValue
import com.bookcovermatcher.core.excel.comments.SheetCells
import com.bookcovermatcher.core.excel.comments.SheetLinks
import com.bookcovermatcher.core.excel.comments.SheetWindows
import com.bookcovermatcher.core.excel.comments.WorkbookSheets
import com.bookcovermatcher.core.excel.comments.WorkbookStyles
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The supplied sample workbook (`test_excel/doujin.xlsx`) as a **representative** fixture.
 *
 * It is 40 MB and is not committed, so these tests are skipped when it is absent. They check the shape of
 * the workbook that was reported as broken — 24 columns, the Twitter and Misskey columns at the right edge,
 * a serial-number publication date — and nothing in the app depends on those names: the same behaviour is
 * pinned with synthetic workbooks in `WorksheetColumnsTest` and `TimelineDatesTest`.
 */
class SampleWorkbookColumnsTest {

    private val file = File("../test_excel/doujin.xlsx")

    private fun <T> withListSheet(block: (CatalogueTable, List<CellValue>) -> T): T {
        assumeTrue("sample workbook not present: ${file.path}", file.isFile)
        ZipFile(file).use { zip ->
            JavaZipSource(zip).use { source ->
                val sheet = WorkbookSheets.list(source).first { it.name == "LIST" }
                val table = CatalogueReader.read(source, sheet.partPath, sheet.name)
                assertNotNull("LIST must read as a catalogue table", table)
                return block(table!!, SheetCells.read(source, sheet.partPath))
            }
        }
    }

    @Test
    fun `the list worksheet keeps all twenty four columns`() = withListSheet { table, _ ->
        assertEquals(24, table.fields.size)
        assertEquals("A", table.fields.first().letter)
        assertEquals("X", table.fields.last().letter)
        assertEquals((0 until 24).toList(), table.fields.map { it.column })
        assertTrue("every column keeps a header", table.fields.all { it.hasHeader })
        // One value per column on every record: a dropped column would shorten the row.
        assertTrue("records keep 24 values", table.records.all { it.values.size == 24 })
        assertTrue(table.records.size > 100)
    }

    @Test
    fun `the twitter and misskey columns at the right edge are reachable`() = withListSheet { table, _ ->
        val twitter = table.fields.first { it.name == "\u30C4\u30A4\u30C3\u30BF\u30FC" }
        val twitterUser = table.fields.first { it.name == "\u30C4\u30A4\u30C3\u30BF\u30FC (\u30E6\u30FC\u30B6\u30FC\u540D)" }
        val misskey = table.fields.first { it.name == "\u30DF\u30B9\u30AD\u30FC" }
        val misskeyUser = table.fields.first { it.name == "\u30DF\u30B9\u30AD\u30FC (\u30E6\u30FC\u30B6\u30FC\u540D)" }

        // S, T, U, V — the columns that used to be cut off at the sixteenth.
        assertEquals(listOf("S", "T", "U", "V"), listOf(twitter, twitterUser, misskey, misskeyUser).map { it.letter })
        assertEquals(listOf(18, 19, 20, 21), listOf(twitter, twitterUser, misskey, misskeyUser).map { it.column })

        val first = table.records.first()
        assertTrue("the twitter handle column has a value", first.value(twitterUser).isNotEmpty())
        // The tweet text and the handle are two different columns, not one value read twice.
        assertTrue(first.value(twitter) != first.value(twitterUser))
    }

    @Test
    fun `publication and purchase dates can be chosen and read`() = withListSheet { table, _ ->
        val published = TimelineDates.inspect(table, TimelineMode.PUBLICATION, "\u51FA\u7248\u65E5\u4ED8")
        assertEquals("G", CatalogueFiltering.columnLetter(published.column!!))
        assertEquals(DateColumnState.READY, published.state)
        assertTrue(published.readable > 0)

        val acquired = TimelineDates.inspect(table, TimelineMode.ACQUISITION, "\u8CFC\u5165\u65E5")
        assertEquals("R", CatalogueFiltering.columnLetter(acquired.column!!))
        assertEquals(DateColumnState.READY, acquired.state)
        // These cells are Excel serial numbers, not text: the app reads them because the user chose the
        // column, and the parsed day lands in this century.
        val sample = acquired.samples.first { it.isReadable }
        assertTrue("serial date read: ${sample.raw}", sample.date!!.year in 1990..2100)
    }

    @Test
    fun `the source window draws the whole width of the list worksheet`() = withListSheet { table, cells ->
        val window = SheetWindows.build(
            sheetName = "LIST",
            sheetPath = "xl/worksheets/sheet5.xml",
            cells = cells,
            targetRow = table.records.first().row,
            targetColumn = 0,
            headerRow = table.headerRow,
        )
        assertEquals(24, window.columns.size)
        assertEquals(0, window.hiddenColumns)
        assertEquals("X", window.columns.last().letter)
        assertEquals(24, window.rows.first().values.size)
    }

    @Test
    fun `the sample workbook's date column reads as dates while its serial values stay`() {
        assumeTrue("sample workbook not present: ${file.path}", file.isFile)
        ZipFile(file).use { zip ->
            JavaZipSource(zip).use { source ->
                val styles = WorkbookStyles.read(source)
                assertFalse("the workbook declares its own number formats", styles.isEmpty)
                val sheet = WorkbookSheets.list(source).first { it.name == "LIST" }
                val table = CatalogueReader.read(source, sheet.partPath, sheet.name, styles = styles)
                assertNotNull("LIST must read as a catalogue table", table)
                val published = table!!.fields.first { it.name == "出版日付" }
                val dated = table.records.filter { it.value(published).isNotEmpty() }
                assertTrue(dated.isNotEmpty())

                // Stored: what Excel wrote. Displayed: the date its own format means.
                assertTrue(
                    "stored values are serial numbers: ${dated.take(3).map { it.value(published) }}",
                    dated.all { it.value(published).matches(Regex("\\d{4,6}(\\.\\d+)?")) },
                )
                assertTrue(
                    "displayed values are dates: ${dated.take(3).map { it.display(published) }}",
                    dated.all { it.display(published).matches(Regex("\\d{4}-\\d{2}-\\d{2}")) },
                )

                // The price and the page count are numbers: they must never become dates.
                val price = table.fields.first { it.name.contains("価格") }
                val pages = table.fields.first { it.name == "ページ数" }
                val record = table.records.first { it.value(price).isNotEmpty() && it.value(pages).isNotEmpty() }
                assertEquals(record.value(price), record.display(price))
                assertEquals(record.value(pages), record.display(pages))
                assertFalse(record.display(price).contains("-"))
            }
        }
    }

    @Test
    fun `the serial column is offered as the default order and breaks a date tie`() = withListSheet { table, _ ->
        // Detection is a starting point, not a decision: the field is offered because the workbook has a
        // column whose header the field knows, and the user can change or clear it.
        val profile = ColumnMappings.detectedProfile(table)
        assertEquals("通し番号", profile.columns[CatalogField.DEFAULT_ORDER])

        val catalog = WorkbookCatalogBuilder.build(
            workbookSha256 = "sample",
            sheets = CatalogFixtures.sheets("LIST"),
            tables = listOf(table),
            images = emptyList(),
            profile = profile,
        )
        assertTrue(catalog.entries.all { it.defaultOrder != null })
        // Every value is the workbook's own text, not an index the app invented.
        val first = catalog.entries.first()
        val column = table.fields.first { it.name == "通し番号" }
        assertEquals(table.records.first { it.row == first.row }.value(column), first.defaultOrder)

        val timeline = CollectionTimelineBuilder.published(
            catalog,
            TimelineDateSettings("sample", mapOf("LIST" to SheetDateChoice("出版日付", null))),
        )
        val ordered = CollectionTimelineBuilder.ordered(timeline.sections, ascending = false)
            .flatMap { it.entries }
        assertTrue("the sample has records to place", ordered.size > 100)
        // Inside one shared publication date the workbook's own order decides — the timeline does not
        // alphabetise the records that happen to have the same date.
        val tied = ordered.groupBy { it.date }.values.first { it.size > 1 }
        val values = tied.map { java.math.BigDecimal(it.defaultOrder!!) }
        assertEquals(values.sorted(), values)
    }

    @Test
    fun `the sample workbook's linked cells keep their destinations apart from their text`() = withListSheet { table, _ ->
        // The fixture really does store hyperlinks (its circle column links to a shop search), so this is a
        // regression check on the real thing rather than on a synthetic package.
        val linked = table.records.filter { record -> table.fields.any { record.linkOf(it) != null } }
        assertTrue("the sample stores hyperlinks", linked.isNotEmpty())

        val circle = table.fields.first { it.name == "サークル名" }
        val withLink = linked.first { it.linkOf(circle) != null }
        val link = withLink.linkOf(circle)!!
        assertTrue("external target: ${link.target}", link.target.startsWith("http"))
        assertTrue(link.isWeb)
        assertTrue(link.isOpenable)

        // The cell keeps its own text: the destination never replaces what the workbook displays.
        assertFalse(withLink.display(circle).startsWith("http"))
        assertEquals(withLink.value(circle), withLink.display(circle))

        // A column the workbook does not link reports no link, so nothing is fabricated from the text.
        val title = table.fields.first { it.name == "ブック名" }
        assertNull(linked.first().linkOf(title))
    }

    @Test
    fun `the source window reads the same dates and links as the catalogue table`() {
        assumeTrue("sample workbook not present: ${file.path}", file.isFile)
        ZipFile(file).use { zip ->
            JavaZipSource(zip).use { source ->
                val styles = WorkbookStyles.read(source)
                val sheet = WorkbookSheets.list(source).first { it.name == "LIST" }
                val cells = SheetCells.read(source, sheet.partPath)
                val links = SheetLinks.read(source, sheet.partPath)
                val table = CatalogueReader.fromCells(cells, sheet.name, styles = styles, links = links)
                assertNotNull("LIST must read as a catalogue table", table)
                val record = table!!.records.first { row -> row.values.any { it.isNotBlank() } }
                val window = SheetWindows.build(
                    sheetName = sheet.name,
                    sheetPath = sheet.partPath,
                    cells = cells,
                    targetRow = record.row,
                    targetColumn = 0,
                    headerRow = table.headerRow,
                    styles = styles,
                    links = links,
                )

                // One record, two views: every column must read exactly the same in both.
                for (field in table.fields) {
                    assertEquals(
                        "column ${field.letter} reads the same in the table and in the source preview",
                        record.display(field),
                        window.cellAt(record.row, field.column)?.shown,
                    )
                }
                // The workbook's own hyperlinks are attached to the same cells the table reports them for.
                val linked = table.fields.filter { record.linkOf(it) != null }
                assertTrue("the sample workbook stores hyperlinks", linked.isNotEmpty())
                for (field in linked) {
                    assertEquals(
                        "column ${field.letter} keeps its destination in the preview",
                        record.linkOf(field)?.target,
                        window.cellAt(record.row, field.column)?.link?.target,
                    )
                }
            }
        }
    }

    @Test
    fun `the workbook still holds the worksheets the report listed`() {
        assumeTrue("sample workbook not present: ${file.path}", file.isFile)
        ZipFile(file).use { zip ->
            JavaZipSource(zip).use { source ->
                assertEquals(
                    listOf("PIVOT - BAR ", "PIVOT - Tree", "PIVOT - Pie", "PIVOT - Area", "LIST", "DATA", "C108", "C109"),
                    WorkbookSheets.list(source).map { it.name },
                )
            }
        }
    }
}

