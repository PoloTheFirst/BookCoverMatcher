package com.bookcovermatcher

import android.content.Context
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
import com.bookcovermatcher.core.catalog.TimelineEntry
import com.bookcovermatcher.core.catalog.TimelineMode
import com.bookcovermatcher.core.catalog.WorkbookCatalog
import com.bookcovermatcher.core.catalog.WorkbookCatalogBuilder
import com.bookcovermatcher.core.excel.JavaZipSource
import com.bookcovermatcher.core.excel.comments.CatalogueTable
import com.bookcovermatcher.core.excel.comments.CellValue
import com.bookcovermatcher.core.excel.comments.CommentImageScanner
import com.bookcovermatcher.core.excel.comments.CommentScanLimits
import com.bookcovermatcher.core.excel.comments.CommentScanResult
import com.bookcovermatcher.core.excel.comments.FilterField
import com.bookcovermatcher.core.excel.comments.ImageRow
import com.bookcovermatcher.core.excel.comments.ImageSort
import com.bookcovermatcher.core.excel.comments.ImageState
import com.bookcovermatcher.core.excel.comments.SheetCells
import com.bookcovermatcher.core.excel.comments.SheetWindow
import com.bookcovermatcher.core.excel.comments.SheetWindows
import com.bookcovermatcher.core.excel.comments.SourceLocation
import com.bookcovermatcher.core.excel.comments.SourceLocations
import com.bookcovermatcher.core.excel.comments.WorkbookAnalysis
import com.bookcovermatcher.core.excel.comments.WorkbookCommentImages
import com.bookcovermatcher.core.excel.comments.WorkbookAnalyzer
import com.bookcovermatcher.core.export.ImageManifest
import com.bookcovermatcher.data.ColumnMappingStore
import com.bookcovermatcher.data.CommentImageCache
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

    /** The saved column-mapping profiles; small JSON file, read once per open. */
    private val mappingStore = ColumnMappingStore(File(appContext.filesDir, "mappings"))

    /** Opens the preview for [file], using the cache when the workbook hash is unchanged. */
    fun open(file: File, sha256: String, displayName: String, importedAt: Long = 0L) {
        // Already open on these bytes: keep the parsed result (and the user's filters) instead of
        // starting a second scan. A different workbook always reloads.
        if (_ui.value.open && _ui.value.sha256 == sha256 && !_ui.value.loading) return
        job?.cancel()
        sheetCells.clear()
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
            _ui.update { if (it.sha256 == sha256) it.copy(profiles = profiles) else it }
            val cached = withContext(Dispatchers.IO) { cache.read(sha256) }
            if (cached != null) {
                val analysis = withContext(Dispatchers.IO) { analyze(file, cached) }
                _ui.update { it.copy(loading = false, model = cached, analysis = analysis) }
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
        _ui.value = WorkbookCommentsUi()
    }

    /** Forces a fresh scan, ignoring and replacing the cached entry for this workbook. */
    fun reload() {
        val file = source ?: return
        val state = _ui.value
        if (state.sha256.isBlank()) return
        job?.cancel()
        sheetCells.clear()
        _ui.update {
            it.copy(
                loading = true,
                error = null,
                source = null,
                // The parsed data is about to change: every derived result is dropped, not kept stale.
                catalog = null,
                health = null,
                analytics = null,
                publicationTimeline = null,
                acquisitionTimeline = null,
                importTimeline = null,
                insightsError = null,
                mappingPreview = emptyList(),
                mappingIssues = emptyList(),
            )
        }
        job = scope.launch {
            apply(withContext(Dispatchers.IO) { scan(file, state.sha256, state.workbookName) }, state.sha256)
        }
    }

    private suspend fun apply(result: CommentScanResult, sha256: String) {
        when (result) {
            is CommentScanResult.Success -> {
                withContext(Dispatchers.IO) { cache.write(result.workbook) }
                _ui.update { state ->
                    // A newer workbook won the race: keep its state, do not overwrite it.
                    if (state.sha256 != sha256) state
                        else state.copy(
                            loading = false,
                            model = result.workbook,
                            analysis = withContext(Dispatchers.IO) { analyze(source, result.workbook) }
                        )
                }
            }
            is CommentScanResult.Failure -> _ui.update { state ->
                if (state.sha256 != sha256) state else state.copy(loading = false, error = result.message)
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
            val headerRow = analysis?.tables?.firstOrNull { it.sheetName == candidate.sheetName }?.headerRow
            SheetWindows.build(
                sheetName = candidate.sheetName,
                sheetPath = candidate.sheetPath,
                cells = cells,
                targetRow = candidate.row,
                targetColumn = candidate.column,
                headerRow = headerRow,
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
        val imported: CollectionTimeline,
    )

    /**
     * Builds the canonical catalog and everything derived from it, off the main thread.
     *
     * The parsed analysis and the scan result are reused — nothing is re-read from the workbook — and a
     * build that finishes after the user switched workbooks is dropped.
     */
    fun refreshInsights(profile: ColumnMappingProfile? = null) {
        val state = _ui.value
        val analysis = state.analysis
        val model = state.model
        if (analysis == null || model == null) {
            _ui.update { it.insightsFailed("The workbook is still being read. The reports open as soon as it is ready.") }
            return
        }
        val sha256 = state.sha256
        val chosen = profile
            ?: state.profiles.firstOrNull { it.name == state.activeProfile }
            ?: detectedProfile(analysis)
        _ui.update { it.insightsStarted() }
        scope.launch {
            val bundle = withContext(Dispatchers.IO) {
                buildInsights(sha256, analysis, model, chosen, state.importedAt)
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
                        imported = bundle.imported,
                    )
                }
            }
        }
    }

    private fun buildInsights(
        sha256: String,
        analysis: WorkbookAnalysis,
        model: WorkbookCommentImages,
        profile: ColumnMappingProfile,
        importedAt: Long,
    ): InsightsBundle {
        val catalog = WorkbookCatalogBuilder.build(
            workbookSha256 = sha256,
            sheets = analysis.sheets,
            tables = analysis.tables,
            images = analysis.images,
            profile = profile,
        )
        return InsightsBundle(
            catalog = catalog,
            health = HealthReportBuilder.build(catalog, analysis, model),
            analytics = CollectionAnalyticsBuilder.build(catalog),
            publication = CollectionTimelineBuilder.publication(catalog),
            acquisition = CollectionTimelineBuilder.acquisition(catalog),
            imported = CollectionTimelineBuilder.imported(catalog, importedAt.takeIf { it > 0L }),
        )
    }

    /** The mapping the workbook suggests for its own headers. */
    private fun detectedProfile(analysis: WorkbookAnalysis?): ColumnMappingProfile {
        val table = analysis?.tables?.firstOrNull()
            ?: return ColumnMappingProfile(ColumnMappingProfile.DETECTED_NAME, emptyMap())
        return ColumnMappings.detectedProfile(table)
    }

    fun showInsights(tab: InsightsTab) = _ui.update { it.showInsights(tab) }

    // ---- mapping profiles ---------------------------------------------------------------------

    /** Applies a saved profile (null = the auto-detected mapping) and rebuilds every report. */
    fun selectProfile(name: String?) {
        val state = _ui.value
        val saved = state.profiles.firstOrNull { it.name == name }
        _ui.update { it.copy(activeProfile = saved?.name, mappingDirty = false) }
        refreshInsights(saved)
        startMapping(base = saved)
    }

    /** Saves the draft under [name] and applies it. The workbook itself is never modified. */
    fun saveProfile(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            onMessage("Give the profile a name first.", ToastKind.INFO)
            return
        }
        val columns = mappedColumns(_ui.value.mappingDraft)
        if (columns.isEmpty()) {
            onMessage("Map at least one column before saving a profile.", ToastKind.INFO)
            return
        }
        scope.launch {
            val profiles = withContext(Dispatchers.IO) { mappingStore.save(ColumnMappingProfile(trimmed, columns)) }
            _ui.update { it.copy(profiles = profiles, activeProfile = trimmed, mappingDirty = false) }
            refreshInsights(ColumnMappingProfile(trimmed, columns))
            onMessage("Saved the profile \u201C$trimmed\u201D.", ToastKind.OK)
        }
    }

    fun deleteProfile(name: String) {
        scope.launch {
            val profiles = withContext(Dispatchers.IO) { mappingStore.delete(name) }
            val stillActive = _ui.value.activeProfile == name
            _ui.update { it.copy(profiles = profiles, activeProfile = if (stillActive) null else it.activeProfile) }
            if (stillActive) refreshInsights(null)
            onMessage("Deleted the profile \u201C$name\u201D.", ToastKind.OK)
        }
    }

    // ---- Fields: drafting a mapping ------------------------------------------------------------

    fun selectMappingSheet(sheetName: String) = startMapping(sheetName)

    /**
     * Starts (or restarts) the draft for [sheetName] from the applied profile, and previews it.
     *
     * The preview is computed by the same builder the reports use, so what the user confirms is what the
     * other screens will read.
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
        val profile = base
            ?: state.profiles.firstOrNull { it.name == state.activeProfile }
            ?: ColumnMappings.detectedProfile(table)
        previewDraft(CatalogField.ordered.associateWith { profile.columns[it] }, table)
    }

    /** Maps one canonical field to [header] (null = leave it unmapped) and re-previews. */
    fun mapField(field: CatalogField, header: String?) {
        val state = _ui.value
        val table = state.analysis?.tables?.firstOrNull { it.sheetName == state.mappingSheet } ?: return
        val draft = LinkedHashMap(state.mappingDraft)
        draft[field] = header
        previewDraft(draft, table)
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
                headers = table.fields.map { field -> field.name },
                preview = preview.take(MAPPING_PREVIEW),
                issues = ColumnMappings.resolve(table, profile).issues,
            )
        }
    }    /** Applies the draft to every worksheet and rebuilds the reports. Nothing is written to the file. */
    fun applyMapping() {
        val state = _ui.value
        val columns = mappedColumns(state.mappingDraft)
        if (columns.isEmpty()) {
            onMessage("Map at least one column first.", ToastKind.INFO)
            return
        }
        val saved = state.profiles.firstOrNull { it.columns == columns }
        val profile = saved ?: ColumnMappingProfile("Mapping on ${state.mappingSheet ?: "workbook"}", columns)
        _ui.update { it.copy(activeProfile = saved?.name, mappingDirty = false) }
        refreshInsights(profile)
        onMessage("Applied the mapping to this workbook. The file itself is unchanged.", ToastKind.OK)
    }

    fun resetMapping() = startMapping(_ui.value.mappingSheet)

    private fun mappedColumns(draft: Map<CatalogField, String?>): Map<CatalogField, String> =
        CatalogField.ordered.mapNotNull { field -> draft[field]?.let { field to it } }.toMap()

    // ---- filters and navigation ---------------------------------------------------------------

    fun setTimelineMode(mode: TimelineMode) = _ui.update { it.selectTimeline(mode) }

    fun toggleTimelineOrder() = _ui.update { it.toggleTimelineOrder() }

    fun setHealthFilter(severity: HealthSeverity?, category: HealthCategory? = null) =
        _ui.update { it.filterHealth(severity, category) }

    fun setGalleryFilter(filter: GalleryFilter) = _ui.update { it.filterGallery(filter) }

    fun setGallerySort(sort: GallerySort) = _ui.update { it.sortGallery(sort) }

    fun toggleGalleryOrder() = _ui.update { it.toggleGalleryOrder() }

    fun setGallerySheet(sheet: String?) = _ui.update { it.copy(galleryFilter = it.galleryFilter.copy(sheetName = sheet)) }

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