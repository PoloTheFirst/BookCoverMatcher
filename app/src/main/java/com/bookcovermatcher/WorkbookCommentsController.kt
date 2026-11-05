package com.bookcovermatcher

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.bookcovermatcher.core.catalog.CatalogEntry
import com.bookcovermatcher.core.catalog.CatalogField
import com.bookcovermatcher.core.catalog.CollectionAnalytics
import com.bookcovermatcher.core.catalog.CollectionAnalyticsBuilder
import com.bookcovermatcher.core.catalog.CollectionTimeline
import com.bookcovermatcher.core.catalog.CollectionTimelineBuilder
import com.bookcovermatcher.core.catalog.ColumnMappingProfile
import com.bookcovermatcher.core.catalog.ColumnMappings
import com.bookcovermatcher.core.catalog.GalleryFilter
import com.bookcovermatcher.core.catalog.GallerySort
import com.bookcovermatcher.core.catalog.HealthCategory
import com.bookcovermatcher.core.catalog.HealthFinding
import com.bookcovermatcher.core.catalog.HealthReport
import com.bookcovermatcher.core.catalog.HealthReportBuilder
import com.bookcovermatcher.core.catalog.HealthSeverity
import com.bookcovermatcher.core.catalog.InsightsView
import com.bookcovermatcher.core.catalog.SheetMapping
import com.bookcovermatcher.core.catalog.SheetScope
import com.bookcovermatcher.core.catalog.TimelineDateSettings
import com.bookcovermatcher.core.catalog.TimelineEntry
import com.bookcovermatcher.core.catalog.TimelineMode
import com.bookcovermatcher.core.catalog.WorkbookCatalog
import com.bookcovermatcher.core.catalog.WorkbookCatalogBuilder
import com.bookcovermatcher.core.catalog.WorkbookMapping
import com.bookcovermatcher.core.catalog.withSheets
import com.bookcovermatcher.core.excel.JavaZipSource
import com.bookcovermatcher.core.excel.comments.CatalogueField
import com.bookcovermatcher.core.excel.comments.CatalogueRecord
import com.bookcovermatcher.core.excel.comments.CatalogueTable
import com.bookcovermatcher.core.excel.comments.CellLink
import com.bookcovermatcher.core.excel.comments.CellValue
import com.bookcovermatcher.core.excel.comments.CommentImageScanner
import com.bookcovermatcher.core.excel.comments.CommentScanLimits
import com.bookcovermatcher.core.excel.comments.CommentScanResult
import com.bookcovermatcher.core.excel.comments.FilterField
import com.bookcovermatcher.core.excel.comments.ImageRow
import com.bookcovermatcher.core.excel.comments.ImageSort
import com.bookcovermatcher.core.excel.comments.ImageState
import com.bookcovermatcher.core.excel.comments.SheetCells
import com.bookcovermatcher.core.excel.comments.SheetLinks
import com.bookcovermatcher.core.excel.comments.SheetWindow
import com.bookcovermatcher.core.excel.comments.SheetWindows
import com.bookcovermatcher.core.excel.comments.SourceLocation
import com.bookcovermatcher.core.excel.comments.SourceLocations
import com.bookcovermatcher.core.excel.comments.WorkbookAnalysis
import com.bookcovermatcher.core.excel.comments.WorkbookCommentImages
import com.bookcovermatcher.core.excel.comments.WorkbookAnalyzer
import com.bookcovermatcher.core.excel.comments.WorkbookStyles
import com.bookcovermatcher.core.export.ImageManifest
import com.bookcovermatcher.data.ColumnMappingStore
import com.bookcovermatcher.data.CommentImageCache
import com.bookcovermatcher.data.InsightsViewStore
import com.bookcovermatcher.data.TimelineDateStore
import com.bookcovermatcher.data.WorkbookMappingStore
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs the offline workbook preview: scan (or reuse the cache), read media bytes, export.
 *
 * Same shape as the app's other controllers: a plain class owned by [MainViewModel], one `StateFlow`,
 * all ZIP/XML work on [Dispatchers.IO], and a cancelled job when the user switches workbooks. It reads
 * the staged copy of the workbook and never writes to it.
 */
class WorkbookCommentsController(
    context: Context,
    private val scope: CoroutineScope,
    private val cache: CommentImageCache = CommentImageCache(File(context.filesDir, "comments")),
    private val onMessage: (String, ToastKind) -> Unit = { _, _ -> },
) {

    private val appContext: Context = context.applicationContext
    private val _ui = MutableStateFlow(WorkbookCommentsUi())
    val ui: StateFlow<WorkbookCommentsUi> = _ui.asStateFlow()

    private var job: Job? = null
    private var source: File? = null

    /**
     * Worksheet cells already read for the source view, keyed by sheet part.
     *
     * A navigation reads its sheet once and reuses the result for every later location in the same
     * sheet; the cache is cleared whenever the workbook is opened, rescanned or closed, so cells from
     * another workbook can never be shown.
     */
    private val sheetCells = HashMap<String, List<CellValue>>()

    /**
     * The workbook's own number formats and hyperlinks, read once per open for the source preview.
     *
     * The formats are the same table the Catalogue table reads, so a date cell reads identically in both
     * views; the links are kept per worksheet part, so a cell can never show its neighbour's destination.
     * Both are cleared whenever the workbook is opened, rescanned or closed.
     */
    private var styles: WorkbookStyles? = null
    private val sheetLinks = HashMap<String, Map<Long, CellLink>>()

    /** The saved column-mapping profiles; small JSON file, read once per open. */
    private val mappingStore = ColumnMappingStore(File(appContext.filesDir, "mappings"))
    /** The user's date-column choices, stored beside the mapping profiles but scoped per workbook. */
    private val dateStore = TimelineDateStore(File(appContext.filesDir, "mappings"))
    /** The mapping each workbook was last left with, so a manual choice survives a restart. */
    private val mappingStateStore = WorkbookMappingStore(File(appContext.filesDir, "mappings"))
    /** The worksheet selections and timeline year ranges this workbook was last left with. */
    private val viewStore = InsightsViewStore(File(appContext.filesDir, "mappings"))
    /** The last view written, so an unchanged state is not rewritten on every rebuild. */
    private var savedView: InsightsView? = null
    /** The mappings stored for this workbook, applied once the worksheets are parsed (per-worksheet entries). */
    private var storedMapping: WorkbookMapping? = null

    /** Opens the preview for [file], using the cache when the workbook hash is unchanged. */
    fun open(file: File, sha256: String, displayName: String, importedAt: Long = 0L) {
        // Already open on these bytes: keep the parsed result (and the user's filters) instead of
        // starting a second scan. A different workbook always reloads.
        if (_ui.value.open && _ui.value.sha256 == sha256 && !_ui.value.loading) return
        job?.cancel()
        sheetCells.clear()
        clearCellMetadata()
        source = file
        _ui.update {
            WorkbookCommentsUi(
                open = true,
                workbookName = displayName,
                sha256 = sha256,
                loading = true,
                importedAt = importedAt,
            )
        }
        job = scope.launch {
            val profiles = withContext(Dispatchers.IO) { mappingStore.load() }
            // The date-column choices belong to this workbook, so they load with it and not with a profile.
            val dates = withContext(Dispatchers.IO) { dateStore.load(sha256) }
            // So does what the Insights screens were last left with: three worksheet scopes and the year
            // ranges. They are restored before any report is built, so the first numbers already respect them.
            val view = withContext(Dispatchers.IO) { viewStore.load(sha256) }
            savedView = view
            // And the mappings it was last left with, one entry per worksheet: a manual choice — the Default Order
            // column included — is in effect again after a restart, and no worksheet inherits another's columns.
            // They are applied once the worksheets (and their header rows) are known.
            val mapping = withContext(Dispatchers.IO) { mappingStateStore.load(sha256) }
            storedMapping = mapping
            _ui.update {
                if (it.sha256 == sha256) {
                    it.copy(profiles = profiles, dateSettings = dates).restoredView(view)
                } else {
                    it
                }
            }
            val cached = withContext(Dispatchers.IO) { cache.read(sha256) }
            if (cached != null) {
                val analysis = withContext(Dispatchers.IO) { analyze(file, cached) }
                _ui.update { it.copy(loading = false, model = cached, analysis = analysis) }
                restoreMapping(sha256, analysis)
                return@launch
            }
            val result = withContext(Dispatchers.IO) { scan(file, sha256, displayName) }
            apply(result, sha256)
        }
    }

    fun close() {
        job?.cancel()
        job = null
        source = null
        sheetCells.clear()
        clearCellMetadata()
        savedView = null
        storedMapping = null
        _ui.value = WorkbookCommentsUi()
    }

    /**
     * Re-applies the mappings stored for this workbook, once its worksheets and their header rows are known.
     *
     * The stored entries are per worksheet, so nothing is merged or borrowed: a worksheet with no stored entry stays
     * on its own detected headers. An open that already has applied mappings (a rebuild after a rescan) keeps them
     * instead, so the in-session choice is never overwritten by an older file.
     */
    private fun restoreMapping(sha256: String, analysis: WorkbookAnalysis?) {
        val mapping = storedMapping ?: return
        val tables = analysis?.tables.orEmpty()
        _ui.update { current ->
            if (current.sha256 != sha256 || current.sheetMappings.isNotEmpty()) current
            else current.restoredMapping(mapping, tables)
        }
    }

    /** Drops the number formats and hyperlinks read for the source preview (a new workbook, or new bytes). */
    private fun clearCellMetadata() {
        styles = null
        sheetLinks.clear()
    }

    /** Forces a fresh scan, ignoring and replacing the cached entry for this workbook. */
    fun reload() {
        val file = source ?: return
        val state = _ui.value
        if (state.sha256.isBlank()) return
        job?.cancel()
        sheetCells.clear()
        // New bytes: the formats and links read from the old ones must not be shown beside the new ones.
        clearCellMetadata()
        // Only what came from the old bytes is dropped: profiles, the mapping draft, the three worksheet
        // scopes, both filters and the chosen date columns survive the rescan.
        _ui.update { it.reloadingForRescan() }
        job = scope.launch {
            apply(withContext(Dispatchers.IO) { scan(file, state.sha256, state.workbookName) }, state.sha256)
        }
    }

    private suspend fun apply(result: CommentScanResult, sha256: String) {
        when (result) {
            is CommentScanResult.Success -> {
                withContext(Dispatchers.IO) { cache.write(result.workbook) }
                val analysis = withContext(Dispatchers.IO) { analyze(source, result.workbook) }
                _ui.update { state ->
                    // A newer workbook won the race: keep its state, do not overwrite it.
                    if (state.sha256 != sha256) state
                        else state.analysisLoaded(result.workbook, analysis)
                }
                // The stored per-worksheet mappings are applied here, where the header rows are known (a fresh
                // open); an in-session choice from a rescan is kept as it is.
                restoreMapping(sha256, analysis)
                // The reports are derived from the analysis, so a rescan must rebuild them *here*: waiting
                // for the user to leave and re-enter Insights was the bug. The mapping draft is re-previewed
                // against the new tables so the Fields screen cannot point at a worksheet that is gone.
                val current = _ui.value
                if (current.sha256 != sha256 || !current.wantsInsights) return
                if (current.mappingDraft.isNotEmpty() || current.mappingSheet != null) {
                    startMapping(current.mappingSheet)
                }
                refreshInsights()
            }
            is CommentScanResult.Failure -> _ui.update { state ->
                if (state.sha256 != sha256) state else state.reloadFailed(result.message)
            }
        }
    }

    fun showSection(section: CommentsSection) {
        _ui.update { it.copy(section = section) }
        if (section == CommentsSection.INSIGHTS) {
            // The catalog is built on first use, not on open: a workbook the user only skims never pays
            // for the four reports.
            if (_ui.value.catalog == null && !_ui.value.insightsBusy) refreshInsights()
            if (_ui.value.mappingDraft.isEmpty()) startMapping()
        }
    }

    fun selectSheet(sheet: String?) = _ui.update { state ->
        // Catalogue filters belong to a worksheet: a filter whose table is no longer in scope is dropped
        // instead of silently filtering against data the user cannot see.
        val kept = state.catalogueFilters.filter { filter -> sheet == null || filter.sheetName == sheet }
        state.copy(
            selectedSheet = sheet,
            selectedCell = null,
            selectedSheetName = null,
            catalogueFilters = kept,
            filterPickerOpen = false,
            filterFieldKey = null,
            filterValueSearch = "",
        )
    }

    fun selectComment(sheetName: String, cell: String) = _ui.update {
        it.copy(selectedSheetName = sheetName, selectedCell = cell, section = CommentsSection.COMMENTS)
    }

    fun dismissComment() = _ui.update { it.copy(selectedCell = null, selectedSheetName = null) }

    fun openViewer(mediaPath: String) = _ui.update { state ->
        val associations = state.comments.filter { mediaPath in it.mediaPaths }
            .map { "${it.sheetName}!${it.cell}" }
        state.copy(
            viewer = CommentViewerUi(
                mediaPath = mediaPath,
                title = mediaPath.substringAfterLast('/'),
                associations = associations,
            ),
        )
    }

    fun closeViewer() = _ui.update { it.copy(viewer = null) }

    // ---------------------------------------------------------------------------------------------
    // Source location: "go to the worksheet cell"
    // ---------------------------------------------------------------------------------------------

    /**
     * Opens the source overlay for [location] and, when the parser proved exactly one destination,
     * starts reading that worksheet at once. Several destinations stay a list: the app never picks one
     * for the user.
     */
    fun openSource(heading: String, location: SourceLocation) {
        _ui.update { it.openSource(heading, location) }
        val candidates = _ui.value.source?.candidates.orEmpty()
        if (candidates.size == 1) openSourceCell(candidates.first())
    }

    /**
     * Reads the worksheet cells around [candidate] and shows them.
     *
     * The workbook is re-opened read-only for the first navigation in a sheet and the parsed cells are
     * reused afterwards. A workbook that has been removed, or a sheet that cannot be read, ends in a
     * readable message — never in an empty grid that would look like an empty row.
     */
    fun openSourceCell(candidate: SourceLocation.Candidate) {
        val file = source
        if (file == null || !file.isFile) {
            _ui.update { it.sourceFailed("The workbook is no longer available. Open it again to look up its cells.") }
            return
        }
        val state = _ui.value
        val sha256 = state.sha256
        _ui.update { it.sourceLoading(candidate) }
        scope.launch {
            val window = withContext(Dispatchers.IO) { buildWindow(file, candidate, state.analysis) }
            _ui.update { current ->
                // A different workbook (or a closed overlay) won the race: drop this late result.
                if (!current.sourceBelongsTo(sha256) || current.source?.chosen != candidate) current
                else current.sourceWindow(candidate, window)
            }
        }
    }

    fun closeSource() = _ui.update { it.closeSource() }

    private fun buildWindow(
        file: File,
        candidate: SourceLocation.Candidate,
        analysis: WorkbookAnalysis?,
    ): SheetWindow? = try {
        JavaZipSource(ZipFile(file)).use { zip ->
            val cells = sheetCells.getOrPut(candidate.sheetPath) { SheetCells.read(zip, candidate.sheetPath) }
            // The same number formats the Catalogue table reads, so a date or a percentage is spelled the same
            // in both views; and the worksheet's own hyperlinks, so a linked cell is recognisable here too.
            val formats = styles ?: runCatching { WorkbookStyles.read(zip) }
                .getOrDefault(WorkbookStyles.EMPTY)
                .also { styles = it }
            val links = sheetLinks.getOrPut(candidate.sheetPath) {
                runCatching { SheetLinks.read(zip, candidate.sheetPath) }.getOrDefault(emptyMap())
            }
            val headerRow = analysis?.tables?.firstOrNull { it.sheetName == candidate.sheetName }?.headerRow
            SheetWindows.build(
                sheetName = candidate.sheetName,
                sheetPath = candidate.sheetPath,
                cells = cells,
                targetRow = candidate.row,
                targetColumn = candidate.column,
                headerRow = headerRow,
                styles = formats,
                links = links,
            )
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Copies the **original** media bytes of [path] to [target]. No re-encoding: the package entry is
     * written straight into the destination, so the exported file is byte-identical to the workbook's.
     */
    fun export(path: String, target: Uri) {
        val file = source ?: return
        _ui.update { it.copy(exporting = path) }
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    val bytes = readEntry(file, path)
                        ?: return@withContext "The image is missing from the workbook."
                    appContext.contentResolver.openOutputStream(target, "w")?.use { out -> out.write(bytes) }
                        ?: return@withContext "The destination could not be opened."
                    "Exported ${path.substringAfterLast('/')} (${bytes.size} bytes)."
                } catch (e: Exception) {
                    "Export failed: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            _ui.update { it.copy(exporting = null) }
            onMessage(outcome, if (outcome.startsWith("Exported")) ToastKind.OK else ToastKind.ERR)
        }
    }

    /** Media bytes for a thumbnail or the viewer; null when missing or above the previewer limit. */
    suspend fun mediaBytes(path: String): ByteArray? {
        val file = source ?: return null
        return withContext(Dispatchers.IO) { readEntry(file, path) }
    }

    /** True when these bytes cannot be shown (missing or above [CommentScanLimits.MAX_MEDIA_BYTES]). */
    fun tooLarge(bytes: ByteArray?): Boolean = bytes == null || bytes.size > CommentScanLimits.MAX_MEDIA_BYTES

    private fun scan(file: File, sha256: String, displayName: String): CommentScanResult = try {
        JavaZipSource(ZipFile(file)).use { zip ->
            CommentImageScanner.scan(
                zip = zip,
                workbookName = displayName,
                sha256 = sha256,
                shouldStop = { !(scope.coroutineContext[Job]?.isActive ?: true) },
            )
        }
    } catch (e: Exception) {
        CommentScanResult.Failure(
            "This workbook could not be read: ${e.message ?: e.javaClass.simpleName}. " +
                "Encrypted or password-protected files cannot be previewed.",
        )
    }

    private fun readEntry(file: File, path: String): ByteArray? = try {
        JavaZipSource(ZipFile(file)).use { zip -> if (zip.contains(path)) zip.read(path) else null }
    } catch (e: Exception) {
        null
    }

    /** Catalogue tables + the image inventory, recomputed from the (cached or fresh) scan result. */
    private fun analyze(file: File?, model: WorkbookCommentImages): WorkbookAnalysis? {
        val target = file ?: return null
        return try {
            JavaZipSource(ZipFile(target)).use { zip -> WorkbookAnalyzer.analyze(zip, model) }
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------------------------------------
    // All Images: search, filters, sorting, selection
    // ---------------------------------------------------------------------------------------------

    fun setImageSearch(text: String) = _ui.update { it.copy(imageSearch = text) }

    /** "All" clears the filter; a chip toggles itself. An empty set means "everything". */
    /** Selects one association filter; null means "All". Tapping the active chip is a no-op. */
    fun setImageFilter(state: ImageState?) = _ui.update { it.copy(imageFilter = state) }

    fun clearImageFilters() = _ui.update {
        it.copy(imageSearch = "", imageFilter = null, catalogueFilters = emptyList())
    }

    /** Clears only the catalogue filters; the search text and association chips stay as they are. */
    fun clearCatalogueFilters() = _ui.update {
        it.copy(catalogueFilters = emptyList(), filterFieldKey = null, filterValueSearch = "")
    }

    // ---- catalogue filter picker (Images) ---------------------------------------------------

    fun openFilterPicker() = _ui.update {
        it.copy(filterPickerOpen = true, filterFieldKey = null, filterSearch = "", filterValueSearch = "")
    }

    fun closeFilterPicker() = _ui.update {
        it.copy(filterPickerOpen = false, filterFieldKey = null, filterValueSearch = "")
    }

    fun setFilterSearch(text: String) = _ui.update { it.copy(filterSearch = text) }

    /** Shows the values of one field; going back keeps the field list as it was. */
    fun openFilterField(key: String) = _ui.update { it.copy(filterFieldKey = key, filterValueSearch = "") }

    fun backToFilterFields() = _ui.update { it.copy(filterFieldKey = null, filterValueSearch = "") }

    fun setFilterValueSearch(text: String) = _ui.update { it.copy(filterValueSearch = text) }

    /** Applies the chosen value for [field]; other fields keep their own value (AND across fields). */
    fun setCatalogueFilter(field: FilterField, value: String) = _ui.update { state ->
        val next = state.catalogueFilters.filterNot { it.key == field.key } + field.filter(value)
        state.copy(
            catalogueFilters = next,
            filterPickerOpen = false,
            filterFieldKey = null,
            filterValueSearch = "",
        )
    }

    fun removeCatalogueFilter(key: String) = _ui.update { state ->
        state.copy(catalogueFilters = state.catalogueFilters.filterNot { it.key == key })
    }

    /** The "All" chip clears only the association filter; a search stays as typed. */
    fun clearImageStates() = _ui.update { it.copy(imageFilter = null) }

    fun setImageSort(sort: ImageSort) = _ui.update { it.copy(imageSort = sort) }
    fun setAscending(ascending: Boolean) = _ui.update { it.copy(ascending = ascending) }
    fun setGridMode(grid: Boolean) = _ui.update { it.copy(gridMode = grid) }

    /** Thumbnail size in the All Images grid: 0 = small, 1 = default, 2 = large. */
    fun setThumbSize(size: Int) = _ui.update { it.copy(thumbSize = size.coerceIn(0, 2)) }
    fun setCatalogueQuery(text: String) = _ui.update { it.copy(catalogueQuery = text) }

    fun toggleImageSelected(key: String) = _ui.update {
        val next = if (key in it.selectedImages) it.selectedImages - key else it.selectedImages + key
        it.copy(selectedImages = next)
    }

    fun selectAllVisible() = _ui.update { state ->
        state.copy(selectedImages = state.visibleImages.map { row -> row.key }.toSet())
    }

    fun clearSelection() = _ui.update { it.copy(selectedImages = emptySet()) }

    fun openSheetPicker() = _ui.update { it.copy(sheetPickerOpen = true, sheetSearch = "") }
    fun closeSheetPicker() = _ui.update { it.copy(sheetPickerOpen = false) }
    fun setSheetSearch(text: String) = _ui.update { it.copy(sheetSearch = text) }

    /**
     * Batch export of the selected inventory rows into a user-chosen folder.
     *
     * Original bytes are copied unchanged, names are sanitised and de-duplicated, and a `manifest.csv`
     * with UTF-8 metadata is written beside them. Partial failures are counted and reported; nothing is
     * ever written to the workbook.
     */
    fun exportSelected(treeUri: Uri) {
        val rows = _ui.value.selectedRows.filter { it.mediaPath != null }
        if (rows.isEmpty()) {
            onMessage("Select at least one image first.", ToastKind.INFO)
            return
        }
        val file = source ?: return
        _ui.update { it.copy(exporting = "selected") }
        scope.launch {
            val summary = withContext(Dispatchers.IO) { writeBatch(file, rows, treeUri) }
            _ui.update { it.copy(exporting = null) }
            onMessage(summary, if (summary.startsWith("Exported")) ToastKind.OK else ToastKind.ERR)
        }
    }

    private fun writeBatch(file: File, rows: List<ImageRow>, treeUri: Uri): String {
        val resolver = appContext.contentResolver
        val treeDoc = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        }.getOrNull() ?: return "The export folder could not be opened."

        val used = HashSet<String>()
        val exported = ArrayList<Pair<String, ImageRow>>()
        var failed = 0
        for (row in rows) {
            val path = row.mediaPath ?: continue
            val bytes = readEntry(file, path)
            if (bytes == null) {
                failed++
                continue
            }
            val name = ImageManifest.uniqueName(ImageManifest.safeFileName(row.fileName), used)
            try {
                val document = DocumentsContract.createDocument(resolver, treeDoc, mimeFor(row.format), name)
                    ?: throw IllegalStateException("no document")
                resolver.openOutputStream(document)?.use { it.write(bytes) }
                    ?: throw IllegalStateException("no stream")
                exported.add(name to row)
            } catch (e: Exception) {
                failed++
            }
        }

        var manifestError = false
        try {
            val name = ImageManifest.uniqueName("manifest.csv", used)
            val document = DocumentsContract.createDocument(resolver, treeDoc, "text/csv", name)
                ?: throw IllegalStateException("no document")
            resolver.openOutputStream(document)?.use {
                it.write(ImageManifest.csv(exported).toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("no stream")
        } catch (e: Exception) {
            manifestError = true
        }

        val status = StringBuilder("Exported ${exported.size} image(s)")
        if (failed > 0) status.append(", $failed failed")
        if (manifestError) status.append(", manifest not written")
        return status.append(".").toString()
    }

    private fun mimeFor(format: String): String = when (format.lowercase()) {
        "png" -> "image/png"
        "jpeg", "jpg" -> "image/jpeg"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }

    // ---------------------------------------------------------------------------------------------
    // Insights (v2.4.0): health, gallery, analytics, timeline and column mapping
    // ---------------------------------------------------------------------------------------------

    /** Everything one build produces, kept together so the four screens share one set of results. */
    private data class InsightsBundle(
        val catalog: WorkbookCatalog,
        val health: HealthReport,
        val analytics: CollectionAnalytics,
        val publication: CollectionTimeline,
        val acquisition: CollectionTimeline,
    )

    /**
     * Builds the canonical catalog and everything derived from it, off the main thread.
     *
     * The parsed analysis and the scan result are reused — nothing is re-read from the workbook — and a
     * build that finishes after the user switched workbooks is dropped.
     *
     * The mapping comes from the state, **per worksheet**: every worksheet is read with its own applied mapping or
     * its own detected headers, so applying (or saving) the columns of one worksheet cannot change what another
     * worksheet means. There is no parameter for it on purpose — a caller-supplied profile would be exactly the
     * workbook-wide mapping that used to leak between worksheets.
     */
    fun refreshInsights() {
        val state = _ui.value
        val analysis = state.analysis
        val model = state.model
        if (analysis == null || model == null) {
            _ui.update { it.insightsFailed("The workbook is still being read. The reports open as soon as it is ready.") }
            return
        }
        val sha256 = state.sha256
        val mappings = state.sheetMappings
        val dates = state.dateSettings
        // A worksheet selection is reconciled before it is used, so the numbers and the visible scope agree.
        val available = analysis.sheets.map { SheetScope.keyOf(it.partPath, it.name) }.toSet()
        val healthScope = state.healthScope.reconciled(available)
        val analyticsScope = state.analyticsScope.reconciled(available)
        _ui.update { it.copy(healthScope = healthScope, analyticsScope = analyticsScope).insightsStarted() }
        scope.launch {
            val bundle = withContext(Dispatchers.IO) {
                buildInsights(sha256, analysis, model, mappings, dates, healthScope, analyticsScope)
            }
            _ui.update { current ->
                if (!current.insightsBelongTo(sha256)) {
                    current
                } else {
                    current.insightsLoaded(
                        catalog = bundle.catalog,
                        health = bundle.health,
                        analytics = bundle.analytics,
                        publication = bundle.publication,
                        acquisition = bundle.acquisition,
                    )
                }
            }
            // A worksheet that no longer exists is dropped from a saved selection here, so the file on disk
            // and the scope on screen agree from the first build onwards.
            persistView()
        }
    }

    private fun buildInsights(
        sha256: String,
        analysis: WorkbookAnalysis,
        model: WorkbookCommentImages,
        sheetMappings: Map<String, SheetMapping>,
        dates: TimelineDateSettings,
        healthScope: SheetScope,
        analyticsScope: SheetScope,
    ): InsightsBundle {
        val catalog = WorkbookCatalogBuilder.build(
            workbookSha256 = sha256,
            sheets = analysis.sheets,
            tables = analysis.tables,
            images = analysis.images,
            // "No workbook-wide mapping": each worksheet falls back to its **own** detected headers unless it has
            // an applied mapping of its own. This is what keeps one worksheet's columns out of the others.
            profile = null,
            sheetMappings = sheetMappings,
        )
        return InsightsBundle(
            catalog = catalog,
            // Both reports are calculated for the worksheets the user selected, not filtered afterwards:
            // the summary counts and every breakdown come from the same scoped record list.
            health = HealthReportBuilder.build(catalog, analysis, model, healthScope),
            analytics = CollectionAnalyticsBuilder.build(catalog, analyticsScope, analysis.date1904),
            // Both timelines read the columns the user chose for this workbook — never a guessed header,
            // and serials are read in the workbook's own date system.
            publication = CollectionTimelineBuilder.published(catalog, dates, date1904 = analysis.date1904),
            acquisition = CollectionTimelineBuilder.acquired(catalog, dates, date1904 = analysis.date1904),
        )
    }

    /** Monotonic revision of the worksheet-scoped rebuilds, so an older result cannot win a race. */
    private var scopeRevision = 0

    /**
     * Recomputes the two worksheet-scoped reports from the catalog that is already in memory.
     *
     * Nothing is re-parsed: the catalog stays the one canonical model, and only the report and the
     * analytics are recalculated for the newly selected worksheets. A result that arrives after another
     * selection, another workbook or another catalog is dropped.
     */
    private fun rebuildScopedReports() {
        val state = _ui.value
        val catalog = state.catalog ?: return
        val analysis = state.analysis ?: return
        val healthScope = state.healthScope
        val analyticsScope = state.analyticsScope
        val model = state.model
        val sha256 = state.sha256
        val revision = ++scopeRevision
        scope.launch {
            val health = withContext(Dispatchers.IO) {
                HealthReportBuilder.build(catalog, analysis, model, healthScope)
            }
            val analytics = withContext(Dispatchers.IO) {
                CollectionAnalyticsBuilder.build(catalog, analyticsScope, analysis.date1904)
            }
            _ui.update { current ->
                if (revision != scopeRevision || current.sha256 != sha256 || current.catalog !== catalog) {
                    current
                } else {
                    current.copy(health = health, analytics = analytics)
                }
            }
        }
    }

    fun showInsights(tab: InsightsTab) = _ui.update { it.showInsights(tab) }

    // ---- mapping profiles ---------------------------------------------------------------------

    /**
     * Selects the profile **view**: null is Detected, a name is that saved profile's configuration.
     *
     * This is a view switch, not a data exchange: the Detected baseline is computed, every saved profile keeps its
     * own record untouched, and the per-worksheet edits applied in the previous view are dropped with it. The
     * draft is re-seeded from the newly selected configuration, and every report is rebuilt from it.
     */
    fun selectProfile(name: String?) {
        val state = _ui.value
        val saved = state.profileOf(name)
        _ui.update { it.selectProfileView(saved?.name) }
        persistMapping()
        refreshInsights()
        startMapping()
        onMessage(
            if (saved == null) {
                "Showing the detected headers for this workbook."
            } else {
                "Showing the mapping saved in \u201C${saved.name}\u201D."
            },
            ToastKind.INFO,
        )
    }

    /**
     * Saves the current configuration as a reusable profile under [name], and selects it.
     *
     * The snapshot is built by [profileSnapshot] from the configuration **in effect**, one entry per worksheet: the
     * worksheet on screen takes the draft (the user's changes), and the other worksheets keep their own mappings —
     * inherited from the selected profile or from their detected headers. Saving therefore cannot copy the inspected
     * worksheet's columns (or its blanks) into another worksheet, and it cannot reset a workbook's other
     * worksheets to detection. Saved profiles that already exist are not modified except by re-using their name.
     */
    fun saveProfile(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            onMessage("Give the profile a name first.", ToastKind.INFO)
            return
        }
        val state = _ui.value
        if (mappedColumns(state.mappingDraft).isEmpty()) {
            onMessage("Map at least one column before saving a profile.", ToastKind.INFO)
            return
        }
        val sheetName = state.mappingSheet
        val snapshot = state.profileSnapshot(trimmed)
        if (snapshot == null) {
            onMessage("There is no worksheet mapping to save yet.", ToastKind.INFO)
            return
        }
        scope.launch {
            val profiles = withContext(Dispatchers.IO) { mappingStore.save(snapshot) }
            // The new profile now holds the whole configuration, so the view switches to it and the per-worksheet
            // edits that were folded into it are dropped: nothing is shown twice, and no other profile changed.
            _ui.update {
                it.copy(profiles = profiles).selectProfileView(trimmed)
            }
            persistMapping()
            refreshInsights()
            startMapping(sheetName)
            onMessage(
                "Saved the profile \u201C$trimmed\u201D: ${snapshot.sheetKeys.size} worksheet(s). " +
                    "Other profiles are unchanged.",
                ToastKind.OK,
            )
        }
    }

    /**
     * Deletes a saved profile. Worksheets it was selected for fall back to the Detected view; no other profile and
     * no applied edit is touched.
     */
    fun deleteProfile(name: String) {
        scope.launch {
            val profiles = withContext(Dispatchers.IO) { mappingStore.delete(name) }
            val wasSelected = _ui.value.activeProfile == name
            _ui.update { state ->
                val next = state.copy(profiles = profiles)
                if (wasSelected) next.selectProfileView(null) else next
            }
            if (wasSelected) {
                persistMapping()
                refreshInsights()
                startMapping()
            }
            onMessage("Deleted the profile \u201C$name\u201D.", ToastKind.OK)
        }
    }

    // ---- Fields: drafting a mapping ------------------------------------------------------------

    fun selectMappingSheet(sheetName: String) = startMapping(sheetName)

    /**
     * Starts (or restarts) the draft for [sheetName] from **that worksheet's** mapping, and previews it.
     *
     * The base is the worksheet's own applied mapping when it has one, otherwise its own detected headers — never
     * the mapping of whichever worksheet was open last, which is how one worksheet's columns used to appear in
     * another's Fields screen. The preview is computed by the same builder the reports use, so what the user
     * confirms is what the other screens will read.
     */
    fun startMapping(sheetName: String? = null, base: ColumnMappingProfile? = null) {
        val state = _ui.value
        val tables = state.analysis?.tables.orEmpty()
        val target = sheetName ?: state.mappingSheet ?: tables.firstOrNull()?.sheetName
        val table = tables.firstOrNull { it.sheetName == target }
        if (table == null) {
            _ui.update {
                it.copy(
                    mappingSheet = null,
                    mappingHeaders = emptyList(),
                    mappingDraft = emptyMap(),
                    mappingPreview = emptyList(),
                    mappingIssues = emptyList(),
                    mappingDirty = false,
                )
            }
            return
        }
        val profile = base ?: state.mappingFor(table)
        previewDraft(CatalogField.ordered.associateWith { profile.columns[it] }, table)
    }

    /** Maps one canonical field to [header] (null = leave it unmapped) and re-previews. */
    fun mapField(field: CatalogField, header: String?) {
        val state = _ui.value
        val table = state.analysis?.tables?.firstOrNull { it.sheetName == state.mappingSheet } ?: return
        previewDraft(state.mappingDraft.withField(field, header), table)
    }

    private fun previewDraft(draft: Map<CatalogField, String?>, table: CatalogueTable) {
        val profile = ColumnMappingProfile(DRAFT_NAME, mappedColumns(draft))
        val analysis = _ui.value.analysis
        val preview = if (analysis == null) {
            emptyList()
        } else {
            WorkbookCatalogBuilder.build(
                workbookSha256 = _ui.value.sha256,
                sheets = analysis.sheets,
                tables = listOf(table),
                images = analysis.images,
                profile = profile,
            ).entries
        }
        _ui.update {
            it.mappingEdited(
                draft = draft,
                sheetName = table.sheetName,
                // A profile maps headers by name, so a column whose header cell is blank cannot be named
                // and is left out of the picker instead of appearing as an empty row.
                headers = table.fields.filter { field -> field.hasHeader }.map { field -> field.name },
                preview = preview.take(MAPPING_PREVIEW),
                issues = ColumnMappings.resolve(table, profile).issues,
            )
        }
    }

    /**
     * Applies the draft to the **worksheet being inspected** and rebuilds the reports. Nothing is written to the
     * workbook file.
     *
     * A mapping that turns out to be exactly what this worksheet's headers already detect is not stored as an
     * applied mapping: the worksheet stays on detection, the **Detected** status keeps its blue, and the persisted
     * file gains nothing. Any other worksheet is untouched — its own entry, or its own detection, stays as it was.
     */
    fun applyMapping() {
        val state = _ui.value
        val columns = mappedColumns(state.mappingDraft)
        if (columns.isEmpty()) {
            onMessage("Map at least one column first.", ToastKind.INFO)
            return
        }
        // Only the worksheet on screen is touched: the other worksheets keep the mapping they have in the
        // selected profile view, or their own detection.
        _ui.update { it.applySheetMapping(columns) }
        refreshInsights()
        persistMapping()
        val sheet = state.mappingSheet ?: "this worksheet"
        onMessage(
            if (_ui.value.isDetectedMapping) {
                "Applied the mapping: $sheet now matches its detected headers. The file itself is unchanged."
            } else {
                "Applied the mapping to $sheet. The file itself is unchanged."
            },
            ToastKind.OK,
        )
    }
    /**
     * Remembers the mappings now in effect, one entry per worksheet, or forgets the workbook when every worksheet
     * is back on its detected headers.
     *
     * Written only from an explicit action (apply mapping, save a profile, pick a profile, delete a profile, pick
     * **Detected**) — never from a recomposition — so the file cannot record a choice the user did not make.
     */
    private fun persistMapping() {
        val mapping = _ui.value.persistableMapping()
        if (mapping.workbookSha256.isBlank()) return
        scope.launch {
            withContext(Dispatchers.IO) {
                if (mapping.isEmpty) mappingStateStore.delete(mapping.workbookSha256)
                else mappingStateStore.save(mapping)
            }
        }
    }

    fun resetMapping() = startMapping(_ui.value.mappingSheet)

    private fun mappedColumns(draft: Map<CatalogField, String?>): Map<CatalogField, String> =
        CatalogField.ordered.mapNotNull { field -> draft[field]?.let { field to it } }.toMap()

    // ---- filters and navigation ---------------------------------------------------------------

    fun setTimelineMode(mode: TimelineMode) = _ui.update { it.selectTimeline(mode) }

    fun toggleTimelineOrder() = _ui.update { it.toggleTimelineOrder() }

    // ---- timeline filter (v2.4.0-dev.4) -------------------------------------------------------

    /** Text filter of the timeline on screen; the other date meaning keeps its own filter. */
    fun setTimelineQuery(text: String) = _ui.update { it.filterTimeline(it.currentTimelineFilter.withQuery(text)) }

    /** Restores the whole eligible timeline of the meaning on screen. */
    fun clearTimelineFilters() {
        _ui.update { it.clearTimelineFilter() }
        persistView()
    }

    fun openTimelineYearPicker(endpoint: TimelineYearEndpoint) = _ui.update { it.timelineYearPicker(endpoint) }

    fun closeTimelineYearPicker() = _ui.update { it.timelineYearPicker(null) }

    /** Sets one end of the year range; null clears that bound. The choice is saved with the workbook. */
    fun pickTimelineYear(endpoint: TimelineYearEndpoint, year: Int?) {
        _ui.update { it.pickTimelineYear(endpoint, year) }
        persistView()
    }

    // ---- worksheet scopes of Health and Analytics (v2.4.0-dev.4) ------------------------------

    fun openScopeDialog(target: ScopeTarget) = _ui.update { it.openScopeDialog(target) }

    fun toggleScopeSheet(key: String) = _ui.update { it.toggleScopeSheet(key) }

    fun selectAllScopeSheets() = _ui.update { it.scopeSelectAll() }

    fun closeScopeDialog() = _ui.update { it.closeScopeDialog() }

    /**
     * Commits the dialog's selection to its own screen and recalculates what it covers.
     *
     * Health and Analytics each own their scope and are rebuilt for it; the gallery's scope is part of its
     * filter and the visible cards are derived from the catalog, so nothing has to be recalculated there.
     */
    fun applyScopeDialog() {
        val before = _ui.value.scopeDialog ?: return
        _ui.update { it.scopeCommitted() }
        if (before.target != ScopeTarget.GALLERY) rebuildScopedReports()
        persistView()
        onMessage(
            "The ${before.target.label} report now covers " +
                (if (before.draft.isEmpty()) "all worksheets." else "${before.draft.size} worksheet(s)."),
            ToastKind.INFO,
        )
    }

    /** Applies [scope] to [target] directly — the "All worksheets" shortcut — and recalculates it. */
    fun setScope(target: ScopeTarget, scope: SheetScope) {
        _ui.update {
            when (target) {
                ScopeTarget.HEALTH -> it.copy(healthScope = scope)
                ScopeTarget.ANALYTICS -> it.copy(analyticsScope = scope)
                ScopeTarget.GALLERY -> it.copy(galleryFilter = it.galleryFilter.withSheets(scope))
            }
        }
        // Only the two calculated reports need a rebuild; the gallery derives its cards from the catalog.
        if (target != ScopeTarget.GALLERY) rebuildScopedReports()
        persistView()
    }

    // ---- persisted worksheet scopes and year ranges (v2.4.0) ----------------------------------

    /**
     * Writes this workbook's selections — the three worksheet scopes and each timeline's year range — when
     * they actually changed.
     *
     * Called only from the actions that change them, never from a recomposition, so scrolling, a thumbnail
     * or a rebuilt report never rewrites the file. A workbook opened for the first time keeps no entry until
     * the user chooses something.
     */
    private fun persistView() {
        val state = _ui.value
        if (state.sha256.isBlank()) return
        val view = state.persistableView()
        if (view == savedView) return
        savedView = view
        scope.launch { withContext(Dispatchers.IO) { viewStore.save(view) } }
    }

    // ---- date columns (v2.4.0-dev.3) ----------------------------------------------------------

    /** Opens the "Choose date columns" dialog; nothing is assigned until the user saves. */
    fun openDateSetup(sheetName: String? = null) = _ui.update { it.openDateSetup(sheetName) }

    fun closeDateSetup() = _ui.update { it.closeDateSetup() }

    fun selectDateSetupSheet(sheetName: String) = _ui.update { it.dateSetupOn(sheetName) }

    /** Switches the dialog between the Published and the Acquired slot. */
    fun setDateSetupTarget(mode: TimelineMode) = _ui.update { it.dateSetupAssignTo(mode) }

    /** Assigns a column to the open slot of the dialog (null clears it). */
    fun assignDateColumn(header: String?) = _ui.update { it.dateSetupAssign(header) }

    /** Empties both draft slots of the open worksheet; still nothing saved. */
    fun clearDateColumns() = _ui.update { it.dateSetupCleared() }

    /**
     * Saves the dialog's two choices for this workbook and rebuilds the timelines with them.
     *
     * The choice is stored per workbook and worksheet, so another workbook's dates are untouched, and the
     * rebuild goes through the same build every other report uses, so the Published and Acquired screens
     * cannot disagree about a record.
     */
    fun saveDateSetup() {
        val state = _ui.value
        if (!state.dateSetupOpen) return
        val committed = state.dateSetupCommitted()
        val sha256 = committed.sha256
        _ui.value = committed
        scope.launch {
            val stored = withContext(Dispatchers.IO) { dateStore.save(committed.dateSettings) }
            // Read back what the store kept: an empty choice removes the workbook's entry entirely.
            val saved = stored.firstOrNull { it.workbookSha256 == sha256 } ?: TimelineDateSettings(sha256)
            _ui.update { if (it.sha256 == sha256) it.copy(dateSettings = saved) else it }
            refreshInsights()
            onMessage(
                if (saved.isConfigured) {
                    "Saved the date columns for this workbook."
                } else {
                    "Cleared the date columns for this workbook."
                },
                ToastKind.OK,
            )
        }
    }

    /** Catalogue tab: compact cards, or the whole worksheet with every column. */
    fun setCatalogueView(view: CatalogueView) = _ui.update { it.showCatalogueView(view) }

    // ---- Catalogue table: the cell dialog (Copy / Open Link / Back) ---------------------------

    /**
     * Opens the cell dialog for one table cell.
     *
     * The cell is identified by its worksheet, source row and source column — not by where it happens to be
     * drawn — so the value, the link and the "go to source" action all describe the same workbook cell.
     */
    fun peekCell(sheetName: String, record: CatalogueRecord, field: CatalogueField) =
        _ui.update { it.peekCell(sheetName, record, field) }

    fun closeCellPeek() = _ui.update { it.closeCellPeek() }

    /**
     * Opens the same cell dialog from the Source location preview.
     *
     * The row and column are the **worksheet's own** (not the position on screen), and the value and the link
     * are read from the window's cell at that identity, so the dialog cannot describe a neighbouring cell.
     */
    fun peekSourceCell(window: SheetWindow, row: Int, column: Int) =
        _ui.update { it.peekSourceCell(window, row, column) }

    /**
     * Copies the **displayed** value of the open cell to the clipboard.
     *
     * The value is copied exactly as the table shows it — never the link's destination, never a trimmed or
     * re-formatted version — so what the user sees is what they paste. An empty cell copies an empty string
     * and says so instead of copying something unrelated.
     */
    fun copyPeekedCell() {
        val peek = _ui.value.cellPeek ?: return
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            onMessage("This device has no clipboard service.", ToastKind.ERR)
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("BookCoverMatcher cell ${peek.locationLabel}", peek.value))
        onMessage(
            if (peek.isBlank) "${peek.locationLabel} is empty; copied an empty value." else "Copied ${peek.locationLabel}.",
            ToastKind.OK,
        )
    }

    /**
     * Follows the open cell's link.
     *
     * A web link goes to the system browser through an ordinary `ACTION_VIEW` intent; an internal workbook
     * link opens that cell in the app's own source view instead of being handed to a browser. Anything the
     * app cannot follow (a defined name, a missing worksheet, a scheme with no handler) is reported, never
     * guessed.
     */
    fun openPeekedLink() {
        val peek = _ui.value.cellPeek ?: return
        val link = peek.link ?: return
        if (!link.isOpenable) {
            onMessage("This cell's link cannot be opened.", ToastKind.INFO)
            return
        }
        if (link.isWeb) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link.target))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val opened = runCatching { appContext.startActivity(intent) }.isSuccess
            if (!opened) {
                onMessage("No app on this device can open ${link.target}.", ToastKind.ERR)
                return
            }
            _ui.update { it.closeCellPeek() }
            return
        }
        val (sheetName, cell) = link.internalTarget() ?: run {
            onMessage("That link points at a defined name, which the app cannot follow.", ToastKind.INFO)
            return
        }
        val sheet = SourceLocations.sheetOf(sheetName, _ui.value.sheets)
        val (row, column) = SheetCells.refToCell(cell) ?: run {
            onMessage("The link's cell reference \u201C$cell\u201D cannot be read.", ToastKind.INFO)
            return
        }
        if (sheet == null) {
            onMessage("That link points at \u201C$sheetName\u201D, which this workbook does not have.", ToastKind.INFO)
            return
        }
        _ui.update { it.closeCellPeek() }
        openSourceCell(
            SourceLocation.Candidate(
                sheetName = sheet.name,
                sheetPath = sheet.partPath,
                cell = SheetCells.cellRef(row, column),
                row = row,
                column = column,
            ),
        )
    }

    fun setHealthFilter(severity: HealthSeverity?, category: HealthCategory? = null) =
        _ui.update { it.filterHealth(severity, category) }

    fun setGalleryFilter(filter: GalleryFilter) = _ui.update { it.filterGallery(filter) }

    fun setGallerySort(sort: GallerySort) = _ui.update { it.sortGallery(sort) }

    fun toggleGalleryOrder() = _ui.update { it.toggleGalleryOrder() }

    /**
     * The gallery's worksheet selection: all, one, or any combination.
     *
     * The whole selection is replaced at once (the picker commits a set), and the visible cards come from
     * the canonical catalog filtered by worksheet identity, so the change reaches the dataset rather than
     * hiding cards that were already selected.
     */
    fun setGalleryScope(scope: SheetScope) {
        _ui.update { it.copy(galleryFilter = it.galleryFilter.withSheets(scope)) }
        persistView()
    }

    fun setGalleryQuery(text: String) = _ui.update { it.copy(galleryFilter = it.galleryFilter.copy(query = text)) }

    /** Opens the worksheet row behind a catalog entry, through the verified source navigation. */
    fun openRecordSource(entry: CatalogEntry) {
        val state = _ui.value
        val pair = state.recordOf(entry)
        if (pair == null) {
            _ui.update { it.openSource(entry.locationLabel, SourceLocation.Unknown) }
            return
        }
        _ui.update { it.openSource(entry.locationLabel, SourceLocations.ofRecord(pair.first, pair.second, state.sheets)) }
        val candidates = _ui.value.source?.candidates.orEmpty()
        if (candidates.size == 1) openSourceCell(candidates.first())
    }

    /** Opens the cell an image finding came from, or the row a record finding came from. */
    fun openFindingSource(finding: HealthFinding) {
        val state = _ui.value
        val path = finding.imagePath
        if (path != null) {
            val row = state.allImages.firstOrNull { it.mediaPath == path || it.fileName == path || it.key == path }
            if (row != null) {
                _ui.update { it.openSource(row.fileName, SourceLocations.ofImage(row, state.sheets)) }
                val candidates = _ui.value.source?.candidates.orEmpty()
                if (candidates.size == 1) openSourceCell(candidates.first())
                return
            }
        }
        val entry = finding.recordId?.let { id -> state.catalog?.entries?.firstOrNull { it.id == id } }
        if (entry != null) {
            openRecordSource(entry)
        } else {
            onMessage("This finding has no worksheet location.", ToastKind.INFO)
        }
    }

    /** Opens the row behind a timeline entry, through the verified source navigation. */
    fun openTimelineEntry(entry: TimelineEntry) {
        val catalogEntry = _ui.value.catalog?.entries?.firstOrNull { it.id == entry.recordId }
        if (catalogEntry == null) {
            onMessage("That record is no longer in this workbook's catalog.", ToastKind.INFO)
            return
        }
        openRecordSource(catalogEntry)
    }

    private companion object {
        /** Records shown on the mapping preview; the full catalog is still built on apply. */
        const val MAPPING_PREVIEW = 12

        /** The name of a draft mapping that has not been saved yet. */
        const val DRAFT_NAME = "Draft"
    }
}