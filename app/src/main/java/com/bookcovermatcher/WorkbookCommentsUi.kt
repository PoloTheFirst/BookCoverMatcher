// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher

import com.bookcovermatcher.core.catalog.CatalogEntry
import com.bookcovermatcher.core.catalog.CatalogField
import com.bookcovermatcher.core.catalog.CollectionAnalytics
import com.bookcovermatcher.core.catalog.CollectionTimeline
import com.bookcovermatcher.core.catalog.ColumnMappingProfile
import com.bookcovermatcher.core.catalog.ColumnMappings
import com.bookcovermatcher.core.catalog.DateColumnReport
import com.bookcovermatcher.core.catalog.DefaultOrderValue
import com.bookcovermatcher.core.catalog.DefaultOrdering
import com.bookcovermatcher.core.catalog.GalleryFilter
import com.bookcovermatcher.core.catalog.GallerySort
import com.bookcovermatcher.core.catalog.GalleryView
import com.bookcovermatcher.core.catalog.HealthCategory
import com.bookcovermatcher.core.catalog.HealthFinding
import com.bookcovermatcher.core.catalog.HealthReport
import com.bookcovermatcher.core.catalog.HealthSeverity
import com.bookcovermatcher.core.catalog.InsightsView
import com.bookcovermatcher.core.catalog.MappingEquality
import com.bookcovermatcher.core.catalog.MappingIssue
import com.bookcovermatcher.core.catalog.SheetChoice
import com.bookcovermatcher.core.catalog.SheetDateChoice
import com.bookcovermatcher.core.catalog.SheetMapping
import com.bookcovermatcher.core.catalog.SheetScope
import com.bookcovermatcher.core.catalog.entryFor
import com.bookcovermatcher.core.catalog.withSheet
import com.bookcovermatcher.core.catalog.withoutSheet
import com.bookcovermatcher.core.catalog.TimelineDateSettings
import com.bookcovermatcher.core.catalog.TimelineDates
import com.bookcovermatcher.core.catalog.TimelineFilter
import com.bookcovermatcher.core.catalog.TimelineFiltering
import com.bookcovermatcher.core.catalog.TimelineMode
import com.bookcovermatcher.core.catalog.TimelineView
import com.bookcovermatcher.core.catalog.WorkbookCatalog
import com.bookcovermatcher.core.catalog.WorkbookMapping
import com.bookcovermatcher.core.catalog.YearRange
import com.bookcovermatcher.core.catalog.sheetChoices
import com.bookcovermatcher.core.catalog.sheetKeys
import com.bookcovermatcher.core.catalog.withSheets
import com.bookcovermatcher.core.excel.comments.CatalogueField
import com.bookcovermatcher.core.excel.comments.CatalogueFilter
import com.bookcovermatcher.core.excel.comments.CatalogueFiltering
import com.bookcovermatcher.core.excel.comments.CatalogueRecord
import com.bookcovermatcher.core.excel.comments.CatalogueTable
import com.bookcovermatcher.core.excel.comments.CellLink
import com.bookcovermatcher.core.excel.comments.FilterField
import com.bookcovermatcher.core.excel.comments.ImageRow
import com.bookcovermatcher.core.excel.comments.ImageSort
import com.bookcovermatcher.core.excel.comments.ImageState
import com.bookcovermatcher.core.excel.comments.SheetCells
import com.bookcovermatcher.core.excel.comments.SheetInfo
import com.bookcovermatcher.core.excel.comments.SheetWindow
import com.bookcovermatcher.core.excel.comments.SourceLocation
import com.bookcovermatcher.core.excel.comments.WorkbookAnalysis
import com.bookcovermatcher.core.excel.comments.WorkbookCommentImages
import com.bookcovermatcher.core.excel.comments.ImageIndex

/** The two lists of the workbook preview: legacy comments, and every media file in the package. */
enum class CommentsSection { COMMENTS, MEDIA, CATALOGUE, INSIGHTS }

/**
 * How the Catalogue tab draws a worksheet.
 *
 * [CARDS] is the compact record list; [TABLE] is the worksheet itself, with every column the parser
 * found and a shared horizontal scroll, so a wide sheet (the sample `LIST` has 24 columns) can be read
 * to its right edge instead of ending at the columns a card happens to show.
 */
enum class CatalogueView(val label: String) {
    CARDS("Cards"),
    TABLE("Table"),
}


/**
 * The five v2.4.0 features that share one canonical catalog, in the order the screen lists them.
 *
 * They are sub-tabs of the existing preview instead of new top-level destinations: the workbook stays
 * open, and the filters underneath are untouched.
 */
enum class InsightsTab(val label: String) {
    HEALTH("Health"),
    ANALYTICS("Analytics"),
    GALLERY("Gallery"),
    TIMELINE("Timeline"),
    FIELDS("Fields"),
}

/**
 * Which Insights screen a worksheet-scope dialog is editing.
 *
 * The Health report, the analytics and the gallery each keep their own selection: narrowing one must not
 * silently narrow another, so the dialog remembers its target instead of a single shared scope.
 */
enum class ScopeTarget(val label: String) {
    HEALTH("Health"),
    ANALYTICS("Analytics"),
    GALLERY("Gallery"),
}

/** The open worksheet-scope dialog: its report and the selection being edited (empty = all worksheets). */
data class ScopeDialogUi(val target: ScopeTarget, val draft: Set<String>)

/** Which end of the timeline's year range a picker is editing. */
enum class TimelineYearEndpoint { FROM, TO }

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
    val timelineMode: TimelineMode = TimelineMode.PUBLICATION,
    val timelineAscending: Boolean = false,
    val healthSeverity: HealthSeverity? = null,
    val healthCategory: HealthCategory? = null,
    val galleryFilter: GalleryFilter = GalleryFilter(),
    val gallerySort: GallerySort = GallerySort.TITLE,
    val galleryAscending: Boolean = true,
    /** Saved mapping profiles of the app; empty until the store was read. */
    val profiles: List<ColumnMappingProfile> = emptyList(),
    /**
     * Which profile view is selected: null is **Detected** (the automatic detection results), a name is that
     * saved profile's configuration.
     *
     * Selecting a profile switches the configuration the Fields screen shows and the reports read. It never
     * exchanges, merges or rewrites saved profile data: Detected is computed, and each custom profile keeps its
     * own record.
     */
    val activeProfile: String? = null,
    /**
     * The mappings **applied** to individual worksheets on top of the selected configuration, by worksheet
     * identity (`SheetScope.keyOf`). This is what **Apply mapping** writes, for the worksheet on screen only.
     *
     * An override exists only where the applied mapping differs from the selected profile view (or from
     * detection); applying a mapping that changes nothing stores nothing, which is why an unchanged mapping
     * leaves the Detected status blue. Switching the profile view clears these — they are the edits of one
     * configuration, not saved data.
     */
    val sheetMappings: Map<String, SheetMapping> = emptyMap(),
    /** The mapping being edited on the Fields screen: field -> header, null meaning "not mapped". */
    val mappingDraft: Map<CatalogField, String?> = emptyMap(),
    /** Headers of the worksheet being mapped, in the workbook's own order. */
    val mappingHeaders: List<String> = emptyList(),
    val mappingSheet: String? = null,
    /** Records as the draft mapping reads them — the preview before anything is applied. */
    val mappingPreview: List<CatalogEntry> = emptyList(),
    val mappingIssues: List<MappingIssue> = emptyList(),
    val mappingDirty: Boolean = false,
    /** Catalogue tab: compact record cards, or the whole worksheet table with every column. */
    val catalogueView: CatalogueView = CatalogueView.CARDS,
    /** The date columns the user chose for this workbook, per worksheet. Never assigned automatically. */
    val dateSettings: TimelineDateSettings = TimelineDateSettings(),
    /** The "Choose date columns" dialog: open state, worksheet, the two drafts, and the slot being chosen. */
    val dateSetupOpen: Boolean = false,
    val dateSetupSheet: String? = null,
    val dateSetupPublished: String? = null,
    val dateSetupAcquired: String? = null,
    val dateSetupTarget: TimelineMode = TimelineMode.PUBLICATION,
    /** The timeline filter of each date meaning, so a search in Published cannot filter Acquired. */
    val timelineFilters: Map<TimelineMode, TimelineFilter> = emptyMap(),
    /** The open year picker of the timeline range; null when it is closed. */
    val timelineYearPicker: TimelineYearEndpoint? = null,
    /** Worksheets the Health report covers; empty = every worksheet. */
    val healthScope: SheetScope = SheetScope.ALL,
    /** Worksheets the analytics cover; independent of [healthScope] on purpose. */
    val analyticsScope: SheetScope = SheetScope.ALL,
    /** The open worksheet-scope dialog, with the selection being edited. */
    val scopeDialog: ScopeDialogUi? = null,
    /** The open cell-value dialog of the Catalogue table (Copy / Open Link / Back). */
    val cellPeek: CellPeekUi? = null,
    /**
     * When the saved copy of this workbook was imported, from the saved-workbook library; 0 when unknown.
     *
     * Kept as workbook metadata only: it is no longer a Timeline mode, so the value is shown/handled by the
     * library and is never turned into a timeline of its own.
     */
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

    /** The gallery's own worksheet selection, the same object its filter reads and writes. */
    val galleryScope: SheetScope get() = galleryFilter.sheets

    /** True when the Insights directory is the section on screen. */
    val wantsInsights: Boolean get() = section == CommentsSection.INSIGHTS

    /**
     * The state a rescan starts from: the parsed data is dropped, the user's own choices are not.
     *
     * Profiles, the mapping draft, the three worksheet scopes, the gallery and timeline filters, the chosen
     * date columns and the selected section all survive, so a rescan cannot undo a preference. Only the
     * results that came from the old bytes are cleared — and when the Insights directory is on screen the
     * busy flag is raised at once, so the panels say "reading" instead of showing an empty report.
     */
    fun reloadingForRescan(): WorkbookCommentsUi = copy(
        loading = true,
        error = null,
        source = null,
        selectedCell = null,
        selectedSheetName = null,
        viewer = null,
        catalog = null,
        health = null,
        analytics = null,
        publicationTimeline = null,
        acquisitionTimeline = null,
        insightsError = null,
        insightsBusy = wantsInsights,
        mappingPreview = emptyList(),
        mappingIssues = emptyList(),
    )

    /**
     * A finished scan or rescan: the new bytes are in, and the reports are about to be rebuilt.
     *
     * A build that is already running in another section keeps its busy state; the section that is open is
     * the one whose panels must show that a rebuild is under way.
     */
    fun analysisLoaded(
        model: WorkbookCommentImages,
        analysis: WorkbookAnalysis?,
    ): WorkbookCommentsUi = copy(
        loading = false,
        error = null,
        model = model,
        analysis = analysis,
        insightsBusy = if (wantsInsights) true else insightsBusy,
    )

    /**
     * A failed scan or rescan: the previous results are gone, the reason is on screen, and no rebuild is
     * pending — an error must never leave the panels stuck in their "reading" state.
     */
    fun reloadFailed(message: String): WorkbookCommentsUi = copy(
        loading = false,
        error = message,
        insightsBusy = false,
    )

    /** Gallery cards as the user sees them: the shared catalog, filtered and ordered. */
    val visibleCatalog: List<CatalogEntry>
        get() = GalleryView.rows(catalog?.entries.orEmpty(), galleryFilter, gallerySort, galleryAscending)

    /** The header the draft mapping uses for the Default Order column, or null when it is unset. */
    val defaultOrderHeader: String? get() = mappingDraft[CatalogField.DEFAULT_ORDER]

    /** The worksheet table the Fields screen is editing, when the analysis found one. */
    val mappingTable: CatalogueTable?
        get() = analysis?.tables?.firstOrNull { it.sheetName == mappingSheet }

    /** The identity of the worksheet being edited (`SheetScope.keyOf`), the key its mapping is stored under. */
    val mappingSheetKey: String? get() = sheetKeyOf(mappingSheet)

    /** The identity of [sheetName] in this workbook, or null when no worksheet has that name. */
    fun sheetKeyOf(sheetName: String?): String? {
        val name = sheetName ?: return null
        val path = analysis?.sheets?.firstOrNull { it.name == name }?.partPath.orEmpty()
        return SheetScope.keyOf(path, name)
    }

    /**
     * The mapping applied to [sheetName] on top of the selected profile view, or null when it has none.
     *
     * Never a fallback to the worksheet on screen: a worksheet with no entry is read with the selected profile's
     * entry (or its own detection), so one worksheet's applied columns cannot become another's.
     */
    fun sheetMappingOf(sheetName: String?): SheetMapping? = sheetMappings.entryFor(sheetKeyOf(sheetName))

    /** The saved profile with [name], or null when the name is Detected (or no longer exists). */
    fun profileOf(name: String? = activeProfile): ColumnMappingProfile? =
        profiles.firstOrNull { it.name == name }

    /** The saved profile currently selected, or null for the **Detected** view. */
    val selectedProfile: ColumnMappingProfile? get() = profileOf(activeProfile)

    /** True when the Fields screen is showing the Detected view (no custom profile selected). */
    val isDetectedView: Boolean get() = selectedProfile == null

    /** The columns detection would choose for the inspected worksheet, from its own header row. */
    val detectedMappingColumns: Map<CatalogField, String>
        get() = mappingTable?.let { ColumnMappings.detectedProfile(it).columns }.orEmpty()

    /**
     * The columns **in effect** for [sheetName]: what was applied to it, else the selected profile's entry for it,
     * else its own detected headers.
     *
     * This is the only definition of "the mapping" the screen and the reports use, and every layer is keyed by the
     * worksheet, so two worksheets can never borrow each other's columns.
     */
    fun activeColumnsFor(sheetName: String?): Map<CatalogField, String> {
        sheetMappingOf(sheetName)?.let { return it.columns }
        selectedProfile?.columnsFor(sheetKeyOf(sheetName))?.let { return it }
        return detectedColumns(sheetName)
    }

    /** The columns the worksheet reads when nothing is applied on top of it: the view's entry, else detection. */
    fun baselineColumnsFor(sheetName: String?): Map<CatalogField, String> =
        selectedProfile?.columnsFor(sheetKeyOf(sheetName)) ?: detectedColumns(sheetName)

    /** The columns detection would choose for [sheetName], from its own header row. */
    fun detectedColumns(sheetName: String?): Map<CatalogField, String> {
        val table = analysis?.tables?.firstOrNull { it.sheetName == sheetName } ?: return emptyMap()
        return ColumnMappings.detectedProfile(table).columns
    }

    /** The columns in effect for the worksheet being inspected. */
    val effectiveMappingColumns: Map<CatalogField, String>
        get() = activeColumnsFor(mappingSheet)

    /** The mapping [table] is read with: what was applied to it, else the selected profile's, else its detection. */
    fun mappingFor(table: CatalogueTable): ColumnMappingProfile =
        sheetMappingOf(table.sheetName)?.asProfile()
            ?: selectedProfile?.columnsFor(sheetKeyOf(table.sheetName))
                ?.let { ColumnMappingProfile(selectedProfile?.name.orEmpty(), columns = it) }
            ?: ColumnMappings.detectedProfile(table)

    /**
     * The header automatic detection would choose for [field] on the worksheet being mapped, or null.
     *
     * Read through the same [ColumnMappings.detectedProfile] the reports use, so the Fields screen's "use the
     * detected column" action and the reports' own detection can never disagree.
     */
    fun detectedHeader(field: CatalogField): String? = detectedMappingColumns[field]

    /**
     * True when the draft's Default Order column is the one detection would pick.
     *
     * The Fields screen uses this to offer "use the detected column" only when there is something to go back to,
     * instead of showing an action that would change nothing.
     */
    val defaultOrderIsDetected: Boolean
        get() = defaultOrderHeader == detectedHeader(CatalogField.DEFAULT_ORDER)

    /**
     * True when the mapping in effect for the **worksheet being inspected** is the one detection would choose.
     *
     * This is a statement about the mapping, not about what the user clicked: an applied mapping whose assignments
     * equal the detected ones is *detected*, so **Apply mapping** on an unchanged mapping leaves the status blue,
     * and restoring the detected assignments brings the blue back. Comparison is [MappingEquality]'s — semantic,
     * blank-tolerant and header-normalized — so a different object representing the same assignments still counts
     * as detected.
     */
    val isDetectedMapping: Boolean
        get() = MappingEquality.sameColumns(effectiveMappingColumns, detectedMappingColumns)

    /**
     * The mapping the reports read the worksheet being inspected with, or null when it is its detected headers.
     *
     * A view (Detected or a profile) whose columns mean exactly what detection would choose is detection: there is
     * nothing "applied", which is why an unchanged mapping leaves the status blue.
     */
    fun appliedProfile(): ColumnMappingProfile? {
        if (isDetectedMapping) return null
        val table = mappingTable ?: return null
        return mappingFor(table)
    }

    /** How the Fields screen describes what is in effect, so the selected view and an applied edit never look alike. */
    val mappingSourceLabel: String
        get() = when {
            sheetMappingOf(mappingSheet) != null && selectedProfile == null ->
                "Manual mapping, not saved as a profile"
            selectedProfile != null -> "Saved profile \u201C${selectedProfile?.name}\u201D"
            else -> "Detected from this workbook's headers"
        }

    /**
     * The Default Order values of the mapping preview, in the draft's own mapping.
     *
     * This is what the Fields screen shows before anything is applied: the workbook's own text, a number
     * when the whole cell is one, and a blank when the cell is blank — nothing is invented.
     */
    val defaultOrderValues: List<DefaultOrderValue>
        get() = mappingPreview.map { DefaultOrdering.of(it) }

    /**
     * The records the worksheet selection alone allows — the denominator of the gallery's count line, so
     * "12 of 40" never counts cards from a worksheet the user excluded.
     */
    val galleryEligible: List<CatalogEntry>
        get() {
            val scope = galleryFilter.sheets
            return catalog?.entries.orEmpty().filter { scope.allows(it.sheetPath, it.sheetName) }
        }

    /** The timeline of the selected date meaning, with its filter applied (what the screen shows). */
    val timeline: CollectionTimeline? get() = timelineView?.timeline

    /** The timeline as it was built, before the filter — the denominator of "x of y". */
    val unfilteredTimeline: CollectionTimeline? get() = timelineOf(timelineMode)

    /** The timeline of one meaning with its own filter applied. */
    val timelineView: TimelineView?
        get() = timelineOf(timelineMode)?.let { TimelineFiltering.apply(it, currentTimelineFilter) }

    /** The filter of the selected meaning, or an empty one. */
    val currentTimelineFilter: TimelineFilter get() = timelineFilter(timelineMode)

    /** The filter kept for [mode] — each date meaning has its own. */
    fun timelineFilter(mode: TimelineMode): TimelineFilter = timelineFilters[mode] ?: TimelineFilter()

    /** Years that actually exist on the selected timeline, newest first: the range a picker offers. */
    val timelineYears: List<Int> get() = timelineOf(timelineMode)?.sections?.map { it.year }.orEmpty()

    /**
     * Identity of what the timeline is showing. The list watches this and returns to the top when the
     * meaning, the ordering or the filter changes — never for an unrelated recomposition.
     */
    val timelineOrderKey: String
        get() = listOf(
            catalog?.workbookSha256.orEmpty(),
            timelineMode.name,
            if (timelineAscending) "asc" else "desc",
            currentTimelineFilter.key,
        ).joinToString("|")

    /** Selectable worksheets of the open workbook, in workbook order. */
    val sheetChoices: List<SheetChoice> get() = catalog?.sheetChoices.orEmpty()

    /** `All worksheets`, `LIST`, or `2 of 3 worksheets` — a summary that fits a filter bar. */
    fun scopeLabel(scope: SheetScope): String {
        if (scope.isAll) return "All worksheets"
        val names = sheetChoices.filter { it.key in scope.keys }.map { it.name }
        return when (names.size) {
            0 -> "No worksheet"
            1 -> names.single()
            else -> "${names.size} of ${sheetChoices.size} worksheets"
        }
    }

    /** The scope of [target], so all three screens read one accessor. */
    fun scopeOf(target: ScopeTarget): SheetScope = when (target) {
        ScopeTarget.HEALTH -> healthScope
        ScopeTarget.ANALYTICS -> analyticsScope
        ScopeTarget.GALLERY -> galleryFilter.sheets
    }

    /**
     * The count the Insights directory shows.
     *
     * It is never hidden — a zero is `0 errors`, an unbuilt report is `—`, a running build is `reading…` —
     * so the same directory entry cannot look count-less just because the spreadsheet was (re)opened.
     */
    val insightCountLabel: String
        get() {
            val report = health
            return when {
                report != null -> {
                    val errors = report.summary.errors
                    if (errors == 1) "1 error" else "$errors errors"
                }
                insightsBusy -> "reading\u2026"
                insightsError != null -> "not available"
                else -> "\u2014"
            }
        }

    /** Any of the two timelines by meaning, whether or not it is the one on screen. */
    fun timelineOf(mode: TimelineMode): CollectionTimeline? = when (mode) {
        TimelineMode.PUBLICATION -> publicationTimeline
        TimelineMode.ACQUISITION -> acquisitionTimeline
    }

    /** Findings left after the severity and category chips. */
    val visibleFindings: List<HealthFinding>
        get() = health?.matching(healthSeverity, healthCategory).orEmpty()

    /** The number on the Insights tab: the confirmed problems the user can act on. */
    val insightErrors: Int get() = health?.summary?.errors ?: 0

    /** The worksheet the "Choose date columns" dialog is configuring, when it is open. */
    val dateSetupTable: CatalogueTable?
        get() = catalog?.tables?.let { tables -> dateSetupSheet?.let { tables[it] } ?: tables.values.firstOrNull() }

    /** Every column of that worksheet, in workbook order — the list the dialog offers. */
    val dateSetupColumns: List<CatalogueField> get() = dateSetupTable?.fields.orEmpty()

    /**
     * The state of one of the dialog's two drafts: how many values of the chosen column can be read.
     *
     * Computed from the worksheet the dialog is on, so switching worksheet switches the evidence too.
     */
    fun dateSetupReport(mode: TimelineMode): DateColumnReport? {
        val table = dateSetupTable ?: return null
        val header = if (mode == TimelineMode.PUBLICATION) dateSetupPublished else dateSetupAcquired
        return TimelineDates.inspect(table, mode, header, analysis?.date1904 ?: false)
    }

    /** The choice already saved for this workbook and worksheet (before any draft is applied). */
    val dateSetupSavedChoice: SheetDateChoice
        get() = dateSetupSheet?.let { dateSettings.choiceOf(it) } ?: SheetDateChoice()

    /**
     * Identity of what the gallery is showing, in the order it is showing it.
     *
     * The grid watches this: when it changes the scroll goes back to the top, because the first card of a
     * new order is the thing to look at. Anything else (a thumbnail finishing, a dialog opening, a health
     * finding expanding) leaves it alone, so the user is never moved while reading.
     */
    val galleryOrderKey: String
        get() = listOf(
            catalog?.workbookSha256.orEmpty(),
            gallerySort.name,
            if (galleryAscending) "asc" else "desc",
            galleryFilter.sheets.key,
            galleryFilter.query.trim(),
            if (galleryFilter.onlyWithCover) "with" else "",
            if (galleryFilter.onlyWithoutCover) "without" else "",
            if (galleryFilter.onlyMissingTitle) "notitle" else "",
            if (galleryFilter.onlyMissingMetadata) "nometa" else "",
        ).joinToString("|")

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


/**
 * The cell-value dialog of the Catalogue table.
 *
 * [sheetName], [row] and [column] are the **source** identity of the cell, so the value, the hyperlink and
 * the navigation all describe the same workbook cell — never whatever sits at that screen position after a
 * sort, a filter or a scroll.
 */
data class CellPeekUi(
    val sheetName: String,
    val row: Int,
    val column: Int,
    val columnLetter: String,
    val fieldLabel: String,
    /** The cell's displayed value: what the table shows, which is what Copy puts on the clipboard. */
    val value: String,
    /** The workbook's own hyperlink for this cell, or null when the cell has none. */
    val link: CellLink? = null,
) {
    /** `D128` — the cell reference inside its worksheet. */
    val cell: String get() = SheetCells.cellRef(row, column)

    /** `LIST!D128` — the location as a person reads it. */
    val locationLabel: String get() = "$sheetName!$cell"

    val isBlank: Boolean get() = value.isEmpty()

    /** Only a link the workbook really stores and the app can act on offers "Open Link". */
    val canOpenLink: Boolean get() = link?.isOpenable == true
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
): WorkbookCommentsUi {
    // A stored worksheet selection is reconciled against what this workbook really has, so a renamed or
    // deleted sheet cannot leave a report filtered down to nothing.
    val keys = catalog.sheetKeys
    return copy(
        catalog = catalog,
        health = health,
        analytics = analytics,
        publicationTimeline = publication,
        acquisitionTimeline = acquisition,
        healthScope = healthScope.reconciled(keys),
        analyticsScope = analyticsScope.reconciled(keys),
        galleryFilter = galleryFilter.copy(sheets = galleryFilter.sheets.reconciled(keys)),
        insightsBusy = false,
        insightsError = null,
    )
}

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

// ---------------------------------------------------------------------------------------------
// Catalogue view (v2.4.0-dev.3): compact cards, or the whole worksheet with every column
// ---------------------------------------------------------------------------------------------

/** Switches the Catalogue tab between the compact cards and the full worksheet table. */
fun WorkbookCommentsUi.showCatalogueView(view: CatalogueView): WorkbookCommentsUi =
    if (catalogueView == view) this else copy(catalogueView = view)

// ---------------------------------------------------------------------------------------------
// Date columns (v2.4.0-dev.3): Published and Acquired are chosen by the user, never guessed
// ---------------------------------------------------------------------------------------------

/** Loads the choices stored for the open workbook. */
fun WorkbookCommentsUi.dateSettingsLoaded(settings: TimelineDateSettings): WorkbookCommentsUi =
    copy(dateSettings = settings)

/**
 * Opens the date-column dialog on [sheetName] (or the last one, or the first worksheet) with the saved
 * choice as the draft.
 *
 * No header is ever pre-selected by name: an unconfigured workbook opens with both slots empty, even when
 * a column is called `出版日付`.
 */
fun WorkbookCommentsUi.openDateSetup(sheetName: String? = null): WorkbookCommentsUi {
    val tables = catalog?.tables.orEmpty()
    val target = listOfNotNull(sheetName, dateSetupSheet).firstOrNull { tables.containsKey(it) }
        ?: tables.keys.firstOrNull()
        ?: return this
    val choice = dateSettings.choiceOf(target)
    return copy(
        dateSetupOpen = true,
        dateSetupSheet = target,
        dateSetupPublished = choice.publishedHeader,
        dateSetupAcquired = choice.acquiredHeader,
        dateSetupTarget = TimelineMode.PUBLICATION,
    )
}

fun WorkbookCommentsUi.closeDateSetup(): WorkbookCommentsUi =
    if (!dateSetupOpen) this else copy(dateSetupOpen = false)

/** Switches the dialog to another worksheet; the drafts become that worksheet's own saved choice. */
fun WorkbookCommentsUi.dateSetupOn(sheetName: String): WorkbookCommentsUi {
    val tables = catalog?.tables.orEmpty()
    if (!dateSetupOpen || !tables.containsKey(sheetName)) return this
    val choice = dateSettings.choiceOf(sheetName)
    return copy(
        dateSetupSheet = sheetName,
        dateSetupPublished = choice.publishedHeader,
        dateSetupAcquired = choice.acquiredHeader,
    )
}

/** Which timeline the next column tap assigns: Published or Acquired. */
fun WorkbookCommentsUi.dateSetupAssignTo(mode: TimelineMode): WorkbookCommentsUi =
    if (!dateSetupOpen) this else copy(dateSetupTarget = mode)

/** Puts [header] (null = none) in the open slot. Nothing is saved until [dateSetupCommitted]. */
fun WorkbookCommentsUi.dateSetupAssign(header: String?): WorkbookCommentsUi {
    if (!dateSetupOpen) return this
    val cleaned = header?.trim()?.takeIf { it.isNotEmpty() }
    return when (dateSetupTarget) {
        TimelineMode.PUBLICATION -> copy(dateSetupPublished = cleaned)
        TimelineMode.ACQUISITION -> copy(dateSetupAcquired = cleaned)
    }
}

/** Empties both slots of the open worksheet in the dialog (still not saved). */
fun WorkbookCommentsUi.dateSetupCleared(): WorkbookCommentsUi =
    if (!dateSetupOpen) this else copy(dateSetupPublished = null, dateSetupAcquired = null)

/**
 * Restores what this workbook was last left with: the three worksheet selections and each timeline's year
 * range.
 *
 * Nothing is invented — a workbook with no saved entry keeps the defaults (all worksheets, no year range) —
 * and the saved worksheet keys are reconciled against this workbook's real sheets, so a selection for a
 * sheet that has been renamed away cannot leave a screen filtered down to nothing.
 */
fun WorkbookCommentsUi.restoredView(view: InsightsView): WorkbookCommentsUi {
    val keys = catalog?.sheetKeys.orEmpty()
    val health = view.health.let { if (keys.isEmpty()) it else it.reconciled(keys) }
    val analytics = view.analytics.let { if (keys.isEmpty()) it else it.reconciled(keys) }
    val gallery = view.gallery.let { if (keys.isEmpty()) it else it.reconciled(keys) }
    var filters = timelineFilters
    for (mode in TimelineMode.entries) {
        val range = view.yearsOf(mode)
        if (range.isActive) {
            filters = filters + (mode to TimelineFilter(fromYear = range.fromYear, toYear = range.toYear))
        }

    }
    return copy(
        healthScope = health,
        analyticsScope = analytics,
        galleryFilter = galleryFilter.withSheets(gallery),
        timelineFilters = filters,
    )
}

/**
 * The selections worth remembering for this workbook: the three worksheet scopes and the year range of each
 * date meaning. The search text is deliberately **not** stored — it is a query, not a view setting.
 */
fun WorkbookCommentsUi.persistableView(): InsightsView = InsightsView(
    workbookSha256 = sha256,
    health = healthScope,
    analytics = analyticsScope,
    gallery = galleryFilter.sheets,
    years = TimelineMode.entries.mapNotNull { mode ->
        val filter = timelineFilters[mode] ?: return@mapNotNull null
        val range = YearRange(filter.fromYear, filter.toYear)
        if (range.isActive) mode to range else null
    }.toMap(),
)

/** Writes the two drafts into [dateSettings] and closes; the caller persists and rebuilds. */
fun WorkbookCommentsUi.dateSetupCommitted(): WorkbookCommentsUi {
    if (!dateSetupOpen) return this
    val sheet = dateSetupSheet ?: return copy(dateSetupOpen = false)
    return copy(
        dateSettings = dateSettings.withChoice(sheet, SheetDateChoice(dateSetupPublished, dateSetupAcquired)),
        dateSetupOpen = false,
    )
}

// ---------------------------------------------------------------------------------------------
// Timeline filters and worksheet scopes (v2.4.0-dev.4)
// ---------------------------------------------------------------------------------------------

/** Stores [filter] for [mode] (default: the mode on screen); the other meaning keeps its own. */
fun WorkbookCommentsUi.filterTimeline(
    filter: TimelineFilter,
    mode: TimelineMode = timelineMode,
): WorkbookCommentsUi = copy(timelineFilters = timelineFilters + (mode to filter))

/** Removes the filter of [mode], restoring the whole eligible timeline for that meaning. */
fun WorkbookCommentsUi.clearTimelineFilter(mode: TimelineMode = timelineMode): WorkbookCommentsUi =
    if (mode !in timelineFilters) this else copy(timelineFilters = timelineFilters - mode)

/** Opens (or closes, with null) the year picker of the current timeline filter. */
fun WorkbookCommentsUi.timelineYearPicker(endpoint: TimelineYearEndpoint?): WorkbookCommentsUi =
    copy(timelineYearPicker = endpoint)

/** Sets one end of the current meaning's year range; null clears that bound, and the picker closes. */
fun WorkbookCommentsUi.pickTimelineYear(endpoint: TimelineYearEndpoint, year: Int?): WorkbookCommentsUi {
    val filter = currentTimelineFilter
    val next = when (endpoint) {
        TimelineYearEndpoint.FROM -> filter.withRange(from = year, to = filter.toYear)
        TimelineYearEndpoint.TO -> filter.withRange(from = filter.fromYear, to = year)
    }
    return copy(
        timelineFilters = timelineFilters + (timelineMode to next),
        timelineYearPicker = null,
    )
}

/**
 * Opens the cell dialog for one table cell.
 *
 * The identity comes from the record and the field (worksheet, source row, source column), so the value and
 * the link always belong to the same workbook cell whatever the table is currently sorted or filtered by.
 */
fun WorkbookCommentsUi.peekCell(
    sheetName: String,
    record: CatalogueRecord,
    field: CatalogueField,
): WorkbookCommentsUi = copy(
    cellPeek = CellPeekUi(
        sheetName = sheetName,
        row = record.row,
        column = field.column,
        columnLetter = field.letter,
        fieldLabel = field.label,
        value = record.display(field),
        link = record.linkOf(field),
    ),
)

/** Closes the cell dialog. */
fun WorkbookCommentsUi.closeCellPeek(): WorkbookCommentsUi =
    if (cellPeek == null) this else copy(cellPeek = null)

/**
 * Opens the same cell dialog from the **Source location** preview.
 *
 * The identity comes from the source row and column that were drawn, and everything shown comes from
 * [SheetWindow.cellAt] — the value as the workbook displays it, and that cell's own hyperlink. A cell the
 * window does not draw opens nothing rather than a dialog about a neighbour.
 */
fun WorkbookCommentsUi.peekSourceCell(window: SheetWindow, row: Int, column: Int): WorkbookCommentsUi {
    val cell = window.cellAt(row, column) ?: return this
    val letter = CatalogueFiltering.columnLetter(column)
    return copy(
        cellPeek = CellPeekUi(
            sheetName = window.sheetName,
            row = row,
            column = column,
            columnLetter = letter,
            // A worksheet with a header row labels the column by its own header; a headerless sheet is named
            // by its letter, so the dialog never claims a field the workbook does not have.
            fieldLabel = window.headerOf(column) ?: "Cell $letter",
            value = cell.shown,
            link = cell.link,
        ),
    )
}

/**
 * The mapping draft with one field pointing at [header] (null = not mapped).
 *
 * This is the picker's single edit rule, so the Fields screen cannot change a column one way for a typed
 * field and another way for Default order. The result keeps the draft's order, and nothing is written to the
 * workbook or to a profile until the user applies or saves it.
 */
fun Map<CatalogField, String?>.withField(field: CatalogField, header: String?): Map<CatalogField, String?> {
    val next = LinkedHashMap(this)
    next[field] = header
    return next
}

/**
 * Applies [columns] to the **worksheet being inspected** — and to no other.
 *
 * The comparison is against what that worksheet reads **without** the edit ([baselineColumnsFor]: the
 * selected profile's entry, or its detected headers). When the applied columns mean the same thing, no edit is
 * stored at all, so applying an unchanged mapping changes nothing (the Detected status stays blue and nothing is
 * written); restoring the detected assignments this way removes the edit again.
 *
 * [profileName] is only a record of where the columns came from; the profile itself is never modified here.
 */
fun WorkbookCommentsUi.applySheetMapping(
    columns: Map<CatalogField, String>,
    profileName: String? = null,
): WorkbookCommentsUi {
    val key = mappingSheetKey ?: return this
    val assigned = columns.filterValues { it.isNotBlank() }
    if (assigned.isEmpty()) return clearSheetMapping()
    val next = if (MappingEquality.sameColumns(assigned, baselineColumnsFor(mappingSheet))) {
        sheetMappings.withoutSheet(key)
    } else {
        sheetMappings.withSheet(key, SheetMapping(profileName, assigned))
    }
    return if (next == sheetMappings) copy(mappingDirty = false) else copy(sheetMappings = next, mappingDirty = false)
}

/**
 * Switches the profile **view**: null shows the Detected results, a name shows that saved profile's configuration.
 *
 * This only changes which configuration is displayed and read; it does not exchange, merge or rewrite any saved
 * profile. The applied edits of the previous view are dropped with it — they were edits *of that configuration*,
 * not saved data — so the newly selected view is displayed exactly as it was saved. A name this workbook no longer
 * has falls back to Detected, which is the honest state when a profile was deleted elsewhere.
 */
fun WorkbookCommentsUi.selectProfileView(name: String?): WorkbookCommentsUi {
    val selected = profileOf(name)?.name
    if (selected == activeProfile && sheetMappings.isEmpty()) return this
    return copy(activeProfile = selected, sheetMappings = emptyMap(), mappingDirty = false)
}

/**
 * The snapshot a new profile would save under [name], built from the configuration currently in effect.
 *
 * Every worksheet gets **its own** entry: the one being edited takes the draft (the user's intended changes), and
 * every other worksheet keeps the mapping it already has — the selected profile's entry, the columns applied to it,
 * or its detected headers. Nothing is inherited from *the worksheet on screen*: that worksheet is never a template
 * for the others, and an empty value stays where it is.
 *
 * A source profile that predates per-worksheet mappings (one workbook-wide column set) is narrowed to the headers
 * each worksheet really has, so deriving from it cannot give a worksheet another worksheet's columns.
 * Returns null when no worksheet has anything to save.
 */
fun WorkbookCommentsUi.profileSnapshot(
    name: String,
    tables: List<CatalogueTable> = analysis?.tables.orEmpty(),
): ColumnMappingProfile? {
    val source = selectedProfile
    val sheets = LinkedHashMap<String, Map<CatalogField, String>>()
    for (table in tables) {
        val key = sheetKeyOf(table.sheetName) ?: continue
        val columns = if (table.sheetName == mappingSheet) {
            // The worksheet being edited: what the user has actually assigned there.
            mappingDraft.filterValues { !it.isNullOrBlank() }.mapValues { it.value!! }
        } else {
            val applied = sheetMappingOf(table.sheetName)?.columns
            val fromProfile = source?.columnsFor(key)?.let { columns ->
                // A schema-1 profile's single column set is narrowed to what this worksheet really has.
                if (key in source.sheets) columns else narrowedColumns(columns, table)
            }
            applied ?: fromProfile ?: ColumnMappings.detectedProfile(table).columns
        }
        val assigned = columns.filterValues { it.isNotBlank() }
        if (assigned.isNotEmpty()) sheets[key] = assigned
    }
    if (sheets.isEmpty()) return null
    return ColumnMappingProfile(name = name, sheets = sheets)
}

/** A column set reduced to the columns [table] really has (used for a profile that names no worksheet). */
private fun narrowedColumns(columns: Map<CatalogField, String>, table: CatalogueTable): Map<CatalogField, String> {
    val present = table.fields.map { ColumnMappings.normalize(it.name) }.toSet()
    return columns.filterValues { ColumnMappings.normalize(it) in present }
}

/** Returns [sheetName] (default: the inspected worksheet) to its detected headers; other worksheets are untouched. */
fun WorkbookCommentsUi.clearSheetMapping(sheetName: String? = mappingSheet): WorkbookCommentsUi {
    val key = sheetKeyOf(sheetName) ?: return this
    val next = sheetMappings.withoutSheet(key)
    return if (next == sheetMappings) this else copy(sheetMappings = next, mappingDirty = false)
}

/**
 * The state worth remembering for this workbook: which profile view is selected and the edits applied on top of it.
 *
 * The profiles themselves are stored separately (they are the user's saved data); this is only what this workbook
 * was last looking at. Anything unapplied stays in the draft and is deliberately **not** stored, and a worksheet
 * whose applied mapping means the same as the selected view stores nothing at all.
 */
fun WorkbookCommentsUi.persistableMapping(): WorkbookMapping = WorkbookMapping(
    workbookSha256 = sha256,
    activeProfile = selectedProfile?.name,
    sheets = sheetMappings.filterValues { !it.isEmpty },
)

/**
 * Restores the profile view and the applied edits this workbook was last left with.
 *
 * Saved profile data is **not** restored here — the profiles come from their own store, and this function only
 * selects one of them and replays the per-worksheet edits. A name whose profile has been deleted falls back to the
 * Detected view instead of showing a configuration that no longer exists.
 *
 * A **v1** file (one column set for the whole workbook, from before mappings were per worksheet) is migrated here,
 * where the worksheets' real headers are known: each of its columns goes to the worksheets that actually have that
 * header, and every other worksheet keeps its own detection.
 */
fun WorkbookCommentsUi.restoredMapping(
    mapping: WorkbookMapping,
    tables: List<CatalogueTable> = analysis?.tables.orEmpty(),
): WorkbookCommentsUi {
    if (mapping.isEmpty) return this
    val restored = LinkedHashMap<String, SheetMapping>()
    for ((key, entry) in mapping.sheets) {
        if (entry.isEmpty) continue
        val name = entry.profileName?.let { profileName -> profiles.firstOrNull { it.name == profileName }?.name }
        restored[key] = name?.let { SheetMapping(it, entry.columns) } ?: entry.detached()
    }
    val legacy = mapping.legacy
    if (legacy != null && restored.isEmpty()) {
        for (table in tables) {
            val key = sheetKeyOf(table.sheetName) ?: continue
            // Only the columns this worksheet really has: the rest belong to some other worksheet's header row.
            val present = table.fields.map { ColumnMappings.normalize(it.name) }.toSet()
            val kept = legacy.columns.filterValues { ColumnMappings.normalize(it) in present }
            if (kept.isNotEmpty()) restored[key] = SheetMapping(profileName = null, columns = kept)
        }
    }
    // An entry that means exactly what the worksheet already reads is not an edit; it must not become a grey chip.
    val meaningful = restored.filter { (key, entry) ->
        val table = tables.firstOrNull { sheetKeyOf(it.sheetName) == key }
        val baseline = profileOf(mapping.activeProfile)?.columnsFor(key)
            ?: table?.let { ColumnMappings.detectedProfile(it).columns }.orEmpty()
        !MappingEquality.sameColumns(entry.columns, baseline)
    }
    return copy(
        activeProfile = profileOf(mapping.activeProfile)?.name,
        sheetMappings = meaningful,
        mappingDirty = false,
    )
}


/**
 * Opens the worksheet-scope dialog for [target] with that report's current selection.
 *
 * Health and the analytics never share this state: opening one cannot change the other.
 */
fun WorkbookCommentsUi.openScopeDialog(target: ScopeTarget): WorkbookCommentsUi =
    copy(scopeDialog = ScopeDialogUi(target, scopeOf(target).keys))

fun WorkbookCommentsUi.toggleScopeSheet(key: String): WorkbookCommentsUi {
    val dialog = scopeDialog ?: return this
    val draft = if (key in dialog.draft) dialog.draft - key else dialog.draft + key
    return copy(scopeDialog = dialog.copy(draft = draft))
}

/** "All worksheets": an empty draft, which is the unfiltered report. */
fun WorkbookCommentsUi.scopeSelectAll(): WorkbookCommentsUi =
    scopeDialog?.let { copy(scopeDialog = it.copy(draft = emptySet())) } ?: this

fun WorkbookCommentsUi.closeScopeDialog(): WorkbookCommentsUi =
    if (scopeDialog == null) this else copy(scopeDialog = null)

/** Commits the dialog's selection to its own screen and closes it. */
fun WorkbookCommentsUi.scopeCommitted(): WorkbookCommentsUi {
    val dialog = scopeDialog ?: return this
    val scope = SheetScope(dialog.draft)
    return when (dialog.target) {
        ScopeTarget.HEALTH -> copy(healthScope = scope, scopeDialog = null)
        ScopeTarget.ANALYTICS -> copy(analyticsScope = scope, scopeDialog = null)
        // The gallery's selection lives in its filter, so the picker and the filter bar cannot disagree.
        ScopeTarget.GALLERY -> copy(galleryFilter = galleryFilter.withSheets(scope), scopeDialog = null)
    }
}
