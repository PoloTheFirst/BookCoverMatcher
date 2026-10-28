package com.bookcovermatcher.core

import com.bookcovermatcher.core.excel.MapZipSource
import com.bookcovermatcher.core.excel.comments.CommentImageScanner
import com.bookcovermatcher.core.excel.comments.CommentScanResult
import com.bookcovermatcher.core.excel.comments.ImageState
import com.bookcovermatcher.core.excel.comments.WorkbookAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** End to end: workbook parts → scan → catalogue table → image inventory, with real ZIP parts. */
class WorkbookAnalysisTest {

    private fun sheetXml(vararg rows: String): String =
        "<worksheet><sheetData>${rows.joinToString("")}</sheetData></worksheet>"

    private fun row(index: Int, vararg cells: Pair<String, String>): String = buildString {
        append("<row r=\"$index\">")
        cells.forEach { (ref, text) -> append("<c r=\"$ref\" t=\"inlineStr\"><is><t>$text</t></is></c>") }
        append("</row>")
    }

    private fun buildPackage(): MapZipSource {
        val entries = LinkedHashMap<String, ByteArray>()
        entries["xl/workbook.xml"] = (
            "<workbook xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>" +
                "<sheet name=\"LIST\" sheetId=\"1\" r:id=\"rId1\"/>" +
                "<sheet name=\"PICS\" sheetId=\"2\" state=\"hidden\" r:id=\"rId2\"/>" +
                "</sheets></workbook>"
            ).toByteArray()
        entries["xl/_rels/workbook.xml.rels"] = TestWorkbooks.workbookRels(2).toByteArray()
        entries["xl/worksheets/sheet1.xml"] = sheetXml(
            row(1, "A1" to "タイトル", "B1" to "作者", "C1" to "サークル名"),
            row(2, "A2" to "東方紅魔郷", "B2" to "ZUN", "C2" to "上海アリス幻樂団"),
            row(3, "A3" to "Blue Archive", "B3" to "Yostar", "C3" to "N/A"),
        ).toByteArray()
        entries["xl/worksheets/sheet2.xml"] = sheetXml(row(1, "A1" to "cover")).toByteArray()
        entries["xl/worksheets/_rels/sheet1.xml.rels"] =
            "<Relationships/>".toByteArray()
        entries["xl/worksheets/_rels/sheet2.xml.rels"] =
            TestWorkbooks.sheetRels("../comments1.xml", "../drawings/vmlDrawing1.vml").toByteArray()
        entries["xl/comments1.xml"] = TestWorkbooks.comments(
            listOf("A"),
            TestWorkbooks.comment("A1", 0, "<t>row two cover</t>"),
        ).toByteArray()
        entries["xl/drawings/vmlDrawing1.vml"] =
            TestWorkbooks.vml(TestWorkbooks.noteShape(0, 0, "<v:fill src=\"../media/image1.png\"/>")).toByteArray()
        entries["xl/media/image1.png"] = TestWorkbooks.PNG
        entries["xl/media/logo.png"] = TestWorkbooks.PNG
        return MapZipSource(entries)
    }

    @Test fun theCatalogueAndTheImagesAreJoinedOnTheSameWorksheetRow() {
        val zip = buildPackage()
        val model = (CommentImageScanner.scan(zip, "同人誌.xlsm", "sha") as CommentScanResult.Success).workbook

        val analysis = WorkbookAnalyzer.analyze(zip, model)

        assertEquals(listOf("LIST", "PICS"), analysis.sheets.map { it.name })
        assertTrue("the hidden state must be preserved", analysis.sheets[1].hidden)
        assertEquals("Hidden", analysis.sheets[1].label)
        assertEquals(1, analysis.tables.size)
        assertEquals("LIST", analysis.tables.single().sheetName)
        assertEquals(2, analysis.tables.single().records.size)

        // The comment lives on PICS!A1, which is not a catalogue sheet, so no record is attached.
        val linked = analysis.images.single { it.state == ImageState.LINKED }
        assertEquals("xl/media/image1.png", linked.mediaPath)
        assertTrue("no catalogue record can be attached across sheets", linked.catalogue.isEmpty())
        assertEquals(ImageState.UNREFERENCED, analysis.images.single { it.fileName == "logo.png" }.state)

        assertEquals(2, analysis.coverage.recordsTotal)
        assertEquals(0, analysis.coverage.recordsWithImage)
        assertEquals(1, analysis.coverage.unreferencedMedia)
    }

    @Test fun aDeclaredUsedRangeIsReadWithoutTouchingTheCells() {
        val entries = LinkedHashMap<String, ByteArray>()
        entries["xl/workbook.xml"] =
            "<workbook><sheets><sheet name=\"SHEET1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>".toByteArray()
        entries["xl/_rels/workbook.xml.rels"] = TestWorkbooks.workbookRels(1).toByteArray()
        entries["xl/worksheets/sheet1.xml"] =
            ("<worksheet><dimension ref=\"A1:P120\"/><sheetData>" +
                row(1, "A1" to "x") + "</sheetData></worksheet>").toByteArray()
        val zip = MapZipSource(entries)
        val model = (CommentImageScanner.scan(zip, "book.xlsx", "sha") as CommentScanResult.Success).workbook

        val analysis = WorkbookAnalyzer.analyze(zip, model)

        assertEquals("A1:P120", analysis.sheets.single().dimension)
    }

    @Test fun aWorksheetWithoutADeclaredDimensionReportsNullRatherThanAGuess() {
        val zip = TestWorkbooks.xlsx(sheetName = "BARE")
        val model = (CommentImageScanner.scan(zip, "bare.xlsx", "sha2") as CommentScanResult.Success).workbook

        val analysis = WorkbookAnalyzer.analyze(zip, model)

        assertNull("a missing <dimension> element is unknown, not A1", analysis.sheets.single().dimension)
    }

    @Test fun aPivotTableSheetIsNotOfferedAsACatalogue() {
        val entries = LinkedHashMap<String, ByteArray>()
        entries["xl/workbook.xml"] = (
            "<workbook><sheets>" +
                "<sheet name=\"CAT\" sheetId=\"1\" r:id=\"rId1\"/>" +
                "<sheet name=\"PIVOT\" sheetId=\"2\" r:id=\"rId2\"/>" +
                "</sheets></workbook>"
            ).toByteArray()
        entries["xl/_rels/workbook.xml.rels"] = TestWorkbooks.workbookRels(2).toByteArray()
        entries["xl/worksheets/sheet1.xml"] = sheetXml(
            row(1, "A1" to "タイトル", "B1" to "作者", "C1" to "サークル名"),
            row(2, "A2" to "本", "B2" to "人", "C2" to "サークル"),
        ).toByteArray()
        // A pivot sheet whose "header" would otherwise pass the structural detection.
        entries["xl/worksheets/sheet2.xml"] = sheetXml(
            row(1, "A1" to "Row Labels", "B1" to "Count", "C1" to "Grand Total"),
            row(2, "A2" to "SF", "B2" to "3", "C2" to "12"),
        ).toByteArray()
        entries["xl/worksheets/_rels/sheet2.xml.rels"] = (
            "<Relationships><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/" +
                "officeDocument/2006/relationships/pivotTable\" Target=\"../pivotTables/pivotTable1.xml\"/>" +
                "</Relationships>"
            ).toByteArray()
        val zip = MapZipSource(entries)
        val model = (CommentImageScanner.scan(zip, "pivot.xlsx", "sha-pivot") as CommentScanResult.Success).workbook

        val analysis = WorkbookAnalyzer.analyze(zip, model)

        assertEquals(listOf("CAT"), analysis.tables.map { it.sheetName })
    }
}
