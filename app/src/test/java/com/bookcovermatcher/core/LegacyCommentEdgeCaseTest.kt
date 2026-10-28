package com.bookcovermatcher.core

import com.bookcovermatcher.core.excel.MapZipSource
import com.bookcovermatcher.core.excel.comments.CommentImageScanner
import com.bookcovermatcher.core.excel.comments.CommentImageState
import com.bookcovermatcher.core.excel.comments.CommentScanResult
import com.bookcovermatcher.core.excel.comments.DiagnosticLevel
import com.bookcovermatcher.core.excel.comments.MediaBytes
import com.bookcovermatcher.core.excel.comments.MediaResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun model(zip: MapZipSource) =
    (CommentImageScanner.scan(zip, "edge.xlsx", "sha-edge") as CommentScanResult.Success).workbook

/** Recoverable failures, package variation and the workbook-wide media list. */
class LegacyCommentEdgeCaseTest {

    @Test fun commentsAcrossSheetsUseTheirOwnRelationshipTargets() {
        val entries = LinkedHashMap<String, ByteArray>()
        entries["xl/workbook.xml"] = TestWorkbooks.workbook("One" to 1, "Two" to 2).toByteArray()
        entries["xl/_rels/workbook.xml.rels"] = TestWorkbooks.workbookRels(2).toByteArray()
        entries["xl/worksheets/sheet1.xml"] = "<worksheet/>".toByteArray()
        entries["xl/worksheets/sheet2.xml"] = "<worksheet/>".toByteArray()
        entries["xl/worksheets/_rels/sheet1.xml.rels"] =
            TestWorkbooks.sheetRels("../comments1.xml", "../drawings/vmlDrawing1.vml").toByteArray()
        entries["xl/worksheets/_rels/sheet2.xml.rels"] =
            TestWorkbooks.sheetRels("../comments2.xml", "../drawings/vmlDrawing2.vml").toByteArray()
        entries["xl/comments1.xml"] =
            TestWorkbooks.comments(listOf("A"), TestWorkbooks.comment("A1", 0, "<t>first</t>")).toByteArray()
        entries["xl/comments2.xml"] =
            TestWorkbooks.comments(listOf("B"), TestWorkbooks.comment("B2", 0, "<t>second</t>")).toByteArray()
        entries["xl/drawings/vmlDrawing1.vml"] =
            TestWorkbooks.vml(TestWorkbooks.noteShape(0, 0, "<v:fill src=\"../media/image1.png\"/>")).toByteArray()
        entries["xl/drawings/vmlDrawing2.vml"] =
            TestWorkbooks.vml(TestWorkbooks.noteShape(1, 1, "<v:fill src=\"../media/image2.png\"/>")).toByteArray()
        entries["xl/media/image1.png"] = TestWorkbooks.PNG
        entries["xl/media/image2.png"] = TestWorkbooks.PNG

        val result = model(MapZipSource(entries))
        assertEquals(listOf("One", "Two"), result.sheets)
        assertEquals(2, result.comments.size)
        assertEquals(listOf("One", "Two"), result.comments.map { it.sheetName })
        assertEquals(listOf("xl/media/image1.png", "xl/media/image2.png"), result.comments.map { it.mediaPaths.single() })
        assertEquals(2, result.imageCommentCount)
    }

    @Test fun missingCommentsPartLeavesShapesAsDiagnostics() {
        val zip = TestWorkbooks.xlsx(
            commentsXml = null,
            vmlXml = TestWorkbooks.vml(TestWorkbooks.noteShape(4, 2, "<v:fill src=\"../media/image1.png\"/>")),
        )

        val result = model(zip)
        assertTrue(result.comments.isEmpty())
        assertTrue(result.diagnostics.any { it.message.contains("no comments part") })
    }

    @Test fun missingVmlStillShowsTheText() {
        val zip = TestWorkbooks.xlsx(commentsXml = TestWorkbooks.comments(listOf("A"), TestWorkbooks.comment("C5", 0, "<t>text</t>")))

        val result = model(zip)
        val comment = result.comments.single()
        assertEquals(CommentImageState.TEXT_ONLY, comment.state)
        assertEquals("text", comment.text)
        assertTrue(result.diagnostics.any { it.message.contains("no VML drawing") })
    }

    @Test fun missingRelationshipLeavesTheReferenceUnresolved() {
        val zip = TestWorkbooks.xlsx(
            commentsXml = TestWorkbooks.comments(listOf("A"), TestWorkbooks.comment("C5", 0, "<t>lost</t>")),
            vmlXml = TestWorkbooks.vml(TestWorkbooks.noteShape(4, 2, "<v:fill o:relid=\"rId9\"/>")),
            vmlRelsXml = TestWorkbooks.vmlRels("rId1" to "../media/image1.png"),
        )

        val comment = model(zip).comments.single()
        assertEquals(CommentImageState.UNRESOLVED_IMAGE, comment.state)
        assertEquals(MediaResolution.UNRESOLVED, comment.media.single().resolution)
        assertNotNull(comment.media.single().detail)
        assertTrue(comment.media.single().detail!!.contains("rId9"))
    }

    @Test fun missingMediaPartIsReportedAsUnresolved() {
        val zip = TestWorkbooks.xlsx(
            commentsXml = TestWorkbooks.comments(listOf("A"), TestWorkbooks.comment("C5", 0, "<t>gone</t>")),
            vmlXml = TestWorkbooks.vml(TestWorkbooks.noteShape(4, 2, "<v:fill src=\"../media/missing.png\"/>")),
            media = mapOf("xl/media/image1.png" to TestWorkbooks.PNG),
        )

        val comment = model(zip).comments.single()
        assertEquals(CommentImageState.UNRESOLVED_IMAGE, comment.state)
        assertTrue(comment.media.single().detail!!.contains("no part"))
    }

    @Test fun mediaWithoutAnyCommentIsListedButUnassociated() {
        val zip = TestWorkbooks.xlsx(
            commentsXml = TestWorkbooks.comments(listOf("A"), TestWorkbooks.comment("C5", 0, "<t>plain</t>")),
            media = mapOf(
                "xl/media/image1.png" to TestWorkbooks.PNG,
                "xl/media/logo.png" to TestWorkbooks.PNG,
            ),
        )

        val result = model(zip)
        assertEquals(listOf("xl/media/image1.png", "xl/media/logo.png"), result.media.map { it.path })
        assertTrue(result.media.none { it.isReferenced })
        assertEquals(0, result.referencedMediaCount)
        assertEquals("png", result.media.first().format)
    }

    @Test fun twoCommentsSharingOneImageKeepBothAssociations() {
        val zip = TestWorkbooks.xlsx(
            commentsXml = TestWorkbooks.comments(
                listOf("A"),
                TestWorkbooks.comment("C5", 0, "<t>first</t>"),
                TestWorkbooks.comment("D6", 0, "<t>second</t>"),
            ),
            vmlXml = TestWorkbooks.vml(
                TestWorkbooks.noteShape(4, 2, "<v:fill src=\"../media/image1.png\"/>", id="_x0000_s1"),
                TestWorkbooks.noteShape(5, 3, "<v:fill src=\"../media/image1.png\"/>", id="_x0000_s2"),
            ),
        )

        val media = model(zip).media.single()
        assertEquals("xl/media/image1.png", media.path)
        assertTrue(media.isReferenced)
        assertEquals(listOf("C5", "D6"), media.associations.map { it.cell })
    }

    @Test fun threadedCommentsAreReportedAndNeverTreatedAsNotes() {
        val zip = TestWorkbooks.xlsx(
            extra = mapOf(
                "xl/threadedComments/threadedComment1.xml" to
                    "<ThreadedComments><threadedComment ref=\"A1\"><text>x</text></threadedComment></ThreadedComments>"
                        .toByteArray(),
            ),
        )

        val result = model(zip)
        assertTrue(result.comments.isEmpty())
        assertEquals(listOf("xl/threadedComments/threadedComment1.xml"), result.threadedCommentParts)
        assertTrue(result.diagnostics.any { it.message.contains("threaded", ignoreCase = true) })
    }

    @Test fun xlsmMacroProjectIsIgnoredCompletely() {
        val zip = TestWorkbooks.xlsx(
            commentsXml = TestWorkbooks.comments(listOf("A"), TestWorkbooks.comment("C5", 0, "<t>macro book</t>")),
            extra = mapOf("xl/vbaProject.bin" to byteArrayOf(0x01, 0x02, 0x03, 0x04)),
        )

        val result = model(zip)
        assertEquals(1, result.comments.size)
        // The macro part is neither parsed nor mentioned as an image; it is simply not part of the scan.
        assertTrue(result.media.none { it.path.contains("vbaProject") })
        assertTrue(result.diagnostics.none { it.message.contains("vba", ignoreCase = true) })
    }

    @Test fun elementPrefixesMayDifferFromVxAndO() {
        val vml = """
            <xml xmlns:ns1="urn:schemas-microsoft-com:vml"
                 xmlns:ns2="urn:schemas-microsoft-com:office:excel"
                 xmlns:ns3="urn:schemas-microsoft-com:office:office">
              <ns1:shape id="_x0000_s1">
                <ns1:fill ns3:relid="rId1"/>
                <ns2:ClientData ObjectType="Note"><ns2:Row>4</ns2:Row><ns2:Column>2</ns2:Column></ns2:ClientData>
              </ns1:shape>
            </xml>
        """.trimIndent()
        val zip = TestWorkbooks.xlsx(
            commentsXml = TestWorkbooks.comments(listOf("A"), TestWorkbooks.comment("C5", 0, "<t>prefixed</t>")),
            vmlXml = vml,
            vmlRelsXml = TestWorkbooks.vmlRels("rId1" to "../media/image1.png"),
        )

        assertEquals(listOf("xl/media/image1.png"), model(zip).comments.single().mediaPaths)
    }

    @Test fun malformedCommentsXmlIsRecoverable() {
        val zip = TestWorkbooks.xlsx(
            commentsXml = "<comments><authors><author>A</author>",
        )

        val result = model(zip)
        assertTrue(result.comments.isEmpty())
        assertTrue(result.diagnostics.any { it.message.contains("well-formed") })
    }

    @Test fun aPackageWithoutWorksheetsFailsGracefully() {
        val zip = MapZipSource(linkedMapOf("docProps/app.xml" to "<Properties/>".toByteArray()))

        val result = CommentImageScanner.scan(zip, "broken.xlsx", "sha")
        assertTrue(result is CommentScanResult.Failure)
        assertTrue((result as CommentScanResult.Failure).message.contains("worksheet"))
    }

    @Test fun corruptOrUnsupportedImageBytesAreNotDrawable() {
        assertFalse(MediaBytes.isDrawable(null))
        assertFalse(MediaBytes.isDrawable(byteArrayOf(1, 2, 3, 4, 5)))
        assertFalse(MediaBytes.isDrawable("<svg/>".toByteArray()))
        assertTrue(MediaBytes.isDrawable(TestWorkbooks.PNG))
        assertTrue(MediaBytes.isDrawable(TestWorkbooks.JPEG))
        assertEquals("png", MediaBytes.sniff(TestWorkbooks.PNG))
        assertEquals("jpeg", MediaBytes.sniff(TestWorkbooks.JPEG))
        // The extension is only a hint; the bytes decide.
        assertEquals("png", MediaBytes.fromExtension("xl/media/image1.png"))
        assertEquals("unknown", MediaBytes.fromExtension("xl/media/blob.bin"))
    }

    @Test fun anUnresolvedAssociationIsNotReportedAsUnreferenced() {
        val zip = TestWorkbooks.xlsx(
            commentsXml = TestWorkbooks.comments(listOf("A"), TestWorkbooks.comment("C5", 0, "<t>lost</t>")),
            vmlXml = TestWorkbooks.vml(TestWorkbooks.noteShape(4, 2, "<v:fill src=\"../media/missing.png\"/>")),
            media = mapOf("xl/media/image1.png" to TestWorkbooks.PNG),
        )

        val result = model(zip)
        // The comment keeps an unresolved reference; the media list knows nothing about it.
        assertEquals(CommentImageState.UNRESOLVED_IMAGE, result.comments.single().state)
        assertFalse(result.media.single().isReferenced)
        assertTrue(result.diagnostics.any { it.level == DiagnosticLevel.WARNING })
    }
}
