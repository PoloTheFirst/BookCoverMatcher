package com.bookcovermatcher.core

import com.bookcovermatcher.core.excel.MapZipSource
import com.bookcovermatcher.core.excel.comments.CatalogueReader
import com.bookcovermatcher.core.excel.comments.CatalogueTable
import com.bookcovermatcher.core.excel.comments.CellValue
import com.bookcovermatcher.core.excel.comments.CommentMediaRef
import com.bookcovermatcher.core.excel.comments.CatalogueFilter
import com.bookcovermatcher.core.excel.comments.ImageIndex
import com.bookcovermatcher.core.excel.comments.ImageSort
import com.bookcovermatcher.core.excel.comments.ImageState
import com.bookcovermatcher.core.excel.comments.LegacyCommentEntry
import com.bookcovermatcher.core.excel.comments.MediaAssociation
import com.bookcovermatcher.core.excel.comments.MediaRefKind
import com.bookcovermatcher.core.excel.comments.MediaResolution
import com.bookcovermatcher.core.excel.comments.SheetCells
import com.bookcovermatcher.core.excel.comments.WorkbookCommentImages
import com.bookcovermatcher.core.excel.comments.WorkbookMediaEntry
import com.bookcovermatcher.core.export.ImageManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Catalogue discovery, the image inventory join, filtering/sorting and the CSV manifest. */
class CatalogueImageIndexTest {

    private fun cells(): List<CellValue> = listOf(
        CellValue(0, 0, "タイトル"), CellValue(0, 1, "作者"), CellValue(0, 2, "サークル名"), CellValue(0, 3, "ジャンル"),
        CellValue(1, 0, "東方紅魔郷"), CellValue(1, 1, "ZUN"), CellValue(1, 2, "上海アリス幻樂団"), CellValue(1, 3, "音楽"),
        CellValue(2, 0, "Blue Archive"), CellValue(2, 1, "Yostar"), CellValue(2, 2, "N/A"), CellValue(2, 3, "ゲーム"),
    )

    private fun table(): CatalogueTable =
        CatalogueReader.fromCells(cells(), "LIST") ?: error("the fixture must detect as a table")

    private fun model(
        commentCell: String = "C2",
        media: List<WorkbookMediaEntry> = listOf(
            WorkbookMediaEntry(
                "xl/media/image1.png", "image1.png", 1_024L, "png",
                listOf(MediaAssociation("LIST", commentCell, "cover")),
            ),
            WorkbookMediaEntry("xl/media/logo.png", "logo.png", 512L, "png", emptyList()),
        ),
        refs: List<CommentMediaRef> = listOf(
            CommentMediaRef("../media/image1.png", MediaRefKind.SRC, MediaResolution.RESOLVED, "xl/media/image1.png"),
        ),
    ): WorkbookCommentImages = WorkbookCommentImages(
        workbookName = "同人誌.xlsm",
        sha256 = "sha",
        sheets = listOf("LIST"),
        comments = listOf(
            LegacyCommentEntry(
                sheetName = "LIST", sheetPath = "xl/worksheets/sheet1.xml", cell = commentCell,
                row = 1, col = 2, text = "cover", author = "A", media = refs,
            ),
        ),
        media = media,
        diagnostics = emptyList(),
        threadedCommentParts = emptyList(),
    )

    @Test fun catalogueHeadersComeFromTheWorkbookItself() {
        val table = table()

        assertEquals("LIST", table.sheetName)
        assertEquals(0, table.headerRow)
        assertEquals(listOf("タイトル", "作者", "サークル名", "ジャンル"), table.fields.map { it.name })
        assertEquals(2, table.records.size)
        assertEquals("タイトル", table.titleField?.name)
        assertEquals("作者", table.authorField?.name)
        assertEquals("サークル名", table.circleField?.name)
        assertEquals("東方紅魔郷", table.records.first().value(table.titleField!!))
    }

    @Test fun sharedStringsAreResolvedBeforeDetection() {
        val shared = "<sst><si><t>書名</t></si><si><t>著者</t></si><si><t>サークル</t></si>" +
            "<si><t>本</t></si><si><t>人</t></si><si><t>所</t></si></sst>"
        val sheet = "<worksheet><sheetData>" +
            "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c>" +
            "<c r=\"C1\" t=\"s\"><v>2</v></c></row>" +
            "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>3</v></c><c r=\"B2\" t=\"s\"><v>4</v></c>" +
            "<c r=\"C2\" t=\"s\"><v>5</v></c></row>" +
            "</sheetData></worksheet>"
        val zip = MapZipSource(
            linkedMapOf(
                "xl/sharedStrings.xml" to shared.toByteArray(),
                "xl/worksheets/sheet1.xml" to sheet.toByteArray(),
            ),
        )

        val table = CatalogueReader.read(zip, "xl/worksheets/sheet1.xml", "Sheet1")

        assertNotNull(table)
        assertEquals(listOf("書名", "著者", "サークル"), table!!.fields.map { it.name })
        assertEquals("本", table.records.single().values.first())
    }

    @Test fun aSheetWithoutAHeaderIsNotACatalogue() {
        val pictureSheet = listOf(CellValue(0, 0, "cover"), CellValue(1, 0, "another"))
        assertNull(CatalogueReader.fromCells(pictureSheet, "Pictures"))
    }

    @Test fun sheetCellReferencesAreParsed() {
        assertEquals(0 to 0, SheetCells.refToCell("A1"))
        assertEquals(4 to 2, SheetCells.refToCell("\$C\$5"))
        assertEquals(9 to 26, SheetCells.refToCell("aa10"))
        assertNull(SheetCells.refToCell("A1:B2"))
    }

    @Test fun aCommentOnACatalogueRowCarriesThatRecord() {
        val rows = ImageIndex.build(model(), listOf(table()))
        val linked = rows.single { it.state == ImageState.LINKED }

        assertEquals("xl/media/image1.png", linked.mediaPath)
        assertEquals("LIST", linked.sheetName)
        assertEquals("C2", linked.cell)
        assertEquals("東方紅魔郷", linked.catalogue.single().title)
        assertEquals("ZUN", linked.catalogue.single().author)
        assertEquals("上海アリス幻樂団", linked.catalogue.single().circle)
    }

    @Test fun unreferencedAndUnresolvedAreDistinctStates() {
        val unresolvedRef = CommentMediaRef(
            "rId9", MediaRefKind.RELID, MediaResolution.UNRESOLVED, null, "relationship not declared",
        )
        val rows = ImageIndex.build(
            model(
                media = listOf(WorkbookMediaEntry("xl/media/logo.png", "logo.png", 512L, "png", emptyList())),
                refs = listOf(unresolvedRef),
            ),
            listOf(table()),
        )

        assertEquals(ImageState.UNREFERENCED, rows.single { it.fileName == "logo.png" }.state)
        val unresolved = rows.single { it.state == ImageState.UNRESOLVED }
        assertEquals("rId9", unresolved.fileName)
        assertEquals("relationship not declared", unresolved.unresolvedDetail)
        assertNull("an unresolved reference has no media part", unresolved.mediaPath)
        assertEquals(1, ImageIndex.query(rows, "", setOf(ImageState.UNRESOLVED), emptyList(), ImageSort.NAME, true).size)
        assertEquals(1, ImageIndex.query(rows, "", setOf(ImageState.UNREFERENCED), emptyList(), ImageSort.NAME, true).size)
    }

    @Test fun searchMatchesFilenamesAndCatalogueValues() {
        val rows = ImageIndex.build(model(), listOf(table()))

        assertEquals(
            listOf("image1.png"),
            ImageIndex.query(rows, "zun", emptySet(), emptyList(), ImageSort.NAME, true).map { it.fileName },
        )
        // The Blue Archive record exists, but no image is linked to its row, so searching for it must not
        // invent one: the inventory only holds rows that exist in the package.
        assertEquals(0, ImageIndex.query(rows, "blue archive", emptySet(), emptyList(), ImageSort.NAME, true).size)
        assertEquals(
            listOf("image1.png"),
            ImageIndex.query(rows, "東方", emptySet(), emptyList(), ImageSort.NAME, true).map { it.fileName },
        )
        assertEquals(
            listOf("logo.png"),
            ImageIndex.query(rows, "logo", emptySet(), emptyList(), ImageSort.NAME, true).map { it.fileName },
        )
        assertEquals(rows.size, ImageIndex.query(rows, "   ", emptySet(), emptyList(), ImageSort.NAME, true).size)
    }

    @Test fun catalogueFiltersCombineWithSearchAndState() {
        val rows = ImageIndex.build(model(), listOf(table()))
        val author = table().authorField!!
        val hit = listOf(CatalogueFilter(table().sheetName, author.column, "ZUN"))

        val filtered = ImageIndex.query(rows, "東方", emptySet(), hit, ImageSort.NAME, true, listOf(table()))

        assertEquals(listOf("image1.png"), filtered.map { it.fileName })
        val miss = listOf(CatalogueFilter(table().sheetName, author.column, "nobody"))
        assertTrue(ImageIndex.query(rows, "", emptySet(), miss, ImageSort.NAME, true, listOf(table())).isEmpty())
    }

    @Test fun sortingPlacesMissingValuesLastAndIsStable() {
        val rows = ImageIndex.build(
            model(
                media = listOf(
                    WorkbookMediaEntry("xl/media/b.png", "b.png", 900L, "png", emptyList()),
                    WorkbookMediaEntry("xl/media/a.png", "a.png", -1L, "png", emptyList()),
                    WorkbookMediaEntry("xl/media/c.png", "c.png", 900L, "png", emptyList()),
                ),
                refs = emptyList(),
            ),
            emptyList(),
        )

        assertEquals(
            listOf("b.png", "c.png", "a.png"),
            ImageIndex.sortRows(rows, ImageSort.SIZE, ascending = true).map { it.fileName },
        )
        assertEquals(
            listOf("c.png", "b.png", "a.png"),
            ImageIndex.sortRows(rows, ImageSort.NAME, ascending = false).map { it.fileName },
        )
    }

    @Test fun coverageCountsRecordsWithAndWithoutImages() {
        val table = table()
        val coverage = ImageIndex.coverage(listOf(table), ImageIndex.build(model(), listOf(table)))

        assertEquals(2, coverage.recordsTotal)
        assertEquals(1, coverage.recordsWithImage)
        assertEquals(1, coverage.recordsWithoutImage)
        assertEquals(1, coverage.unreferencedMedia)
        assertEquals(0, coverage.unresolvedAssociations)
    }

    @Test fun theManifestEscapesRfc4180AndPreservesUnicode() {
        val rows = ImageIndex.build(model(), listOf(table()))
        val linked = rows.single { it.state == ImageState.LINKED }

        val csv = ImageManifest.csv(listOf("image1.png" to linked))

        assertTrue(csv.startsWith("exported_file,media_file,format,bytes,width,height,status,worksheet,cell,title,author,circle"))
        assertTrue(csv.contains("image1.png,image1.png,png,1024,,,linked,LIST,C2,東方紅魔郷,ZUN,上海アリス幻樂団"))
        assertEquals("\"a,b\"", ImageManifest.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", ImageManifest.escape("say \"hi\""))
        assertEquals("plain", ImageManifest.escape("plain"))
        assertTrue(ImageManifest.escape("two\nlines").startsWith("\""))
    }

    @Test fun exportFileNamesAreSafeAndUnique() {
        assertEquals("image1.png", ImageManifest.safeFileName("xl/media/image1.png"))
        // A path (either separator) collapses to its base name; no separator survives.
        assertEquals("evil.png", ImageManifest.safeFileName("../../evil.png"))
        assertTrue(!ImageManifest.safeFileName("..\\..\\etc\\passwd").contains(".."))
        val mixed = ImageManifest.safeFileName("../../evil\\.png")
        assertTrue(mixed.isNotEmpty())
        assertTrue(!mixed.any { it in "/\\:*?\"<>|" })
        assertTrue(!ImageManifest.safeFileName("a/b:c*d?e\"f<g>h|i.png").any { it in "/\\:*?\"<>|" })
        val used = HashSet<String>()
        assertEquals("a.png", ImageManifest.uniqueName("a.png", used))
        assertEquals("a (1).png", ImageManifest.uniqueName("a.png", used))
        assertEquals("a (2).png", ImageManifest.uniqueName("a.png", used))
    }
}
