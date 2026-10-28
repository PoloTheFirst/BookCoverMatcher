package com.bookcovermatcher.core

import com.bookcovermatcher.core.excel.comments.CommentDiagnostic
import com.bookcovermatcher.core.excel.comments.CommentImageScanner
import com.bookcovermatcher.core.excel.comments.CommentMediaRef
import com.bookcovermatcher.core.excel.comments.CommentScanResult
import com.bookcovermatcher.core.excel.comments.DiagnosticLevel
import com.bookcovermatcher.core.excel.comments.LegacyCommentEntry
import com.bookcovermatcher.core.excel.comments.MediaAssociation
import com.bookcovermatcher.core.excel.comments.MediaRefKind
import com.bookcovermatcher.core.excel.comments.MediaResolution
import com.bookcovermatcher.core.excel.comments.WorkbookCommentImages
import com.bookcovermatcher.core.excel.comments.WorkbookMediaEntry
import com.bookcovermatcher.data.CommentImageCache
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The on-disk cache: identity, invalidation and lossless round trips. */
class CommentImageCacheTest {

    private val dir: File = Files.createTempDirectory("comment-cache").toFile()

    @After fun cleanUp() {
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

    private fun sample(sha: String = "sha-a", secondImage: Boolean = false): WorkbookCommentImages =
        WorkbookCommentImages(
            workbookName = "book.xlsx",
            sha256 = sha,
            sheets = listOf("Sheet1"),
            comments = listOf(
                LegacyCommentEntry(
                    sheetName = "Sheet1",
                    sheetPath = "xl/worksheets/sheet1.xml",
                    cell = "C5",
                    row = 4,
                    col = 2,
                    text = "first line\nsecond \u2014 \u65e5\u672c\u8a9e",
                    author = "Alice",
                    media = buildList {
                        add(CommentMediaRef("../media/image1.png", MediaRefKind.SRC, MediaResolution.RESOLVED, "xl/media/image1.png"))
                        if (secondImage) {
                            add(CommentMediaRef("rId1", MediaRefKind.RELID, MediaResolution.RESOLVED, "xl/media/image2.png"))
                        }
                        add(CommentMediaRef("rId7", MediaRefKind.RELID, MediaResolution.UNRESOLVED, null, "missing relationship"))
                    },
                    vmlRow = 4,
                    vmlCol = 2,
                ),
            ),
            media = listOf(
                WorkbookMediaEntry(
                    "xl/media/image1.png", "image1.png", -1L, "png",
                    listOf(MediaAssociation("Sheet1", "C5", "first line")),
                ),
            ),
            diagnostics = listOf(CommentDiagnostic(DiagnosticLevel.WARNING, "xl/drawings/vmlDrawing1.vml", "note")),
            threadedCommentParts = listOf("xl/threadedComments/threadedComment1.xml"),
            truncated = true,
        )

    @Test fun aWrittenModelComesBackIntact() {
        val cache = CommentImageCache(dir)
        cache.write(sample())

        val read = cache.read("sha-a")
        assertTrue(read != null)
        assertEquals(true, read!!.fromCache)
        val comment = read.comments.single()
        assertEquals("C5", comment.cell)
        assertEquals("first line\nsecond \u2014 \u65e5\u672c\u8a9e", comment.text)
        assertEquals("Alice", comment.author)
        assertEquals(2, comment.media.size)
        assertEquals("xl/media/image1.png", comment.mediaPaths.single())
        assertEquals(MediaResolution.UNRESOLVED, comment.media.last().resolution)
        assertEquals("missing relationship", comment.media.last().detail)
        assertEquals("xl/media/image1.png", read.media.single().path)
        assertEquals("C5", read.media.single().associations.single().cell)
        assertEquals(1, read.diagnostics.size)
        assertEquals(listOf("xl/threadedComments/threadedComment1.xml"), read.threadedCommentParts)
        assertTrue(read.truncated)
    }

    @Test fun multipleImagesPerCommentSurviveTheRoundTrip() {
        val cache = CommentImageCache(dir)
        cache.write(sample(secondImage = true))

        val comment = cache.read("sha-a")!!.comments.single()
        assertEquals(3, comment.media.size)
        assertEquals(listOf("xl/media/image1.png", "xl/media/image2.png"), comment.mediaPaths)
    }

    @Test fun aDifferentWorkbookNeverReadsAnotherEntry() {
        val cache = CommentImageCache(dir)
        cache.write(sample(sha = "sha-a"))

        assertNull(cache.read("sha-b"))
        assertTrue(File(dir, "comments-sha-a.json").isFile)
    }

    @Test fun aReplacedWorkbookCannotReadTheOldEntry() {
        val cache = CommentImageCache(dir)
        cache.write(sample(sha = "sha-old"))
        cache.write(sample(sha = "sha-new", secondImage = true))

        // Each entry keeps its own data: a new workbook never sees the previous one's comments.
        assertEquals(2, cache.read("sha-old")!!.comments.single().media.size)
        assertEquals(3, cache.read("sha-new")!!.comments.single().media.size)
        // And a lookup for a workbook that was never scanned finds nothing.
        assertNull(cache.read("sha-other"))
    }

    @Test fun aCorruptEntryIsDeletedAndRescanned() {
        val cache = CommentImageCache(dir)
        cache.write(sample())
        File(dir, "comments-sha-a.json").writeText("{not json")

        assertNull(cache.read("sha-a"))
        assertFalse("a broken entry is removed, not trusted", File(dir, "comments-sha-a.json").exists())
    }

    @Test fun anEntryFromAnotherSchemaVersionIsIgnored() {
        val cache = CommentImageCache(dir)
        cache.write(sample())
        val file = File(dir, "comments-sha-a.json")
        file.writeText(file.readText().replace("\"schema\":1", "\"schema\":99"))

        assertNull(cache.read("sha-a"))
    }

    @Test fun aScannedPackageRoundTripsThroughTheCache() {
        val zip = TestWorkbooks.xlsx(
            commentsXml = TestWorkbooks.comments(listOf("A"), TestWorkbooks.comment("C5", 0, "<t>cached</t>")),
            vmlXml = TestWorkbooks.vml(TestWorkbooks.noteShape(4, 2, "<v:fill src=\"../media/image1.png\"/>")),
        )
        val fresh = (CommentImageScanner.scan(zip, "book.xlsx", "sha-scan") as CommentScanResult.Success).workbook
        val cache = CommentImageCache(dir)
        cache.write(fresh)

        val read = cache.read("sha-scan")!!
        assertEquals(fresh.comments.map { it.cell }, read.comments.map { it.cell })
        assertEquals(fresh.comments.map { it.mediaPaths }, read.comments.map { it.mediaPaths })
        assertEquals(fresh.media.map { it.path }, read.media.map { it.path })
        assertEquals(fresh.comments.single().state, read.comments.single().state)
    }

    @Test fun clearRemovesEveryEntry() {
        val cache = CommentImageCache(dir)
        cache.write(sample(sha = "sha-a"))
        cache.write(sample(sha = "sha-b"))

        cache.clear()

        assertNull(cache.read("sha-a"))
        assertNull(cache.read("sha-b"))
    }
}
