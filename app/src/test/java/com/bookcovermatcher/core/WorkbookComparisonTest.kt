package com.bookcovermatcher.core

import com.bookcovermatcher.core.excel.comments.SheetMetrics
import com.bookcovermatcher.core.excel.comments.SheetPresence
import com.bookcovermatcher.core.excel.comments.WorkbookComparison
import com.bookcovermatcher.core.excel.comments.WorkbookMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The metadata-level comparison: identity by sheet name, order, visibility, counts and unknowns. */
class WorkbookComparisonTest {

    private fun sheet(
        name: String,
        visibility: String = "visible",
        dimension: String? = "A1:C10",
        comments: Int = 0,
        linked: Int = 0,
        unresolved: Int = 0,
    ) = SheetMetrics(name, visibility, dimension, comments, linked, unresolved)

    private fun side(
        sha: String = "sha-left",
        sheets: List<SheetMetrics> = listOf(sheet("LIST"), sheet("PICS")),
        media: Int = 2,
        linked: Int = 1,
        unref: Int = 1,
        unresolved: Int = 0,
        records: Int? = 10,
        headers: List<String> = listOf("タイトル", "作者", "サークル名"),
    ) = WorkbookMetrics(
        label = "workbook $sha",
        originalName = "$sha.xlsm",
        sha256 = sha,
        sheets = sheets,
        mediaTotal = media,
        mediaLinked = linked,
        mediaUnreferenced = unref,
        unresolvedAssociations = unresolved,
        catalogueRecords = records,
        catalogueHeaders = headers,
    )

    @Test fun identicalSidesReportNoDifferences() {
        val diff = WorkbookComparison.compare(side(), side(sha = "sha-right"))

        assertTrue(diff.totals.isEmpty())
        assertEquals(2, diff.identicalSheets.size)
        assertTrue(diff.additions.isEmpty())
        assertTrue(diff.removals.isEmpty())
        assertFalse(diff.selfComparison)
        assertFalse(diff.orderChanged)
    }

    @Test fun theSameContentOnBothSidesIsFlaggedAsSelfComparison() {
        assertTrue(WorkbookComparison.compare(side(sha = "same"), side(sha = "same")).selfComparison)
    }

    @Test fun addedAndRemovedWorksheetsAreReportedByName() {
        val left = side(sheets = listOf(sheet("LIST"), sheet("OG")))
        val right = side(sha = "right", sheets = listOf(sheet("LIST"), sheet("NEW")))

        val diff = WorkbookComparison.compare(left, right)

        assertEquals(listOf("OG"), diff.removals.map { it.name })
        assertEquals(listOf("NEW"), diff.additions.map { it.name })
        assertEquals(SheetPresence.LEFT_ONLY, diff.removals.single().presence)
        assertEquals(SheetPresence.RIGHT_ONLY, diff.additions.single().presence)
        assertTrue("the shared sheet did not change", diff.changed.isEmpty())
    }

    @Test fun aRenamedWorksheetIsNotMatchedToTheOldOne() {
        // "LIST" -> "CATALOGUE" must be a removal plus an addition, never a silent match.
        val diff = WorkbookComparison.compare(side(sheets = listOf(sheet("LIST"))), side(sha = "right", sheets = listOf(sheet("CATALOGUE"))))

        assertEquals(listOf("LIST"), diff.removals.map { it.name })
        assertEquals(listOf("CATALOGUE"), diff.additions.map { it.name })
    }

    @Test fun worksheetOrderChangesAreReportedSeparately() {
        val left = side(sheets = listOf(sheet("A"), sheet("B")))
        val right = side(sha = "right", sheets = listOf(sheet("B"), sheet("A")))

        val diff = WorkbookComparison.compare(left, right)

        assertTrue("same names, different order", diff.orderChanged)
        assertTrue(diff.additions.isEmpty() && diff.removals.isEmpty())
        assertEquals(2, diff.identicalSheets.size)
    }

    @Test fun visibilityDifferencesAreReported() {
        val left = side(sheets = listOf(sheet("LIST"), sheet("SECRET", "visible")))
        val right = side(sha = "right", sheets = listOf(sheet("LIST"), sheet("SECRET", "veryhidden")))

        val change = WorkbookComparison.compare(left, right).changed.single()

        assertEquals("SECRET", change.name)
        assertEquals("Visibility", change.changes.single().field)
        assertEquals("visible", change.changes.single().left)
        assertEquals("veryhidden", change.changes.single().right)
    }

    @Test fun countDifferencesAppearPerSheetAndInTheTotals() {
        val left = side(sheets = listOf(sheet("LIST", comments = 3, linked = 2)))
        val right = side(
            sha = "right",
            sheets = listOf(sheet("LIST", comments = 5, linked = 2, unresolved = 1)),
            linked = 2,
            unresolved = 1,
        )

        val diff = WorkbookComparison.compare(left, right)

        assertEquals(listOf("Comments", "Unresolved references"), diff.changed.single().changes.map { it.field })
        assertTrue(diff.totals.any { it.field == "Legacy comments" && it.left == "3" && it.right == "5" })
        assertTrue(diff.totals.any { it.field == "Unresolved references" && it.right == "1" })
    }

    @Test fun differentCatalogueHeadersAndRecordCountsAreReported() {
        val left = side(records = 10)
        val right = side(sha = "right", records = 12, headers = listOf("Title", "Author", "Circle"))

        val diff = WorkbookComparison.compare(left, right)

        assertTrue(diff.headersChanged)
        assertTrue(diff.totals.any { it.field == "Catalogue records" && it.left == "10" && it.right == "12" })
    }

    @Test fun aWorkbookWithoutACatalogueTableReportsUnknownRecords() {
        val left = side(records = 10)
        val right = side(sha = "right", records = null, headers = emptyList())

        val diff = WorkbookComparison.compare(left, right)
        val change = diff.totals.first { it.field == "Catalogue records" }

        assertEquals("10", change.left)
        assertNull("no catalogue table means unknown, not zero", change.right)
        assertTrue(change.unknown)
    }

    @Test fun anUnreadableSideKeepsUnknownsInsteadOfZeros() {
        val right = WorkbookComparison.failure("broken", "broken.xlsx", "sha-broken", "not a zip package")

        val diff = WorkbookComparison.compare(side(), right)

        assertFalse(right.usable)
        assertNull("a failed side has no sheet count", right.sheetCount)
        val worksheets = diff.totals.first { it.field == "Worksheets" }
        assertEquals("2", worksheets.left)
        assertNull("the failed side is unknown, not zero", worksheets.right)
        assertTrue(worksheets.unknown)
        assertFalse("a failed side is never labelled a self-comparison", diff.selfComparison)
    }

    @Test fun aWorksheetOnlyOnOneSideCarriesItsOwnCounts() {
        val left = side(sheets = listOf(sheet("LIST")))
        val right = side(
            sha = "right",
            sheets = listOf(sheet("LIST"), SheetMetrics("EXTRA", "hidden", null, 4, 1, 0)),
        )

        val addition = WorkbookComparison.compare(left, right).additions.single()

        assertEquals("EXTRA", addition.name)
        assertEquals(4, addition.right?.comments)
        assertEquals(1, addition.right?.linkedImages)
        assertNull("a sheet without a declared range stays null", addition.right?.dimension)
    }
}
