package com.bookcovermatcher

import com.bookcovermatcher.core.excel.comments.CatalogueField
import com.bookcovermatcher.core.excel.comments.CatalogueRecord
import com.bookcovermatcher.core.excel.comments.CatalogueTable
import com.bookcovermatcher.core.excel.comments.ImageRow
import com.bookcovermatcher.core.excel.comments.ImageState
import com.bookcovermatcher.core.excel.comments.CommentImageState
import com.bookcovermatcher.core.excel.comments.CommentMediaRef
import com.bookcovermatcher.core.excel.comments.LegacyCommentEntry
import com.bookcovermatcher.core.excel.comments.MediaRefKind
import com.bookcovermatcher.core.excel.comments.MediaResolution
import com.bookcovermatcher.core.excel.comments.WorkbookCommentImages
import com.bookcovermatcher.core.excel.comments.WorkbookAnalysis
import com.bookcovermatcher.core.excel.comments.WorkbookMediaEntry
import com.bookcovermatcher.core.excel.comments.MediaAssociation
import com.bookcovermatcher.core.excel.comments.ImageIndex
import com.bookcovermatcher.core.excel.comments.ImageCoverage
import com.bookcovermatcher.core.excel.comments.SheetInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preview's selection rules, kept out of Compose so they can be tested: a tapped cell shows the
 * comment of *that* sheet, and the sheet filter never mixes two worksheets.
 */
class WorkbookCommentsUiTest {

    private fun comment(sheet: String, cell: String, row: Int, state: CommentImageState) = LegacyCommentEntry(
        sheetName = sheet,
        sheetPath = "xl/worksheets/sheet1.xml",
        cell = cell,
        row = row,
        col = 0,
        text = "text of $cell",
        author = "A",
        media = when (state) {
            CommentImageState.WITH_IMAGES -> listOf(
                CommentMediaRef("../media/image1.png", MediaRefKind.SRC, MediaResolution.RESOLVED, "xl/media/image1.png"),
            )
            CommentImageState.UNRESOLVED_IMAGE -> listOf(
                CommentMediaRef("rId9", MediaRefKind.RELID, MediaResolution.UNRESOLVED, null, "missing"),
            )
            CommentImageState.TEXT_ONLY -> emptyList()
        },
    )

    private val model = WorkbookCommentImages(
        workbookName = "book.xlsx",
        sha256 = "sha",
        sheets = listOf("One", "Two"),
        comments = listOf(
            comment("One", "A1", 0, CommentImageState.TEXT_ONLY),
            comment("One", "C5", 4, CommentImageState.WITH_IMAGES),
            comment("Two", "C5", 4, CommentImageState.UNRESOLVED_IMAGE),
        ),
        media = emptyList(),
        diagnostics = emptyList(),
        threadedCommentParts = emptyList(),
    )

    @Test fun theSelectedCommentComesFromTheSelectedSheet() {
        val ui = WorkbookCommentsUi(model = model, selectedSheetName = "Two", selectedCell = "C5")

        assertEquals("Two", ui.selected?.sheetName)
        assertEquals(CommentImageState.UNRESOLVED_IMAGE, ui.selected?.state)
    }

    @Test fun noSelectionShowsNoPanel() {
        assertNull(WorkbookCommentsUi(model = model).selected)
    }

    @Test fun theSheetFilterNeverMixesWorksheets() {
        val all = WorkbookCommentsUi(model = model)
        assertEquals(3, all.visibleComments.size)

        val one = WorkbookCommentsUi(model = model, selectedSheet = "One")
        assertEquals(listOf("One", "One"), one.visibleComments.map { it.sheetName })
        assertEquals(listOf("A1", "C5"), one.visibleComments.map { it.cell })
    }

    @Test fun theCountsMatchTheThreeMarkerStates() {
        assertEquals(1, model.textOnlyCount)
        assertEquals(1, model.imageCommentCount)
        assertEquals(1, model.unresolvedCount)
    }

    @Test fun perSheetSummariesUseParsedCountsOnly() {
        val media = WorkbookMediaEntry(
            "xl/media/a.png", "a.png", 10L, "png",
            listOf(MediaAssociation("One", "C5", "cover")),
        )
        val withMedia = WorkbookCommentImages(
            workbookName = "w", sha256 = "s", sheets = listOf("One", "Two"),
            comments = listOf(
                comment("One", "A1", 0, CommentImageState.TEXT_ONLY),
                comment("One", "C5", 4, CommentImageState.WITH_IMAGES),
                comment("Two", "C5", 4, CommentImageState.UNRESOLVED_IMAGE),
            ),
            media = listOf(media), diagnostics = emptyList(), threadedCommentParts = emptyList(),
        )
        val analysis = WorkbookAnalysis(
            sheets = listOf(SheetInfo("One", "visible"), SheetInfo("Two", "hidden")),
            tables = emptyList(),
            images = ImageIndex.build(withMedia, emptyList()),
            coverage = ImageCoverage(0, 0, 0, 0),
            unreadableSheets = emptyList(),
        )

        val summaries = WorkbookCommentsUi(model = withMedia, analysis = analysis).sheetSummaries

        val one = summaries.first { it.name == "One" }
        assertEquals(2, one.comments)
        assertEquals(1, one.linkedImages)
        assertEquals(0, one.unresolvedImages)
        assertEquals("Visible", one.visibilityLabel)

        val two = summaries.first { it.name == "Two" }
        assertEquals(1, two.comments)
        assertEquals(0, two.linkedImages)
        assertEquals(1, two.unresolvedImages)
        assertTrue(two.hidden)
        assertEquals("Hidden", two.visibilityLabel)
    }
    @Test fun thumbnailSizeChoicesMapToGridCells() {
        val small = WorkbookCommentsUi(imageSearch = "", thumbSize = 0)
        val normal = WorkbookCommentsUi()
        val large = WorkbookCommentsUi(thumbSize = 2)

        assertEquals(84, small.thumbCellDp)
        assertEquals(72, small.thumbBoxDp)
        assertEquals(112, normal.thumbCellDp)
        assertEquals(100, normal.thumbBoxDp)
        assertEquals(168, large.thumbCellDp)
        assertEquals(152, large.thumbBoxDp)
    }

    @Test fun theThumbnailSizeNeverChangesTheVisibleImages() {
        val analysis = WorkbookAnalysis(
            sheets = listOf(SheetInfo("One", "visible")),
            tables = emptyList(),
            images = emptyList(),
            coverage = ImageCoverage(0, 0, 0, 0),
            unreadableSheets = emptyList(),
        )
        val ui = WorkbookCommentsUi(analysis = analysis)
        val keys = ui.visibleImages.map { it.key }

        assertEquals(keys, ui.copy(thumbSize = 0).visibleImages.map { it.key })
        assertEquals(keys, ui.copy(thumbSize = 2).visibleImages.map { it.key })
    }
    private fun scoped(): Pair<WorkbookCommentImages, WorkbookAnalysis> {
        fun ref(path: String) =
            CommentMediaRef("../media/${path.substringAfterLast('/')}", MediaRefKind.SRC, MediaResolution.RESOLVED, path)
        fun commentOn(sheet: String, path: String) = LegacyCommentEntry(
            sheetName = sheet, sheetPath = "xl/worksheets/sheet1.xml", cell = "C2", row = 1, col = 2,
            text = "t", author = "A", media = listOf(ref(path)),
        )
        val model = WorkbookCommentImages(
            workbookName = "w", sha256 = "s", sheets = listOf("One", "Two"),
            comments = listOf(commentOn("One", "xl/media/image1.png"), commentOn("Two", "xl/media/image2.png")),
            media = listOf(
                WorkbookMediaEntry("xl/media/image1.png", "image1.png", 10L, "png", emptyList()),
                WorkbookMediaEntry("xl/media/image2.png", "image2.png", 10L, "png", emptyList()),
                WorkbookMediaEntry("xl/media/logo.png", "logo.png", 10L, "png", emptyList()),
            ),
            diagnostics = emptyList(), threadedCommentParts = emptyList(),
        )
        val list = CatalogueTable(
            "LIST", 0,
            listOf(CatalogueField("ブック名", 0, 0), CatalogueField("ジャンル", 1, 1)),
            listOf(CatalogueRecord(2, listOf("本A", "催眠")), CatalogueRecord(3, listOf("本B", "男の娘"))),
        )
        val data = CatalogueTable(
            "DATA", 0,
            listOf(CatalogueField("年別", 0, 0), CatalogueField("数", 1, 1)),
            listOf(CatalogueRecord(2, listOf("2024", "3")), CatalogueRecord(3, listOf("2025", "4"))),
        )
        val analysis = WorkbookAnalysis(
            sheets = listOf(SheetInfo("One", "visible"), SheetInfo("Two", "visible")),
            tables = listOf(list, data),
            images = ImageIndex.build(model, listOf(list, data)).map { row ->
                when (row.mediaPath) {
                    "xl/media/image1.png" -> row.copy(associations = listOf(MediaAssociation("One", "C2", "t")))
                    "xl/media/image2.png" -> row.copy(associations = listOf(MediaAssociation("Two", "C2", "t")))
                    else -> row
                }
            },
            coverage = ImageCoverage(2, 2, 0, 1),
            unreadableSheets = emptyList(),
        )
        return model to analysis
    }

    @Test fun imagesAreScopedToTheSelectedWorksheet() {
        val (model, analysis) = scoped()
        val all = WorkbookCommentsUi(model = model, analysis = analysis)

        assertEquals(3, all.visibleImages.size)
        val one = all.copy(selectedSheet = "One")
        assertEquals(listOf("xl/media/image1.png"), one.visibleImages.map { it.mediaPath })
        assertTrue(
            "media with no association has no worksheet and must not be assigned to one",
            one.visibleImages.none { it.fileName == "logo.png" },
        )
        assertEquals(
            listOf("xl/media/image2.png"),
            all.copy(selectedSheet = "Two").visibleImages.map { it.mediaPath },
        )
    }

    @Test fun catalogueRecordsAndFilterFieldsFollowTheSelectedWorksheet() {
        val (model, analysis) = scoped()
        val all = WorkbookCommentsUi(model = model, analysis = analysis)

        assertEquals(4, all.visibleRecords.size)
        assertEquals(setOf("LIST", "DATA"), all.filterFields.map { it.sheetName }.toSet())

        val list = all.copy(selectedSheet = "LIST")
        assertEquals(2, list.visibleRecords.size)
        assertEquals(setOf("LIST"), list.filterFields.map { it.sheetName }.toSet())
        assertEquals(listOf("ブック名", "ジャンル"), list.filterFields.map { it.label })

        assertTrue(
            "a worksheet without a catalogue shows nothing instead of unrelated records",
            all.copy(selectedSheet = "Two").visibleRecords.isEmpty(),
        )
    }    private fun rowsForFilters(): List<ImageRow> = listOf(
        row("xl/media/a.png", "a.png", ImageState.LINKED, "One"),
        row("xl/media/b.png", "b.png", ImageState.UNREFERENCED, null),
        row("xl/media/c.png", "c.png", ImageState.UNRESOLVED, "Two"),
    )

    private fun row(path: String, name: String, state: ImageState, sheet: String?) = ImageRow(
        key = path,
        mediaPath = path,
        fileName = name,
        format = "png",
        sizeBytes = 10L,
        width = null,
        height = null,
        state = state,
        associations = sheet?.let { listOf(MediaAssociation(it, "C2", "t")) }.orEmpty(),
        catalogue = emptyList(),
        unresolvedDetail = if (state == ImageState.UNRESOLVED) "missing" else null,
    )

    private fun filterUi(): WorkbookCommentsUi {
        val analysis = WorkbookAnalysis(
            sheets = listOf(SheetInfo("One", "visible"), SheetInfo("Two", "visible")),
            tables = emptyList(),
            images = rowsForFilters(),
            coverage = ImageCoverage(0, 0, 0, 1),
            unreadableSheets = emptyList(),
        )
        return WorkbookCommentsUi(analysis = analysis)
    }

    @Test fun theAssociationFilterIsASingleValueStartingAtAll() {
        val ui = filterUi()
        assertNull(ui.imageFilter)
        assertEquals(listOf("a.png", "b.png", "c.png"), ui.visibleImages.map { it.fileName })
    }

    @Test fun eachAssociationFilterSelectsItsOwnRowsAndIsTheActiveChip() {
        val ui = filterUi()

        val linked = ui.copy(imageFilter = ImageState.LINKED)
        assertEquals(listOf("a.png"), linked.visibleImages.map { it.fileName })
        assertEquals(ImageState.LINKED, linked.imageFilter)

        val unreferenced = linked.copy(imageFilter = ImageState.UNREFERENCED)
        assertEquals(listOf("b.png"), unreferenced.visibleImages.map { it.fileName })
        assertEquals(ImageState.UNREFERENCED, unreferenced.imageFilter)

        val unresolved = unreferenced.copy(imageFilter = ImageState.UNRESOLVED)
        assertEquals(listOf("c.png"), unresolved.visibleImages.map { it.fileName })
        assertEquals(ImageState.UNRESOLVED, unresolved.imageFilter)

        val all = unresolved.copy(imageFilter = null)
        assertEquals(3, all.visibleImages.size)
        assertNull(all.imageFilter)
    }

    @Test fun rapidSwitchingAlwaysEndsOnTheLastFilter() {
        val ui = filterUi()
        val sequence = listOf(
            ImageState.LINKED, ImageState.UNREFERENCED, ImageState.UNRESOLVED,
            ImageState.LINKED, ImageState.UNRESOLVED, null, ImageState.UNREFERENCED,
        )
        var current = ui
        sequence.forEach { current = current.copy(imageFilter = it) }

        assertEquals(ImageState.UNREFERENCED, current.imageFilter)
        assertEquals(listOf("b.png"), current.visibleImages.map { it.fileName })
    }

    @Test fun filterCountsMatchTheChips() {
        val ui = filterUi()
        assertEquals(ui.allImages.count { it.state == ImageState.LINKED }, ui.copy(imageFilter = ImageState.LINKED).visibleImages.size)
        assertEquals(ui.allImages.count { it.state == ImageState.UNREFERENCED }, ui.copy(imageFilter = ImageState.UNREFERENCED).visibleImages.size)
        assertEquals(ui.allImages.count { it.state == ImageState.UNRESOLVED }, ui.copy(imageFilter = ImageState.UNRESOLVED).visibleImages.size)
    }

    @Test fun aFilterKeepsTheSearchAndTheWorksheetScope() {
        val ui = filterUi().copy(imageSearch = "a.png", imageFilter = ImageState.LINKED)
        assertEquals("a.png", ui.imageSearch)
        assertEquals(listOf("a.png"), ui.visibleImages.map { it.fileName })

        val scoped = ui.copy(selectedSheet = "Two")
        assertTrue("One's image must not show under Two", scoped.visibleImages.isEmpty())
        assertEquals(ImageState.LINKED, scoped.imageFilter)
    }}
