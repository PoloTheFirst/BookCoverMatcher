package com.bookcovermatcher.core

import com.bookcovermatcher.core.excel.MapZipSource
import com.bookcovermatcher.core.excel.comments.CommentImageScanner
import com.bookcovermatcher.core.excel.comments.CommentImageState
import com.bookcovermatcher.core.excel.comments.CommentScanResult
import com.bookcovermatcher.core.excel.comments.MediaRefKind
import com.bookcovermatcher.core.excel.comments.MediaResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun scanned(zip: MapZipSource) =
    (CommentImageScanner.scan(zip, "test.xlsx", "sha256-test") as CommentScanResult.Success).workbook

/** Legacy note text: authors, rich-text runs, and the comments-XML cell mapping. */
class LegacyCommentScanTest {

    @Test fun textOnlyCommentKeepsAuthorTextAndRuns() {
        val zip = TestWorkbooks.xlsx(
            commentsXml = TestWorkbooks.comments(
                listOf("Alice"),
                TestWorkbooks.comment(
                    "C5",
                    0,
                    "<r><t>Hello</t></r><r><t xml:space=\"preserve\"> world</t></r>",
                ),
            ),
        )

        val comment = scanned(zip).comments.single()

        assertEquals("C5", comment.cell)
        assertEquals(4, comment.row)
        assertEquals(2, comment.col)
        assertEquals("Hello world", comment.text)
        assertEquals("Alice", comment.author)
        assertEquals(CommentImageState.TEXT_ONLY, comment.state)
        assertTrue(comment.media.isEmpty())
    }

    @Test fun multipleCommentsInOneSheetAreAllParsed() {
        val zip = TestWorkbooks.xlsx(
            commentsXml = TestWorkbooks.comments(
                listOf("A", "B"),
                TestWorkbooks.comment("A1", 0, "<t>one</t>"),
                TestWorkbooks.comment("B2", 1, "<t>two</t>"),
                TestWorkbooks.comment("AA10", 0, "<t>three</t>"),
            ),
        )

        val model = scanned(zip)
        assertEquals(3, model.comments.size)
        assertEquals(listOf("A1", "B2", "AA10"), model.comments.map { it.cell })
        assertEquals(listOf("A", "B", "A"), model.comments.map { it.author })
        // AA10 proves multi-letter columns are converted, not assumed single-letter.
        assertEquals(26, model.comments.last().col)
        assertEquals(9, model.comments.last().row)
    }

    @Test fun directSrcImageIsResolvedWithoutRelationships() {
        val zip = TestWorkbooks.xlsx(
            commentsXml = TestWorkbooks.comments(listOf("A"), TestWorkbooks.comment("C5", 0, "<t>pic</t>")),
            vmlXml = TestWorkbooks.vml(
                TestWorkbooks.noteShape(4, 2, "<v:fill src=\"../media/image1.png\"/>"),
            ),
        )

        val comment = scanned(zip).comments.single()
        assertEquals(CommentImageState.WITH_IMAGES, comment.state)
        assertEquals(listOf("xl/media/image1.png"), comment.mediaPaths)
        assertEquals(MediaRefKind.SRC, comment.media.single().kind)
        assertEquals(MediaResolution.RESOLVED, comment.media.single().resolution)
    }

    @Test fun relationshipIdIsResolvedThroughVmlRelationships() {
        val zip = TestWorkbooks.xlsx(
            commentsXml = TestWorkbooks.comments(listOf("A"), TestWorkbooks.comment("C5", 0, "<t>pic</t>")),
            vmlXml = TestWorkbooks.vml(TestWorkbooks.noteShape(4, 2, "<v:fill o:relid=\"rId1\"/>")),
            vmlRelsXml = TestWorkbooks.vmlRels("rId1" to "../media/image1.png"),
        )

        val comment = scanned(zip).comments.single()
        assertEquals(listOf("xl/media/image1.png"), comment.mediaPaths)
        assertEquals(MediaRefKind.RELID, comment.media.single().kind)
    }

    @Test fun multipleImagesOnOneNoteAreAllKept() {
        val zip = TestWorkbooks.xlsx(
            commentsXml = TestWorkbooks.comments(listOf("A"), TestWorkbooks.comment("C5", 0, "<t>two</t>")),
            vmlXml = TestWorkbooks.vml(
                TestWorkbooks.noteShape(
                    4, 2,
                    "<v:imagedata src=\"../media/image1.png\"/><v:imagedata src=\"../media/image2.jpg\"/>",
                ),
            ),
            media = mapOf(
                "xl/media/image1.png" to TestWorkbooks.PNG,
                "xl/media/image2.jpg" to TestWorkbooks.JPEG,
            ),
        )

        val comment = scanned(zip).comments.single()
        assertEquals(listOf("xl/media/image1.png", "xl/media/image2.jpg"), comment.mediaPaths)
        assertEquals(2, comment.media.size)
    }

    @Test fun duplicateReferencesToOneImageAreDeduplicated() {
        val zip = TestWorkbooks.xlsx(
            commentsXml = TestWorkbooks.comments(listOf("A"), TestWorkbooks.comment("C5", 0, "<t>dup</t>")),
            vmlXml = TestWorkbooks.vml(
                TestWorkbooks.noteShape(
                    4, 2,
                    "<v:fill src=\"../media/image1.png\"/><v:imagedata src=\"../media/image1.png\"/>",
                ),
            ),
        )

        assertEquals(listOf("xl/media/image1.png"), scanned(zip).comments.single().mediaPaths)
    }

    @Test fun vmlCoordinatesAreZeroBasedAndAgreeWithTheOneBasedRef() {
        // Row 0 / column 0 is A1; row 4 / column 2 is C5.
        val zip = TestWorkbooks.xlsx(
            commentsXml = TestWorkbooks.comments(listOf("A"), TestWorkbooks.comment("A1", 0, "<t>top</t>")),
            vmlXml = TestWorkbooks.vml(TestWorkbooks.noteShape(0, 0, "<v:fill src=\"../media/image1.png\"/>")),
        )

        val comment = scanned(zip).comments.single()
        assertEquals("A1", comment.cell)
        assertEquals(0, comment.vmlRow)
        assertEquals(0, comment.vmlCol)
        assertFalse(comment.coordinateMismatch)
    }

    @Test fun commentsXmlWinsWhenVmlCoordinatesDisagree() {
        // The comment says C5; the note shape claims D6. Nothing is attached to the wrong cell.
        val zip = TestWorkbooks.xlsx(
            commentsXml = TestWorkbooks.comments(listOf("A"), TestWorkbooks.comment("C5", 0, "<t>moved</t>")),
            vmlXml = TestWorkbooks.vml(TestWorkbooks.noteShape(5, 3, "<v:fill src=\"../media/image1.png\"/>")),
        )

        val model = scanned(zip)
        val comment = model.comments.single()
        assertEquals("C5", comment.cell)
        assertEquals(CommentImageState.TEXT_ONLY, comment.state)
        assertTrue(comment.media.isEmpty())
        assertTrue(
            "the unmatched shape must be reported",
            model.diagnostics.any { it.message.contains("has no matching comment") },
        )
    }
}
