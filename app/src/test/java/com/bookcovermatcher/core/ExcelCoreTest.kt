package com.bookcovermatcher.core

import com.bookcovermatcher.core.excel.CellRange
import com.bookcovermatcher.core.excel.ImageMime
import com.bookcovermatcher.core.excel.ImageSource
import com.bookcovermatcher.core.excel.InvalidRangeException
import com.bookcovermatcher.core.excel.MapZipSource
import com.bookcovermatcher.core.excel.WorkbookImageExtractor
import com.bookcovermatcher.core.excel.ZipPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CellRangeTest {
    @Test fun parsesBasicRange() {
        val r = CellRange.parse("A1:B20")
        assertEquals(CellRange(r1 = 0, r2 = 19, c1 = 0, c2 = 1), r)
    }

    @Test fun acceptsCaseDollarsSpacesAndReversedCorners() {
        val expected = CellRange(0, 19, 0, 1)
        assertEquals(expected, CellRange.parse("a1:b20"))
        assertEquals(expected, CellRange.parse("\$A\$1:\$B\$20"))
        assertEquals(expected, CellRange.parse("  A1 : B20  "))
        assertEquals(expected, CellRange.parse("B20:A1"))
    }

    @Test fun multiLetterColumns() {
        assertEquals(0, CellRange.columnLettersToIndex("A"))
        assertEquals(25, CellRange.columnLettersToIndex("Z"))
        assertEquals(26, CellRange.columnLettersToIndex("AA"))
        assertEquals(51, CellRange.columnLettersToIndex("AZ"))
        assertEquals(52, CellRange.columnLettersToIndex("BA"))
        for (i in listOf(0, 1, 25, 26, 27, 51, 52, 701, 702, 16383)) {
            assertEquals(i, CellRange.columnLettersToIndex(CellRange.indexToColumnLetters(i)))
        }
        assertEquals("AA", CellRange.indexToColumnLetters(26))
    }

    @Test fun containsIsInclusive() {
        val r = CellRange.parse("B2:C3")
        assertTrue(r.contains(1, 1)); assertTrue(r.contains(2, 2))
        assertFalse(r.contains(0, 1)); assertFalse(r.contains(1, 3))
    }

    @Test fun rejectsMalformedInputWithTheV1Message() {
        for (bad in listOf("", "A1", "A1:B", "A1-B20", "1:2", "A1:B2:C3", "A1:B99999999999999999999")) {
            try {
                CellRange.parse(bad)
                fail("expected failure for '$bad'")
            } catch (e: InvalidRangeException) {
                assertEquals("Invalid range — use a format like A1:B20.", e.message)
            }
        }
    }
}

class ImageMimeTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun sniffsMagicBytes() {
        assertEquals("image/png", ImageMime.sniff(bytes(0x89, 0x50, 0x4E, 0x47, 0)))
        assertEquals("image/jpeg", ImageMime.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals("image/gif", ImageMime.sniff("GIF89a".toByteArray()))
        assertEquals("image/bmp", ImageMime.sniff("BM\u0000\u0000".toByteArray()))
        assertEquals("image/webp", ImageMime.sniff("RIFFxxxxWEBP".toByteArray()))
        assertNull(ImageMime.sniff(bytes(1, 2, 3)))
        assertNull(ImageMime.sniff(null))
    }

    @Test fun fallsBackToExtensionThenPng() {
        assertEquals("image/jpeg", ImageMime.fromExtension("JPG"))
        assertEquals("image/jpeg", ImageMime.fromExtension("jfif"))
        assertEquals("image/png", ImageMime.fromExtension("tiff"))
        assertEquals("image/gif", ImageMime.detect(bytes(1, 2, 3), "xl/media/a.gif"))
        assertEquals("image/png", ImageMime.detect(bytes(1, 2, 3), "xl/media/noext"))
    }

    @Test fun resolvesRelationshipTargets() {
        assertEquals("xl/media/image1.png", ZipPaths.resolve("xl/drawings/", "../media/image1.png"))
        assertEquals("xl/media/x.png", ZipPaths.resolve("xl/drawings/", "/xl/media/x.png"))
        assertEquals("xl/media/n.jpg", ZipPaths.resolve("xl/drawings/", "../media/./../media/n.jpg"))
        assertEquals("media/a.png", ZipPaths.resolve("xl/", "../../media/a.png"))
        assertEquals("", ZipPaths.resolve("xl/", null))
    }
}

/** Builds small in-memory OOXML packages and checks the v1 extraction rules. */
class WorkbookImageExtractorTest {

    private fun png(tag: String) = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + tag.toByteArray()
    private fun bad() = "BAD!".toByteArray()

    private fun vmlShape(row: Int, col: Int, fill: String) = """
 <v:shape id="s$row$col" type="#_x0000_t202" style='visibility:hidden'>
  $fill
  <x:ClientData ObjectType="Note"><x:Anchor>1,1,1,1,2,2,2,2</x:Anchor><x:Row>$row</x:Row><x:Column>$col</x:Column></x:ClientData>
 </v:shape>"""

    private fun vml(vararg shapes: String) = "<xml xmlns:v=\"urn:v\" xmlns:o=\"urn:o\" xmlns:x=\"urn:x\">" +
        "<v:shapetype id=\"t\"></v:shapetype>${shapes.joinToString("")}</xml>"

    private fun rels(vararg pairs: Pair<String, String>) =
        "<Relationships>" + pairs.joinToString("") { "<Relationship Id=\"${it.first}\" Type=\"image\" Target=\"${it.second}\"/>" } +
            "</Relationships>"

    private fun fillRel(id: String) = "<v:fill o:relid=\"$id\" type=\"frame\"/>"

    private fun decode(r: com.bookcovermatcher.core.excel.RawImage): String? =
        if (String(r.bytes, Charsets.ISO_8859_1).startsWith("BAD!")) null else String(r.bytes, Charsets.ISO_8859_1).drop(4)

    @Test fun readsCommentFillPicturesInsideTheRangeOnly() {
        val pkg = linkedMapOf(
            "xl/drawings/vmlDrawing1.vml" to vml(
                vmlShape(0, 1, fillRel("rId1")),       // B1 inside
                vmlShape(30, 0, fillRel("rId1")),      // A31 outside A1:B20
                vmlShape(2, 5, fillRel("rId1")),       // F3 outside
                vmlShape(3, 0, "<v:fill color2=\"#fff\"/>"),   // plain comment, no picture
            ).toByteArray(),
            "xl/drawings/_rels/vmlDrawing1.vml.rels" to rels("rId1" to "../media/a.png").toByteArray(),
            "xl/media/a.png" to png("A"),
        )
        val out = WorkbookImageExtractor.extractAll(MapZipSource(LinkedHashMap(pkg)), CellRange.parse("A1:B20"), false, ::decode)
        assertEquals(listOf("B1"), out.map { it.cell })
        assertEquals(ImageSource.COMMENT_FILL, out[0].source)
        assertEquals("A", out[0].payload)
    }

    @Test fun supportsAllFourRelationshipAttributePatterns() {
        val shapes = arrayOf(
            vmlShape(0, 0, "<v:fill o:relid=\"rId1\"/>"),
            vmlShape(1, 0, "<v:imagedata r:id=\"rId1\"/>"),
            vmlShape(2, 0, "<v:fill r:id=\"rId1\"/>"),
            vmlShape(3, 0, "<v:imagedata o:relid=\"rId1\"/>"),
        )
        val pkg = linkedMapOf(
            "xl/drawings/vmlDrawing1.vml" to vml(*shapes).toByteArray(),
            "xl/drawings/_rels/vmlDrawing1.vml.rels" to rels("rId1" to "../media/a.png").toByteArray(),
            "xl/media/a.png" to png("A"),
        )
        val out = WorkbookImageExtractor.extractAll(MapZipSource(LinkedHashMap(pkg)), CellRange.parse("A1:A10"), false, ::decode)
        assertEquals(listOf("A1", "A2", "A3", "A4"), out.map { it.cell })
    }

    @Test fun firstPartInZipOrderWinsDuplicateCellsAndUndecodablePicturesDoNotClaimACell() {
        val pkg = linkedMapOf(
            // zip order: vmlDrawing10 BEFORE vmlDrawing2 (not numeric order)
            "xl/drawings/vmlDrawing10.vml" to vml(
                vmlShape(0, 0, fillRel("rId1")),   // A1 -> undecodable, must not block later parts
                vmlShape(1, 0, fillRel("rId2")),   // A2 -> "TEN"
            ).toByteArray(),
            "xl/drawings/_rels/vmlDrawing10.vml.rels" to rels("rId1" to "../media/bad.png", "rId2" to "../media/ten.png").toByteArray(),
            "xl/drawings/vmlDrawing2.vml" to vml(
                vmlShape(0, 0, fillRel("rId1")),   // A1 -> "TWO" (wins because the first attempt failed)
                vmlShape(1, 0, fillRel("rId1")),   // A2 -> loses to part 10
            ).toByteArray(),
            "xl/drawings/_rels/vmlDrawing2.vml.rels" to rels("rId1" to "../media/two.png").toByteArray(),
            "xl/media/bad.png" to bad(), "xl/media/ten.png" to png("TEN"), "xl/media/two.png" to png("TWO"),
        )
        val out = WorkbookImageExtractor.extractAll(MapZipSource(LinkedHashMap(pkg)), CellRange.parse("A1:A5"), false, ::decode)
        assertEquals(listOf("A2" to "TEN", "A1" to "TWO"), out.map { it.cell to it.payload })
    }

    @Test fun ignoresPartsWithoutRelsMissingMediaAndUnknownRelIds() {
        val pkg = linkedMapOf(
            "xl/drawings/vmlDrawing1.vml" to vml(vmlShape(0, 0, fillRel("rId1"))).toByteArray(), // no .rels file
            "xl/drawings/vmlDrawing2.vml" to vml(
                vmlShape(0, 0, fillRel("rId1")),   // media missing
                vmlShape(1, 0, fillRel("rId9")),   // rId not in rels
            ).toByteArray(),
            "xl/drawings/_rels/vmlDrawing2.vml.rels" to rels("rId1" to "../media/missing.png").toByteArray(),
            "xl/drawings/vmlDrawingX.vml" to vml(vmlShape(2, 0, fillRel("rId1"))).toByteArray(),
        )
        val out = WorkbookImageExtractor.extractAll(MapZipSource(LinkedHashMap(pkg)), CellRange.parse("A1:B10"), false, ::decode)
        assertTrue(out.isEmpty())
    }

    // ---- floating pictures ----

    private fun workbookParts(drawingXml: String, drawingRels: String): LinkedHashMap<String, ByteArray> = linkedMapOf(
        "xl/workbook.xml" to "<workbook><sheets><sheet name=\"S1\" sheetId=\"1\" r:id=\"rId1\"/><sheet name=\"S2\" sheetId=\"2\" r:id=\"rId2\"/></sheets></workbook>".toByteArray(),
        "xl/_rels/workbook.xml.rels" to ("<Relationships>" +
            "<Relationship Id=\"rId1\" Type=\"http://x/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://x/relationships/worksheet\" Target=\"worksheets/sheet2.xml\"/></Relationships>").toByteArray(),
        "xl/worksheets/sheet1.xml" to "<worksheet><drawing r:id=\"rId1\"/></worksheet>".toByteArray(),
        "xl/worksheets/_rels/sheet1.xml.rels" to ("<Relationships>" +
            "<Relationship Id=\"rId1\" Type=\"http://x/relationships/drawing\" Target=\"../drawings/drawing1.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://x/relationships/vmlDrawing\" Target=\"../drawings/vmlDrawing1.vml\"/></Relationships>").toByteArray(),
        "xl/worksheets/sheet2.xml" to "<worksheet><drawing r:id=\"rId1\"/></worksheet>".toByteArray(),
        "xl/worksheets/_rels/sheet2.xml.rels" to ("<Relationships>" +
            "<Relationship Id=\"rId1\" Type=\"http://x/relationships/drawing\" Target=\"../drawings/drawing2.xml\"/></Relationships>").toByteArray(),
        "xl/drawings/drawing1.xml" to drawingXml.toByteArray(),
        "xl/drawings/_rels/drawing1.xml.rels" to drawingRels.toByteArray(),
        "xl/drawings/drawing2.xml" to anchors(one(0, 0, "rId1")).toByteArray(),   // second sheet: must be ignored
        "xl/drawings/_rels/drawing2.xml.rels" to rels("rId1" to "../media/second.png").toByteArray(),
        "xl/media/one.png" to png("ONE"), "xl/media/two.png" to png("TWO"), "xl/media/bad.png" to bad(),
        "xl/media/second.png" to png("SECOND"),
    )

    private fun anchors(vararg a: String) = "<xdr:wsDr xmlns:xdr=\"x\" xmlns:a=\"a\" xmlns:r=\"r\">${a.joinToString("")}</xdr:wsDr>"
    private fun one(row: Int, col: Int, rid: String) =
        "<xdr:oneCellAnchor><xdr:from><xdr:col>$col</xdr:col><xdr:colOff>5</xdr:colOff><xdr:row>$row</xdr:row><xdr:rowOff>9</xdr:rowOff></xdr:from>" +
            "<xdr:ext cx=\"1\" cy=\"1\"/><xdr:pic><xdr:blipFill><a:blip r:embed=\"$rid\"/></xdr:blipFill></xdr:pic><xdr:clientData/></xdr:oneCellAnchor>"
    private fun two(row: Int, col: Int, rid: String) =
        "<xdr:twoCellAnchor editAs=\"oneCell\"><xdr:from><xdr:col>$col</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>$row</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>" +
            "<xdr:to><xdr:col>${col + 5}</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>${row + 5}</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to>" +
            "<xdr:pic><xdr:nvPicPr><xdr:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></xdr:cNvPicPr></xdr:nvPicPr><xdr:blipFill><a:blip r:embed=\"$rid\" cstate=\"print\"/></xdr:blipFill></xdr:pic><xdr:clientData/></xdr:twoCellAnchor>"
    private val shapeOnly = "<xdr:twoCellAnchor><xdr:from><xdr:col>0</xdr:col><xdr:row>4</xdr:row></xdr:from><xdr:sp/></xdr:twoCellAnchor>"
    private val absolute = "<xdr:absoluteAnchor><xdr:pos x=\"0\" y=\"0\"/><xdr:pic><a:blip r:embed=\"rId1\"/></xdr:pic></xdr:absoluteAnchor>"

    @Test fun readsAnchoredPicturesOfTheFirstSheetOnly() {
        val parts = workbookParts(
            anchors(one(0, 0, "rId1"), two(1, 1, "rId2"), one(2, 0, "rId3"), one(30, 0, "rId1"), one(0, 9, "rId1"), shapeOnly, absolute),
            rels("rId1" to "../media/one.png", "rId2" to "../media/two.png", "rId3" to "../media/bad.png"),
        )
        val zip = MapZipSource(parts)
        WorkbookImageExtractor.requireWorkbook(zip)
        val out = WorkbookImageExtractor.extractAll(zip, CellRange.parse("A1:B20"), true, ::decode)
        assertEquals(listOf("A1" to "ONE", "B2" to "TWO"), out.map { it.cell to it.payload })
        assertTrue(out.all { it.source == ImageSource.FLOATING })
    }

    @Test fun floatingResultsComeFirstAndWinDuplicateCellsAgainstCommentFills() {
        val parts = workbookParts(anchors(one(1, 1, "rId1")), rels("rId1" to "../media/one.png"))
        parts["xl/drawings/vmlDrawing1.vml"] = vml(vmlShape(1, 1, fillRel("rId1")), vmlShape(0, 1, fillRel("rId1"))).toByteArray()
        parts["xl/drawings/_rels/vmlDrawing1.vml.rels"] = rels("rId1" to "../media/two.png").toByteArray()
        val zip = MapZipSource(parts)
        val out = WorkbookImageExtractor.extractAll(zip, CellRange.parse("A1:B5"), true, ::decode)
        assertEquals(listOf("B2" to "ONE", "B1" to "TWO"), out.map { it.cell to it.payload })
        assertEquals(listOf(ImageSource.FLOATING, ImageSource.COMMENT_FILL), out.map { it.source })

        val notesOnly = WorkbookImageExtractor.extractAll(zip, CellRange.parse("A1:B5"), false, ::decode)
        assertEquals(listOf("B2" to "TWO", "B1" to "TWO"), notesOnly.map { it.cell to it.payload })
    }

    @Test fun floatingCapCountsOnlyDecodedPictures() {
        val parts = workbookParts(
            anchors(one(0, 0, "rId3"), one(1, 0, "rId1"), one(2, 0, "rId1"), one(3, 0, "rId1"), one(4, 0, "rId1")),
            rels("rId1" to "../media/one.png", "rId3" to "../media/bad.png"),
        )
        val zip = MapZipSource(parts)
        val plan = WorkbookImageExtractor.plan(zip, CellRange.parse("A1:A10"), true)
        val out = WorkbookImageExtractor.run(zip, plan, maxFloating = 2, decode = ::decode)
        assertEquals(listOf("A2", "A3"), out.map { it.cell })
    }

    @Test fun reportsProgressForEveryCandidate() {
        val parts = workbookParts(anchors(one(0, 0, "rId1"), one(1, 0, "rId1")), rels("rId1" to "../media/one.png"))
        val zip = MapZipSource(parts)
        val plan = WorkbookImageExtractor.plan(zip, CellRange.parse("A1:A10"), true)
        val steps = ArrayList<Pair<Int, Int>>()
        WorkbookImageExtractor.run(zip, plan, onStep = { d, t -> steps.add(d to t) }, decode = ::decode)
        assertEquals(listOf(1 to 2, 2 to 2), steps)
    }

    @Test fun rejectsFilesThatAreNotWorkbooks() {
        try {
            WorkbookImageExtractor.requireWorkbook(MapZipSource(linkedMapOf("hello.txt" to "x".toByteArray())))
            fail("expected an exception")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("workbook"))
        }
    }
}
