package com.bookcovermatcher

import com.bookcovermatcher.core.excel.comments.CatalogueFilter
import com.bookcovermatcher.core.excel.comments.CatalogueFiltering
import com.bookcovermatcher.core.excel.comments.FilterField
import com.bookcovermatcher.core.excel.comments.ImageRow
import com.bookcovermatcher.core.excel.comments.ImageSort
import com.bookcovermatcher.core.excel.comments.ImageState
import com.bookcovermatcher.core.excel.comments.WorkbookAnalysis
import com.bookcovermatcher.core.excel.comments.WorkbookCommentImages
import com.bookcovermatcher.core.excel.comments.ImageIndex

/** The two lists of the workbook preview: legacy comments, and every media file in the package. */
enum class CommentsSection { COMMENTS, MEDIA, CATALOGUE }

/** What the full-screen viewer is showing. */
data class CommentViewerUi(
    val mediaPath: String,
    val title: String,
    /** Worksheet / cell / comment association, when one is known. */
    val associations: List<String>,
)

/**
 * State of the offline "workbook comments & images" preview.
 *
 * [open] is the screen itself: the feature lives on its own surface, opened from More, so nothing
 * here can change the camera, matching, wishlist or online state.
 */
data class WorkbookCommentsUi(
    val open: Boolean = false,
    val workbookName: String = "",
    val sha256: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val model: WorkbookCommentImages? = null,
    /** Catalogue tables, the image inventory and its coverage; null until the analysis ran. */
    val analysis: WorkbookAnalysis? = null,
    val section: CommentsSection = CommentsSection.COMMENTS,
    /** Sheet filter; null means "all sheets". */
    val selectedSheet: String? = null,
    /** The comment whose detail panel is open, as (sheet, cell). */
    val selectedSheetName: String? = null,
    val selectedCell: String? = null,
    val viewer: CommentViewerUi? = null,
    /** Media path currently being exported, for a progress label. */
    val exporting: String? = null,
    /** All Images: free-text search over filename, worksheet, cell and catalogue values. */
    val imageSearch: String = "",
    /**
     * All Images: the **single** association filter, or null for "All". One value, not a set: the chips
     * are mutually exclusive, so the highlighted chip and the displayed rows can never disagree.
     */
    val imageFilter: ImageState? = null,
    /** All Images: catalogue filters; a field's identity is (worksheet, column), never its header text. */
    val catalogueFilters: List<CatalogueFilter> = emptyList(),
    val imageSort: ImageSort = ImageSort.NAME,
    val ascending: Boolean = true,
    val gridMode: Boolean = true,
    /** All Images: thumbnail size in grid mode, 0 = small, 1 = default, 2 = large. */
    val thumbSize: Int = 1,
    /** Selected inventory keys, for the batch export. */
    val selectedImages: Set<String> = emptySet(),
    /** Catalogue tab: free-text search across the table's own columns. */
    val catalogueQuery: String = "",
    /** Worksheet picker visibility. */
    val sheetPickerOpen: Boolean = false,
    val sheetSearch: String = "",
    /** Catalogue filter picker: open state, the chosen field, and the two search boxes. */
    val filterPickerOpen: Boolean = false,
    val filterFieldKey: String? = null,
    val filterSearch: String = "",
    val filterValueSearch: String = "",
) {
    val comments get() = model?.comments.orEmpty()
    val media get() = model?.media.orEmpty()
    val sheets get() = analysis?.sheets.orEmpty()
    val tables get() = analysis?.tables.orEmpty()
    val coverage get() = analysis?.coverage

    val visibleComments get() = selectedSheet?.let { sheet -> comments.filter { it.sheetName == sheet } } ?: comments

    /** The All Images list as the user currently sees it (search + association + catalogue filters + sort). */
    val visibleImages: List<ImageRow>
        get() = ImageIndex.query(
            rows = analysis?.images.orEmpty(),
            needle = imageSearch,
            states = imageFilter?.let { setOf(it) } ?: emptySet(),
            filters = catalogueFilters,
            sort = imageSort,
            ascending = ascending,
            tables = tables,
            sheetName = selectedSheet,
        )

    /** The whole inventory, before filters — the number the filter chips compare against. */
    val allImages: List<ImageRow> get() = analysis?.images.orEmpty()

    val selectedRows: List<ImageRow> get() = allImages.filter { it.key in selectedImages }

    val selected: com.bookcovermatcher.core.excel.comments.LegacyCommentEntry?
        get() {
            val cell = selectedCell ?: return null
            val sheet = selectedSheetName ?: return null
            return comments.firstOrNull { it.cell == cell && it.sheetName == sheet }
        }

    /** Catalogue tables in the current worksheet scope: one sheet's tables, or all of them. */
    val scopedTables get() = CatalogueFiltering.tablesFor(tables, selectedSheet)

    /** Selectable filter fields of the scoped catalogues (≥2 distinct values, duplicate labels disambiguated). */
    val filterFields: List<FilterField> get() = CatalogueFiltering.fields(scopedTables)

    /** The field whose values the picker is showing, when it is open. */
    val activeFilterField: FilterField? get() = filterFields.firstOrNull { it.key == filterFieldKey }

    /** Values of [field], narrowed by the value search box; never truncated. */
    fun filterValues(field: FilterField): List<String> {
        val table = scopedTables.firstOrNull { it.sheetName == field.sheetName } ?: return emptyList()
        val values = CatalogueFiltering.values(table, field.column)
        val needle = filterValueSearch.trim()
        return if (needle.isEmpty()) values else values.filter { it.contains(needle, ignoreCase = true) }
    }

    /** Filters whose values are still reachable in the current scope, for the active-filter chips. */
    val activeFilters: List<CatalogueFilter>
        get() = catalogueFilters.filter { filter ->
            scopedTables.any { table ->
                table.sheetName == filter.sheetName && table.fields.any { it.column == filter.column }
            }
        }

    /** Catalogue records matching the query and filters, with the table they came from. */
    val visibleRecords: List<CatalogueRow>
        get() {
            val tables = scopedTables
            if (tables.isEmpty()) return emptyList()
            val byRow = allImages
                .filter { it.state == ImageState.LINKED }
                .flatMap { row -> row.catalogue.map { "${it.sheetName}#${it.row}" } }
                .toSet()
            return tables.flatMap { table ->
                table.records
                    .filter { record ->
                        table.matches(record, catalogueQuery) &&
                            CatalogueFiltering.matches(table, record, catalogueFilters)
                    }
                    .map { record ->
                        CatalogueRow(table, record, "${table.sheetName}#${record.row}" in byRow)
                    }
            }
        }

    /** Worksheets of the picker, hidden ones included and labelled. */
    val filteredSheets get() = sheets.filter { it.name.contains(sheetSearch.trim(), ignoreCase = true) }

    /** Grid cell target for the current [thumbSize]; the thumbnail box is slightly smaller. */
    val thumbCellDp: Int get() = when (thumbSize) { 0 -> 84; 2 -> 168; else -> 112 }
    val thumbBoxDp: Int get() = when (thumbSize) { 0 -> 72; 2 -> 152; else -> 100 }

    /** Per-worksheet counts, from parsed data only — a sheet with no verifiable link shows 0, not a guess. */
    val sheetSummaries: List<SheetSummary>
        get() = sheets.map { sheet ->
            val sheetComments = comments.filter { it.sheetName == sheet.name }
            val linked = allImages.count { row ->
                row.state == ImageState.LINKED && row.associations.any { it.sheetName == sheet.name }
            }
            val unresolved = allImages.count { row ->
                row.state == ImageState.UNRESOLVED && row.associations.any { it.sheetName == sheet.name }
            }
            SheetSummary(
                sheet.name, sheet.visibility, sheet.label, sheetComments.size, linked, unresolved,
                dimension = sheet.dimension,
            )
        }
}

/** One row of the Catalogue tab: the record, its table, and whether a verified image is linked to it. */
data class CatalogueRow(
    val table: com.bookcovermatcher.core.excel.comments.CatalogueTable,
    val record: com.bookcovermatcher.core.excel.comments.CatalogueRecord,
    val hasImage: Boolean,
)

/** One row of the worksheet picker, with the counts that are actually known for that sheet. */
data class SheetSummary(
    val name: String,
    val visibility: String,
    val visibilityLabel: String,
    val comments: Int,
    val linkedImages: Int,
    val unresolvedImages: Int,
    /** Excel's declared used range for the sheet (`A1:P120`), or null when the part declares none. */
    val dimension: String? = null,
) {
    val hidden: Boolean get() = !visibility.equals("visible", ignoreCase = true)
}
