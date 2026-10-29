package com.bookcovermatcher

import com.bookcovermatcher.core.catalog.CatalogEntry
import com.bookcovermatcher.core.catalog.CatalogField
import com.bookcovermatcher.core.catalog.CollectionAnalytics
import com.bookcovermatcher.core.catalog.CollectionTimeline
import com.bookcovermatcher.core.catalog.ColumnMappingProfile
import com.bookcovermatcher.core.catalog.GalleryFilter
import com.bookcovermatcher.core.catalog.GallerySort
import com.bookcovermatcher.core.catalog.GalleryView
import com.bookcovermatcher.core.catalog.HealthCategory
import com.bookcovermatcher.core.catalog.HealthFinding
import com.bookcovermatcher.core.catalog.HealthReport
import com.bookcovermatcher.core.catalog.HealthSeverity
import com.bookcovermatcher.core.catalog.MappingIssue
import com.bookcovermatcher.core.catalog.TimelineMode
import com.bookcovermatcher.core.catalog.WorkbookCatalog
import com.bookcovermatcher.core.excel.comments.CatalogueFilter
import com.bookcovermatcher.core.excel.comments.CatalogueFiltering
import com.bookcovermatcher.core.excel.comments.CatalogueRecord
import com.bookcovermatcher.core.excel.comments.CatalogueTable
import com.bookcovermatcher.core.excel.comments.FilterField
import com.bookcovermatcher.core.excel.comments.ImageRow
import com.bookcovermatcher.core.excel.comments.ImageSort
import com.bookcovermatcher.core.excel.comments.ImageState
import com.bookcovermatcher.core.excel.comments.SheetInfo
import com.bookcovermatcher.core.excel.comments.SheetWindow
import com.bookcovermatcher.core.excel.comments.SourceLocation
import com.bookcovermatcher.core.excel.comments.WorkbookAnalysis
import com.bookcovermatcher.core.excel.comments.WorkbookCommentImages
import com.bookcovermatcher.core.excel.comments.ImageIndex

/** The two lists of the workbook preview: legacy comments, and every media file in the package. */
enum class CommentsSection { COMMENTS, MEDIA, CATALOGUE, INSIGHTS }

/**
 * The five v2.4.0 features that share one canonical catalog, in the order the screen lists them.
 *
 * They are sub-tabs of the existing preview instead of new top-level destinations: the workbook stays
 * open, and the filters underneath are untouched.
 */
enum class InsightsTab(val label: String) {
    HEALTH("Health"),
    GALLERY("Gallery"),
    ANALYTICS("Analytics"),
    TIMELINE("Timeline"),
    FIELDS("Fields"),
}

/** What the full-screen viewer is showing. */
data class CommentViewerUi(
    val mediaPath: String,
    val title: String,
    /** Worksheet / cell / comment association, when one is known. */
    val associations: List<String>,
)

/**
 * The open source-location view.
 *
 * [candidates] are the destinations the parser verified. One candidate opens straight away; several
 * are listed so the user chooses, never the app. [window] is the worksheet snippet once the cells were
 * read; [error] explains why no window can be shown (workbook gone, unreadable sheet, no association).
 *
 * The view is deliberately an overlay: opening it does not touch the section, the worksheet filter,
 * the image search, the sort order or the catalogue filters underneath.
 */
data class SourceViewUi(
    /** What the user asked to locate, e.g. `image3.png` or `LIST row 128`. */
    val heading: String,
    val candidates: List<SourceLocation.Candidate>,
    val chosen: SourceLocation.Candidate? = null,
    val window: SheetWindow? = null,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val hasDestination: Boolean get() = candidates.isNotEmpty()
}

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
    /** The source-location overlay ("go to worksheet/cell"); null when it is closed. */
    val source: SourceViewUi? = null,
    /** Which Insights screen is showing. */
    val insightsTab: InsightsTab = InsightsTab.HEALTH,
    /** The shared catalog for this workbook and mapping; null until it has been built. */
    val catalog: WorkbookCatalog? = null,
    val insightsBusy: Boolean = false,
    val insightsError: String? = null,
    val health: HealthReport? = null,
    val analytics: CollectionAnalytics? = null,
    val publicationTimeline: CollectionTimeline? = null,
    val acquisitionTimeline: CollectionTimeline? = null,
    val importTimeline: CollectionTimeline? = null,
    val timelineMode: TimelineMode = TimelineMode.PUBLICATION,
    val timelineAscending: Boolean = false,
    val healthSeverity: HealthSeverity? = null,
    val healthCategory: HealthCategory? = null,
    val galleryFilter: GalleryFilter = GalleryFilter(),
    val gallerySort: GallerySort = GallerySort.TITLE,
    val galleryAscending: Boolean = true,
    /** Saved mapping profiles of the app; empty until the store was read. */
    val profiles: List<ColumnMappingProfile> = emptyList(),
    /** Name of the saved profile the user picked, or null for the auto-detected mapping. */
    val activeProfile: String? = null,
    /** The mapping being edited on the Fields screen: field -> header, null meaning "not mapped". */
    val mappingDraft: Map<CatalogField, String?> = emptyMap(),
    /** Headers of the worksheet being mapped, in the workbook's own order. */
    val mappingHeaders: List<String> = emptyList(),
    val mappingSheet: String? = null,
    /** Records as the draft mapping reads them — the preview before anything is applied. */
    val mappingPreview: List<CatalogEntry> = emptyList(),
    val mappingIssues: List<MappingIssue> = emptyList(),
    val mappingDirty: Boolean = false,
    /** When the saved copy was imported, from the workload library; 0 when it is not known. */
    val importedAt: Long = 0L,
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

    // ---- Insights (v2.4.0) -----------------------------------------------------------------

    /** Gallery cards as the user sees them: the shared catalog, filtered and ordered. */
    val visibleCatalog: List<CatalogEntry>
        get() = GalleryView.rows(catalog?.entries.orEmpty(), galleryFilter, gallerySort, galleryAscending)

    /** The timeline of the selected date meaning. */
    val timeline: CollectionTimeline?
        get() = when (timelineMode) {
            TimelineMode.PUBLICATION -> publicationTimeline
            TimelineMode.ACQUISITION -> acquisitionTimeline
            TimelineMode.IMPORT -> importTimeline
        }

    /** Findings left after the severity and category chips. */
    val visibleFindings: List<HealthFinding>
        get() = health?.matching(healthSeverity, healthCategory).orEmpty()

    /** The number on the Insights tab: the confirmed problems the user can act on. */
    val insightErrors: Int get() = health?.summary?.errors ?: 0

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

// ---------------------------------------------------------------------------------------------
// Source location ("go to the worksheet cell")
// ---------------------------------------------------------------------------------------------

/** The worksheet with exactly this name (the workbook's spelling, trailing spaces included), or null. */
fun WorkbookCommentsUi.sheet(name: String?): SheetInfo? =
    if (name == null) null else sheets.firstOrNull { it.name == name }

/**
 * Opens the source overlay for [location].
 *
 * A verified location is offered as its parser-proven destination(s) — one opens straight away, several
 * are listed for the user to choose. Every other case opens the overlay with an explicit explanation
 * instead of a destination, because there is nothing honest to navigate to.
 */
fun WorkbookCommentsUi.openSource(heading: String, location: SourceLocation): WorkbookCommentsUi {
    val view = when (location) {
        is SourceLocation.Verified ->
            if (location.destinations.isEmpty()) {
                SourceViewUi(heading, emptyList(), error = NO_STALE_LOCATION)
            } else {
                SourceViewUi(heading, location.destinations)
            }
        is SourceLocation.Unresolved -> SourceViewUi(
            heading = heading,
            candidates = emptyList(),
            error = "No source location: the note references an image that is not in the workbook" +
                (location.detail?.let { " ($it)" } ?: "") + ".",
        )
        SourceLocation.Unreferenced -> SourceViewUi(
            heading = heading,
            candidates = emptyList(),
            error = "No source location: nothing in this workbook's supported pipeline points at this image.",
        )
        SourceLocation.Unknown -> SourceViewUi(heading, emptyList(), error = NO_STALE_LOCATION)
    }
    return copy(source = view)
}

/** Marks [candidate] as being read; a no-op when the overlay was closed in the meantime. */
fun WorkbookCommentsUi.sourceLoading(candidate: SourceLocation.Candidate): WorkbookCommentsUi {
    val current = source ?: return this
    return copy(source = current.copy(chosen = candidate, window = null, loading = true, error = null))
}

/** Shows the cells read for [candidate]; a no-op when the overlay was closed in the meantime. */
fun WorkbookCommentsUi.sourceWindow(
    candidate: SourceLocation.Candidate,
    window: SheetWindow?,
): WorkbookCommentsUi {
    val current = source ?: return this
    return copy(
        source = current.copy(
            chosen = candidate,
            window = window,
            loading = false,
            error = if (window == null) "This worksheet could not be read, so its cells cannot be shown." else null,
        ),
    )
}

/** Reports why no cells could be shown (workbook gone, unreadable part). */
fun WorkbookCommentsUi.sourceFailed(message: String): WorkbookCommentsUi {
    val current = source ?: return this
    return copy(source = current.copy(loading = false, window = null, error = message))
}

/** Closes the overlay and leaves every list state underneath exactly as it was. */
fun WorkbookCommentsUi.closeSource(): WorkbookCommentsUi = if (source == null) this else copy(source = null)

/**
 * True when the open overlay belongs to the workbook with [sha256]. A scan that finishes for another
 * workbook must never paint its cells over this one.
 */
fun WorkbookCommentsUi.sourceBelongsTo(sha256: String): Boolean = source != null && this.sha256 == sha256

private const val NO_STALE_LOCATION =
    "No source location: the worksheet or cell this refers to is not in this workbook."


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

// ---------------------------------------------------------------------------------------------
// Insights (v2.4.0): health, gallery, analytics, timeline and column mapping
// ---------------------------------------------------------------------------------------------

/** Switches the Insights sub-tab. The catalog behind it is built once, by the controller. */
fun WorkbookCommentsUi.showInsights(tab: InsightsTab): WorkbookCommentsUi = copy(insightsTab = tab)

/**
 * The catalog and every derived result, built in one pass so the four screens cannot disagree.
 *
 * They are stored together on purpose: the health report, the gallery, the analytics and the timeline
 * all read the same records and the same image associations.
 */
fun WorkbookCommentsUi.insightsLoaded(
    catalog: WorkbookCatalog,
    health: HealthReport,
    analytics: CollectionAnalytics,
    publication: CollectionTimeline,
    acquisition: CollectionTimeline,
    imported: CollectionTimeline,
): WorkbookCommentsUi = copy(
    catalog = catalog,
    health = health,
    analytics = analytics,
    publicationTimeline = publication,
    acquisitionTimeline = acquisition,
    importTimeline = imported,
    insightsBusy = false,
    insightsError = null,
)

fun WorkbookCommentsUi.insightsStarted(): WorkbookCommentsUi = copy(insightsBusy = true, insightsError = null)

/** The build failed or there is nothing to build from; the screen says which instead of showing zeros. */
fun WorkbookCommentsUi.insightsFailed(message: String): WorkbookCommentsUi =
    copy(insightsBusy = false, insightsError = message)

/** True when a finished build still belongs to the open workbook, so a late one is dropped. */
fun WorkbookCommentsUi.insightsBelongTo(sha256: String): Boolean = this.sha256 == sha256

fun WorkbookCommentsUi.selectTimeline(mode: TimelineMode): WorkbookCommentsUi = copy(timelineMode = mode)

fun WorkbookCommentsUi.toggleTimelineOrder(): WorkbookCommentsUi = copy(timelineAscending = !timelineAscending)

fun WorkbookCommentsUi.filterHealth(
    severity: HealthSeverity?,
    category: HealthCategory? = null,
): WorkbookCommentsUi = copy(healthSeverity = severity, healthCategory = category)

fun WorkbookCommentsUi.filterGallery(filter: GalleryFilter): WorkbookCommentsUi = copy(galleryFilter = filter)

fun WorkbookCommentsUi.sortGallery(sort: GallerySort): WorkbookCommentsUi = copy(gallerySort = sort)

fun WorkbookCommentsUi.toggleGalleryOrder(): WorkbookCommentsUi = copy(galleryAscending = !galleryAscending)

/**
 * The editing state of the Fields screen: the draft mapping, the headers it was drawn against, the
 * records it reads and the issues it found. Nothing here changes the applied mapping.
 */
fun WorkbookCommentsUi.mappingEdited(
    draft: Map<CatalogField, String?>,
    sheetName: String?,
    headers: List<String>,
    preview: List<CatalogEntry>,
    issues: List<MappingIssue>,
): WorkbookCommentsUi = copy(
    mappingDraft = draft,
    mappingSheet = sheetName,
    mappingHeaders = headers,
    mappingPreview = preview,
    mappingIssues = issues,
    mappingDirty = true,
)

/** The table and record behind a catalog entry, when this workbook still has both. */
fun WorkbookCommentsUi.recordOf(entry: CatalogEntry): Pair<CatalogueTable, CatalogueRecord>? {
    val table = catalog?.tables?.get(entry.sheetName) ?: return null
    val record = table.records.firstOrNull { it.row == entry.row } ?: return null
    return table to record
}