package com.bookcovermatcher.core

import com.bookcovermatcher.WorkbookCommentsUi
import com.bookcovermatcher.closeSource
import com.bookcovermatcher.core.excel.comments.CatalogueField
import com.bookcovermatcher.core.excel.comments.CatalogueRecord
import com.bookcovermatcher.core.excel.comments.CatalogueTable
import com.bookcovermatcher.core.excel.comments.CellValue
import com.bookcovermatcher.core.excel.comments.ImageRow
import com.bookcovermatcher.core.excel.comments.ImageSort
import com.bookcovermatcher.core.excel.comments.ImageState
import com.bookcovermatcher.core.excel.comments.LegacyCommentEntry
import com.bookcovermatcher.core.excel.comments.MediaAssociation
import com.bookcovermatcher.core.excel.comments.SheetCells
import com.bookcovermatcher.core.excel.comments.SheetInfo
import com.bookcovermatcher.core.excel.comments.SheetWindows
import com.bookcovermatcher.core.excel.comments.SourceLocation
import com.bookcovermatcher.core.excel.comments.SourceLocations
import com.bookcovermatcher.openSource
import com.bookcovermatcher.sourceBelongsTo
import com.bookcovermatcher.sourceFailed
import com.bookcovermatcher.sourceLoading
import com.bookcovermatcher.sourceWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verified image/record to worksheet location, and the window the source view draws.
 *
 * A destination exists only when the parser proved one. Unreferenced media, unresolved references
 * and stale worksheets must never receive a guess.
 */
class SourceNavigationTest {

    private val unicodeSheet = "\u8868\u7D19 \u4E00\u89A7 "
    private val listSheet = SheetInfo("LIST", "visible", null, "xl/worksheets/sheet1.xml")
    private val otherSheet = SheetInfo(unicodeSheet, "hidden", null, "xl/worksheets/sheet7.xml")

    private fun image(
        state: ImageState,
        associations: List<MediaAssociation> = emptyList(),
        detail: String? = null,
    ) = ImageRow(
        key = "xl/media/image1.png",
        mediaPath = if (state == ImageState.UNRESOLVED) null else "xl/media/image1.png",
        fileName = "image1.png",
        format = "png",
        sizeBytes = 12L,
        width = null,
        height = null,
        state = state,
        associations = associations,
        catalogue = emptyList(),
        unresolvedDetail = detail,
    )

    private fun table(): CatalogueTable = CatalogueTable(
        sheetName = "LIST",
        headerRow = 0,
        fields = listOf(
            CatalogueField("\u30E1\u30E2", 0, 0),
            CatalogueField("\u30BF\u30A4\u30C8\u30EB", 1, 2),
            CatalogueField("\u30B5\u30FC\u30AF\u30EB", 2, 5),
        ),
        records = listOf(CatalogueRecord(127, listOf("note", "Book", "Circle"))),
    )

    private fun comment(cell: String, sheet: String = "LIST") = LegacyCommentEntry(
        sheetName = sheet,
        sheetPath = "xl/worksheets/sheet1.xml",
        cell = cell,
        row = SheetCells.refToCell(cell)!!.first,
        col = SheetCells.refToCell(cell)!!.second,
        text = "note",
        author = null,
        media = emptyList(),
    )
    // ---- image, comment and record mapping ----------------------------------------------------

    @Test fun aLinkedImageResolvesToItsVerifiedCell() {
        val location = SourceLocations.ofImage(
            image(ImageState.LINKED, listOf(MediaAssociation("LIST", "D128", "cover"))),
            listOf(listSheet, otherSheet),
        )

        assertEquals(1, location.candidates.size)
        val destination = location.candidates.single()
        assertEquals("LIST", destination.sheetName)
        assertEquals("xl/worksheets/sheet1.xml", destination.sheetPath)
        assertEquals("D128", destination.cell)
        assertEquals(127, destination.row)
        assertEquals(3, destination.column)
        assertFalse("a comment cell is not a row anchor", destination.rowAnchor)
    }

    @Test fun everyVerifiedAssociationIsOfferedInsteadOfPickingOne() {
        val location = SourceLocations.ofImage(
            image(
                ImageState.LINKED,
                listOf(
                    MediaAssociation("LIST", "C5", "first"),
                    MediaAssociation(unicodeSheet, "B2", "second"),
                ),
            ),
            listOf(listSheet, otherSheet),
        )

        assertEquals(2, location.candidates.size)
        assertEquals(
            listOf("LIST!C5", "$unicodeSheet!B2"),
            location.candidates.map { "${it.sheetName}!${it.cell}" },
        )
    }

    @Test fun anUnreferencedImageHasNoDestination() {
        val location = SourceLocations.ofImage(image(ImageState.UNREFERENCED), listOf(listSheet))

        assertEquals(SourceLocation.Unreferenced, location)
        assertTrue(location.candidates.isEmpty())
    }

    @Test fun anUnresolvedReferenceKeepsItsReasonAndNoDestination() {
        val location = SourceLocations.ofImage(
            image(
                ImageState.UNRESOLVED,
                listOf(MediaAssociation("LIST", "C5", "broken")),
                detail = "missing media part xl/media/image9.png",
            ),
            listOf(listSheet),
        )

        assertTrue(location is SourceLocation.Unresolved)
        val unresolved = location as SourceLocation.Unresolved
        assertEquals("missing media part xl/media/image9.png", unresolved.detail)
        assertEquals("C5", unresolved.cell)
        assertTrue(location.candidates.isEmpty())
    }    @Test fun aCommentResolvesToItsOwnCell() {
        val location = SourceLocations.ofComment(comment("b7"), listOf(listSheet))

        assertEquals("B7", location.candidates.single().cell)
        assertEquals(6, location.candidates.single().row)
        assertEquals(1, location.candidates.single().column)
    }

    @Test fun aCatalogueRecordResolvesToItsVerifiedRow() {
        val table = table()
        val location = SourceLocations.ofRecord(table, table.records.single(), listOf(listSheet))

        val destination = location.candidates.single()
        assertEquals("LIST", destination.sheetName)
        assertEquals(127, destination.row)
        // The row is what the parser verified; the cell is that row's first field, labelled as an anchor.
        assertEquals("A128", destination.cell)
        assertEquals(0, destination.column)
        assertTrue(destination.rowAnchor)
    }

    @Test fun aStaleWorksheetOrCellIsNeverGuessed() {
        assertEquals(
            SourceLocation.Unknown,
            SourceLocations.ofImage(
                image(ImageState.LINKED, listOf(MediaAssociation("REMOVED", "C5", "old"))),
                listOf(listSheet),
            ),
        )
        assertEquals(
            SourceLocation.Unknown,
            SourceLocations.ofImage(
                image(ImageState.LINKED, listOf(MediaAssociation("LIST", "not-a-cell", "bad"))),
                listOf(listSheet),
            ),
        )
        assertEquals(
            SourceLocation.Unknown,
            SourceLocations.ofComment(comment("C5", "REMOVED"), listOf(listSheet)),
        )
        val moved = table().copy(sheetName = "REMOVED")
        assertEquals(
            SourceLocation.Unknown,
            SourceLocations.ofRecord(moved, moved.records.single(), listOf(listSheet)),
        )
    }

    @Test fun sheetNamesKeepSpacesUnicodeAndTrailingSpaces() {
        val sheets = listOf(listSheet, otherSheet)

        // The workbook's own spelling is the identity: a trimmed name must not match it.
        assertNull(SourceLocations.sheetOf(unicodeSheet.trim(), sheets))
        assertEquals(otherSheet, SourceLocations.sheetOf(unicodeSheet, sheets))

        val location = SourceLocations.ofImage(
            image(ImageState.LINKED, listOf(MediaAssociation(unicodeSheet, "B2", "cover"))),
            sheets,
        )
        assertEquals(unicodeSheet, location.candidates.single().sheetName)
        assertEquals("xl/worksheets/sheet7.xml", location.candidates.single().sheetPath)
    }

    @Test fun cellReferencesMatchExcelsOwnLetters() {
        assertEquals("A1", SheetCells.cellRef(0, 0))
        assertEquals("D128", SheetCells.cellRef(127, 3))
        assertEquals("AB1", SheetCells.cellRef(0, 27))
        assertEquals("A1", SheetCells.cellRef(-4, -2))
    }    // ---- state transitions ---------------------------------------------------------------------

    private fun listState() = WorkbookCommentsUi(
        workbookName = "DoujinNew.xlsx",
        sha256 = "abc",
        section = com.bookcovermatcher.CommentsSection.MEDIA,
        selectedSheet = "LIST",
        imageSearch = "cover",
        imageSort = ImageSort.CELL,
        imageFilter = ImageState.LINKED,
    )

    @Test fun openingOneVerifiedLocationKeepsEveryListState() {
        val opened = listState().openSource(
            "image1.png",
            SourceLocations.ofComment(comment("D128"), listOf(listSheet)),
        )

        assertEquals("image1.png", opened.source?.heading)
        assertEquals(1, opened.source?.candidates?.size)
        assertEquals(com.bookcovermatcher.CommentsSection.MEDIA, opened.section)
        assertEquals("LIST", opened.selectedSheet)
        assertEquals("cover", opened.imageSearch)
        assertEquals(ImageSort.CELL, opened.imageSort)
        assertEquals(ImageState.LINKED, opened.imageFilter)

        val closed = opened.closeSource()
        assertNull(closed.source)
        assertEquals(com.bookcovermatcher.CommentsSection.MEDIA, closed.section)
        assertEquals("LIST", closed.selectedSheet)
        assertEquals("cover", closed.imageSearch)
    }

    @Test fun anUnlocatableSourceExplainsItselfAndOffersNothing() {
        val unref = listState().openSource("loose.png", SourceLocation.Unreferenced)
        assertTrue(unref.source!!.candidates.isEmpty())
        assertTrue(unref.source!!.error!!.contains("No source location"))

        val unresolved = listState().openSource("broken.png", SourceLocation.Unresolved("LIST", "C5", "missing part"))
        assertTrue(unresolved.source!!.error!!.contains("missing part"))

        val stale = listState().openSource("old.png", SourceLocation.Unknown)
        assertTrue(stale.source!!.candidates.isEmpty())
        assertTrue(stale.source!!.error!!.contains("No source location"))
    }

    @Test fun aWindowFailureKeepsAnExplanationInsteadOfAnEmptyGrid() {
        val candidate = SourceLocation.Candidate("LIST", "xl/worksheets/sheet1.xml", "D128", 127, 3)
        val opened = listState().openSource("image1.png", SourceLocation.Verified(listOf(candidate)))

        val loading = opened.sourceLoading(candidate)
        assertTrue(loading.source!!.loading)
        assertNull(loading.source!!.window)

        val failed = loading.sourceWindow(candidate, null)
        assertFalse(failed.source!!.loading)
        assertNull(failed.source!!.window)
        assertTrue(failed.source!!.error!!.contains("could not be read"))

        val missing = opened.sourceFailed("The workbook is no longer available.")
        assertTrue(missing.source!!.error!!.contains("no longer available"))
    }

    @Test fun lateResultsBelongOnlyToTheWorkbookThatAsked() {
        val candidate = SourceLocation.Candidate("LIST", "xl/worksheets/sheet1.xml", "D128", 127, 3)
        val opened = listState().openSource("image1.png", SourceLocation.Verified(listOf(candidate)))

        assertTrue(opened.sourceBelongsTo("abc"))
        assertFalse(opened.sourceBelongsTo("another-workbook"))

        // A closed overlay is not reopened by a late result.
        val closed = opened.closeSource().sourceWindow(candidate, null)
        assertNull(closed.source)
    }    // ---- the worksheet window ------------------------------------------------------------------

    @Test fun theWindowShowsTheVerifiedRowAndTheCatalogueHeader() {
        val cells = listOf(CellValue(0, 0, "\u30BF\u30A4\u30C8\u30EB"), CellValue(0, 1, "\u30B5\u30FC\u30AF\u30EB")) +
            (2..12).flatMap { row -> listOf(CellValue(row, 0, "book$row"), CellValue(row, 1, "circle$row")) }

        val window = SheetWindows.build(
            sheetName = "LIST",
            sheetPath = "xl/worksheets/sheet1.xml",
            cells = cells,
            targetRow = 10,
            targetColumn = 1,
            headerRow = 0,
        )

        assertEquals("LIST", window.sheetName)
        assertEquals("B11", window.targetCell)
        assertTrue(window.rows.any { it.row == 10 && it.values == listOf("book10", "circle10") })
        // The header sits outside the six-row window, so it is carried separately.
        assertEquals(listOf("\u30BF\u30A4\u30C8\u30EB", "\u30B5\u30FC\u30AF\u30EB"), window.headerValues)
        assertEquals(0, window.headerRow)
        assertEquals(4, window.firstRow)
        assertEquals(12, window.lastRow)
        assertEquals("5\u201313", window.rowRangeLabel)
        assertEquals(listOf("A", "B"), window.columns.map { it.letter })
    }

    @Test fun theWindowAlwaysShowsABlankTargetRow() {
        val cells = listOf(CellValue(1, 0, "x"), CellValue(1, 1, "y"))
        val window = SheetWindows.build(
            sheetName = "LIST",
            sheetPath = "xl/worksheets/sheet1.xml",
            cells = cells,
            targetRow = 90,
            targetColumn = 2,
        )

        val target = window.rows.firstOrNull { it.row == 90 }
        assertNotNull("the verified row is always present", target)
        assertEquals(listOf(2), window.columns.map { it.column })
        assertEquals(listOf<String?>(null), target!!.values)
        assertEquals("C91", window.targetCell)
    }

    @Test fun aWideSheetKeepsTheVerifiedColumnVisible() {
        val cells = (0 until 40).map { col -> CellValue(5, col, "v$col") }
        val window = SheetWindows.build(
            sheetName = "LIST",
            sheetPath = "xl/worksheets/sheet1.xml",
            cells = cells,
            targetRow = 5,
            targetColumn = 30,
            maxColumns = 16,
        )

        assertEquals(16, window.columns.size)
        assertTrue("the verified column survives the cap", window.columns.any { it.column == 30 })
        assertEquals(window.columns.map { it.column }.sorted(), window.columns.map { it.column })
    }

    @Test fun theWindowKeepsTheWorkbookSpellingOfTheSheetName() {
        val window = SheetWindows.build(unicodeSheet, "xl/worksheets/sheet7.xml", emptyList(), 0, 0)

        assertEquals(unicodeSheet, window.sheetName)
        assertEquals("xl/worksheets/sheet7.xml", window.sheetPath)
        assertEquals("A1", window.targetCell)
    }
}