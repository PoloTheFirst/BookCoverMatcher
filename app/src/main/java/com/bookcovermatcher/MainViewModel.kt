package com.bookcovermatcher

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bookcovermatcher.camera.AutoScanSink
import com.bookcovermatcher.camera.CameraController
import com.bookcovermatcher.core.Config
import com.bookcovermatcher.core.autoscan.AutoScanConfig
import com.bookcovermatcher.core.autoscan.AutoScanEngine
import com.bookcovermatcher.core.autoscan.ScanBadge
import com.bookcovermatcher.core.autoscan.ScanTick
import com.bookcovermatcher.core.excel.CellRange
import com.bookcovermatcher.core.excel.InvalidRangeException
import com.bookcovermatcher.core.excel.JavaZipSource
import com.bookcovermatcher.core.excel.WorkbookImageExtractor
import com.bookcovermatcher.core.format.Formatting
import com.bookcovermatcher.core.search.CatalogBuilder
import com.bookcovermatcher.core.search.CatalogIndex
import com.bookcovermatcher.core.search.CatalogIndexStore
import com.bookcovermatcher.core.search.IndexCache
import com.bookcovermatcher.core.search.RankedMatch
import com.bookcovermatcher.core.vision.ImageOps
import com.bookcovermatcher.core.vision.PixelImage
import com.bookcovermatcher.core.wishlist.BatchReport
import com.bookcovermatcher.core.wishlist.BookmarkRules
import com.bookcovermatcher.core.wishlist.InvalidItemException
import com.bookcovermatcher.core.wishlist.ItemDraft
import com.bookcovermatcher.core.wishlist.ItemKind
import com.bookcovermatcher.core.wishlist.Library
import com.bookcovermatcher.core.wishlist.MatchType
import com.bookcovermatcher.core.wishlist.SavedItem
import com.bookcovermatcher.core.wishlist.SearchSession
import com.bookcovermatcher.core.wishlist.SessionDraft
import com.bookcovermatcher.core.wishlist.SessionKind
import com.bookcovermatcher.core.wishlist.WishlistFolder
import com.bookcovermatcher.core.wishlist.WishlistRepository
import com.bookcovermatcher.data.ImageFiles
import com.bookcovermatcher.data.ModelStore
import com.bookcovermatcher.data.Prefs
import com.bookcovermatcher.data.StagedWorkbook
import com.bookcovermatcher.data.WorkbookFiles
import com.bookcovermatcher.online.OnlineSearchController
import com.bookcovermatcher.vision.AndroidImageCodec
import com.bookcovermatcher.vision.OrtClipEmbedder
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds everything the screen shows and runs the three jobs of the app:
 * capture a cover (camera / Auto Scan / photo), match it against a workbook range, keep the wishlist.
 *
 * Counterpart of v1's `main.js`, `auto-scan.js` and `wishlist.js` state handling.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val modelStore = ModelStore(app)
    private val codec = AndroidImageCodec()
    private val indexCache = IndexCache(File(app.cacheDir, "index"))
    private val wishlist = WishlistRepository(File(app.filesDir, "wishlist"))
    private val wishlistExecutor = Executors.newSingleThreadExecutor()
    private val wishlistDispatcher = wishlistExecutor.asCoroutineDispatcher()

    val camera = CameraController(app)
    private val engine = AutoScanEngine(AutoScanConfig())

    private val _ui = MutableStateFlow(UiState(autoScan = prefs.autoScan, rangeText = prefs.range))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /**
     * The optional online feature (the Search destination's Online tab). Owned here so it sees every
     * captured cover and shares the toast channel, but it keeps its own state flow ([online]) and
     * never touches the offline matcher. Everything in it is inert until the user switches it on.
     */
    val online = OnlineSearchController(app, viewModelScope, onMessage = ::showToast)

    /** Fires once per accepted cover (flash + haptic in the UI). */
    private val _captureEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val captureEvents: SharedFlow<Unit> = _captureEvents.asSharedFlow()

    @Volatile private var embedder: OrtClipEmbedder? = null
    @Volatile private var capturing = false
    @Volatile private var targetEmbedding: FloatArray? = null
    @Volatile private var lastRanked: List<RankedMatch> = emptyList()
    private var staged: StagedWorkbook? = null
    private var memo: Pair<String, CatalogIndex>? = null

    private var matchJob: Job? = null
    private var toastJob: Job? = null
    private var promptJob: Job? = null
    private var toastSeq = 0L
    private var promptSeq = 0L
    private var announceCameraReady = false

    /** The picker's pending work: nothing is written until the user confirms a folder. */
    private var pickerMode: PickerMode = PickerMode.ADD_ITEMS
    private var pickerDrafts: List<ItemDraft> = emptyList()
    private var pickerExistingIds: List<String> = emptyList()
    private var pickerScanThumb: ByteArray? = null
    private var pickerQuery: String? = null

    /** What the green bar's "Remove" undoes: exactly the add that produced it. */
    private var lastAdd: LastAdd? = null


    init {
        camera.autoIntervalMs = AutoScanConfig().intervalMs
        if (_ui.value.autoScan) camera.autoSink = AutoScanSink { now, frame -> onAutoTick(now, frame) }
        prepareModel()
        refreshLibrary()
        // The online controller owns its own StateFlow; mirror it into UiState. Without this the
        // panel keeps drawing the default (off) state and never shows the switch the user flipped
        // - the actual reason online search appeared nonfunctional.
        viewModelScope.launch {
            online.ui.collect { onlineState ->
                _ui.update { state -> state.copy(online = onlineState) }
            }
        }
    }

    // =====================================================================================
    // Model
    // =====================================================================================

    private fun prepareModel() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var installed = modelStore.installed()
                if (installed == null && modelStore.hasBundledAsset()) {
                    setModel(ModelUi.Installing("Preparing the image model", null))
                    installed = modelStore.installBundled()
                }
                if (installed == null) setModel(ModelUi.Missing) else openEmbedder(installed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setModel(ModelUi.Failed(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /** Copies a model file chosen by the user into the app and loads it. */
    fun importModel(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                setModel(ModelUi.Installing("Copying the model", null))
                val installed = modelStore.importFrom(uri) { bytes ->
                    setModel(ModelUi.Installing("Copying the model ${Formatting.fileSize(bytes)}", null))
                }
                openEmbedder(installed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setModel(ModelUi.Failed(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun openEmbedder(installed: ModelStore.Installed) {
        setModel(ModelUi.Installing("Loading the image model", null))
        try {
            val e = OrtClipEmbedder.open(installed.file, installed.fingerprint)
            embedder?.close()
            embedder = e
            setModel(ModelUi.Ready(e.dim, installed.sizeBytes))
        } catch (t: Throwable) {
            modelStore.remove()
            val msg = t.message ?: t.javaClass.simpleName
            setModel(ModelUi.Failed("The model could not be loaded: $msg"))
        }
    }

    private fun setModel(m: ModelUi) = _ui.update { it.copy(model = m) }

    // =====================================================================================
    // Camera + capture
    // =====================================================================================

    /** Called by the UI before it asks for the camera permission; [announce] shows "Camera ready" once live. */
    fun noteCameraStartIntent(announce: Boolean) { announceCameraReady = announce }

    fun onCameraPermission(granted: Boolean) {
        if (granted) {
            _ui.update { it.copy(cameraWanted = true, cameraLive = false, cameraRestart = it.cameraRestart + 1, cameraBadge = "Starting") }
        } else {
            announceCameraReady = false
            _ui.update { it.copy(cameraWanted = false, cameraLive = false, cameraBadge = "Camera off") }
            showToast("Camera permission denied. Upload an image instead.", ToastKind.ERR)
        }
    }

    fun onCameraLive() {
        synchronized(engine) { engine.reset() }
        _ui.update {
            it.copy(cameraLive = true, scanTick = null, cameraBadge = if (it.autoScan) ScanBadge.AUTO_ON else ScanBadge.LIVE)
        }
        if (announceCameraReady) {
            announceCameraReady = false
            showToast("Camera ready \u2014 tap Scan again to capture.", ToastKind.OK)
        }
    }

    fun onCameraError(message: String) {
        announceCameraReady = false
        _ui.update { it.copy(cameraWanted = false, cameraLive = false, scanTick = null, cameraBadge = "Camera off") }
        showToast("Camera error: $message", ToastKind.ERR)
    }

    /** "Scan Book Cover" while the camera is running: grab the current frame. */
    fun captureManual() {
        if (_ui.value.isBusy) return
        viewModelScope.launch {
            try {
                val frame = camera.grabGuideFrame()
                applyCapture(frame, auto = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast(e.message ?: "Could not capture the frame.", ToastKind.ERR)
            }
        }
    }

    /** A photo chosen from the gallery instead of the camera. */
    fun onImagePicked(uri: Uri) {
        viewModelScope.launch {
            try {
                val picked = withContext(Dispatchers.IO) {
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IOException("Cannot open the selected image.")
                    val image = codec.decodePhoto(bytes, Config.MAX_IMAGE_DECODE)
                        ?: throw IOException("Unsupported or corrupt image file.")
                    // The picker's provider knows the real file name; a content:// URI does not.
                    val label = ImageFiles.labelFor(
                        resolvedName = ImageFiles.displayName(getApplication(), uri),
                        lastPathSegment = uri.lastPathSegment,
                        mimeType = getApplication<Application>().contentResolver.getType(uri),
                    )
                    image to label
                }
                // The name is applied only after a successful decode: a failed pick must not show a
                // file name next to the previous cover.
                applyCapture(picked.first, auto = false, imageName = picked.second)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast("Could not read image: " + (e.message ?: e.toString()), ToastKind.ERR)
            }
        }
    }

    /** Makes [image] the cover to search for: thumbnail + embedding. */
    private fun applyCapture(image: PixelImage, auto: Boolean, imageName: String? = null) {
        capturing = true
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val thumb = codec.makeThumbJpeg(
                    image,
                    Config.THUMB_SCAN_W * Config.THUMB_PIXEL_SCALE,
                    Config.THUMB_SCAN_H * Config.THUMB_PIXEL_SCALE,
                )
                val emb = embedder?.embed(image)
                targetEmbedding = emb
                lastRanked = emptyList()
                _ui.update {
                    it.copy(
                        target = TargetUi(thumb, describeEmbedding(emb)),
                        // A camera capture has no file name; that clears a previous file's name.
                        imageName = imageName,
                        results = ResultsUi.Empty,
                    )
                }
                _captureEvents.tryEmit(Unit)
                if (!auto) showToast("Cover captured \u2014 embedding computed.", ToastKind.OK)

                // Online tab. Returns immediately when the feature is off, and a failure here can
                // never affect the offline result above. Encoding is the app's own job, so an
                // encoding failure is reported as such rather than reaching Python and coming
                // back looking like an OCR problem.
                if (online.ui.value.enabled) {
                    try {
                        online.onCapture(codec.encodeJpeg(image, AndroidImageCodec.OCR_MAX_EDGE))
                    } catch (e: Exception) {
                        online.onCaptureFailed(
                            e.message ?: "The cover could not be encoded.",
                            e.toString(),
                        )
                    }
                } else {
                    // The feature is off, but the previous cover's results are still in memory:
                    // selecting a new cover drops them so they can never be shown against it.
                    online.onCoverChanged()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast("Could not process the capture: " + (e.message ?: e.toString()), ToastKind.ERR)
            } finally {
                capturing = false
            }
        }
    }

    private fun describeEmbedding(e: FloatArray?): String {
        if (e == null) return "Model not loaded"
        val head = e.take(6).joinToString(" ") { String.format(java.util.Locale.US, "%+.3f", it) }
        return "${e.size}-d \u00B7 $head "
    }

    // =====================================================================================
    // Auto Scan
    // =====================================================================================

    fun toggleAutoScan() {
        if (_ui.value.isBusy) return
        val next = !_ui.value.autoScan
        prefs.autoScan = next
        synchronized(engine) { engine.reset() }
        camera.autoSink = if (next) AutoScanSink { now, frame -> onAutoTick(now, frame) } else null
        _ui.update {
            it.copy(
                autoScan = next,
                scanTick = null,
                cameraBadge = when {
                    next && it.cameraLive -> ScanBadge.AUTO_ON
                    next -> ScanBadge.AUTO_ARMED
                    it.cameraLive -> ScanBadge.LIVE
                    else -> ScanBadge.TAP_TO_START
                },
            )
        }
    }

    private class AutoStep(val tick: ScanTick, val captured: PixelImage?)

    /** Runs on the camera analysis thread, at most once per `intervalMs`. */
    private fun onAutoTick(now: Long, frame: () -> PixelImage?) {
        val s = _ui.value
        if (!s.autoScan || s.isBusy || capturing) return

        val step: AutoStep? = synchronized(engine) {
            if (engine.isCoolingDown(now)) {
                AutoStep(engine.cooldownTick(), null)
            } else {
                val img = frame()
                if (img == null) {
                    null
                } else {
                    val px64 = ImageOps.areaResizeArgb(img.argb, img.width, img.height, AutoScanConfig.ANALYSIS_SIZE, AutoScanConfig.ANALYSIS_SIZE)
                    val px32 = ImageOps.areaResizeArgb(img.argb, img.width, img.height, AutoScanConfig.SIGNATURE_SIZE, AutoScanConfig.SIGNATURE_SIZE)
                    val tick = engine.step(now, px64, px32)
                    AutoStep(tick, if (tick.capture) img else null)
                }
            }
        }
        if (step == null) return

        _ui.update { it.copy(scanTick = step.tick, cameraBadge = ScanBadge.text(step.tick)) }
        step.captured?.let { applyCapture(it, auto = true) }
    }

    // =====================================================================================
    // Spreadsheet + matching
    // =====================================================================================

    fun setRange(text: String) {
        prefs.range = text
        _ui.update { it.copy(rangeText = text) }
    }

    fun onWorkbookPicked(uri: Uri) {
        viewModelScope.launch {
            _ui.update { it.copy(workbookLoading = true) }
            try {
                val st = withContext(Dispatchers.IO) {
                    val s = WorkbookFiles.stage(getApplication<Application>(), uri)
                    openWorkbook(s.file).close()          // fail early on files that are not workbooks
                    s
                }
                staged = st
                _ui.update { it.copy(workbook = WorkbookUi(st.displayName, Formatting.fileSize(st.sizeBytes)), workbookLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                staged = null
                _ui.update { it.copy(workbook = null, workbookLoading = false) }
                showToast("Error: " + (e.message ?: e.toString()), ToastKind.ERR)
            }
        }
    }

    fun cancelMatch() {
        matchJob?.cancel()
    }

    /** "Link & Find Top 5". */
    fun findTopMatches() {
        if (_ui.value.isBusy) return

        val query = targetEmbedding
        if (query == null) {
            showToast("Scan a book cover (or upload an image) first.", ToastKind.ERR)
            return
        }
        val workbook = staged
        if (workbook == null) {
            showToast("Choose an .xlsx or .xlsm spreadsheet first.", ToastKind.ERR)
            return
        }
        val rangeText = _ui.value.rangeText
        val range = try {
            CellRange.parse(rangeText)
        } catch (e: InvalidRangeException) {
            showToast(e.message ?: CellRange.ERROR_MESSAGE, ToastKind.ERR)
            return
        }
        val model = embedder
        if (model == null) {
            showToast("The image model is not loaded yet.", ToastKind.ERR)
            return
        }

        matchJob = viewModelScope.launch {
            setBusy("Reading spreadsheet", null)
            try {
                val catalog = withContext(Dispatchers.Default) { loadOrBuildCatalog(workbook, range, model) }
                if (catalog.size == 0) {
                    lastRanked = emptyList()
                    _ui.update { it.copy(results = ResultsUi.Empty) }
                    showToast(
                        "No images found inside " + rangeText.trim().uppercase() + " (checked floating images and comment fills).",
                        ToastKind.ERR,
                    )
                    return@launch
                }
                setBusy("Ranking", null)
                val ranked = withContext(Dispatchers.Default) { catalog.rank(query, Config.TOP_MATCHES) }
                lastRanked = ranked
                _ui.update {
                    it.copy(
                        results = ResultsUi.Ranked(ranked.mapIndexed { i, m -> toResultUi(i, m) }, catalog.size),
                        // A finished comparison is shown on the Workbook tab: the user just ran it, so
                        // its output must not land behind another tab. Switching later stays the user's
                        // choice and never triggers a new run.
                        searchTab = SearchResultsTab.WORKBOOK,
                    )
                }
                showToast("Compared against ${catalog.size} image(s).", ToastKind.OK)
            } catch (e: CancellationException) {
                showToast("Search cancelled.", ToastKind.INFO)
                throw e
            } catch (e: Exception) {
                showToast("Error: " + (e.message ?: e.toString()), ToastKind.ERR)
            } finally {
                _ui.update { it.copy(busy = null) }
            }
        }
    }

    private fun toResultUi(index: Int, m: RankedMatch) = ResultUi(
        rank = index + 1,
        cell = m.image.cell,
        source = m.image.source.label,
        cosine = m.cosine,
        percent = m.percent,
        level = m.level,
        thumbJpeg = m.image.thumbJpeg,
    )

    private fun setBusy(label: String, progress: Float?) = _ui.update { it.copy(busy = BusyUi(label, progress)) }

    /** Runs on a background dispatcher; honors cancellation between pictures. */
    private suspend fun loadOrBuildCatalog(workbook: StagedWorkbook, range: CellRange, model: OrtClipEmbedder): CatalogIndex {
        val key = CatalogIndexStore.cacheKey(
            workbookSha256 = workbook.sha256,
            rangeKey = "${range.r1}:${range.r2}:${range.c1}:${range.c2}",
            modelFingerprint = model.fingerprint,
            includeFloating = Config.EXTRACT_FLOATING_IMAGES,
        )
        memo?.let { if (it.first == key) return it.second }
        indexCache.get(key)?.let {
            memo = key to it
            return it
        }

        val ctx = currentCoroutineContext()
        val catalog = openWorkbook(workbook.file).use { zip ->
            CatalogBuilder(codec, model).build(
                zip = zip,
                range = range,
                onProgress = { done, total ->
                    val label = if (total == 0) "Extracting images" else "Embedding images $done/$total"
                    setBusy(label, if (total == 0) null else done.toFloat() / total)
                },
                checkCancelled = { ctx.ensureActive() },
            )
        }
        ctx.ensureActive()
        if (catalog.size > 0) {
            indexCache.put(key, catalog)
            memo = key to catalog
        }
        return catalog
    }

    private fun openWorkbook(file: File): JavaZipSource {
        val zip = try {
            JavaZipSource(ZipFile(file))
        } catch (e: IOException) {
            throw IllegalStateException("Not a valid .xlsx/.xlsm workbook.")
        }
        try {
            WorkbookImageExtractor.requireWorkbook(zip)
        } catch (e: Exception) {
            zip.close()
            throw e
        }
        return zip
    }

    // =====================================================================================
    // Phase 2 navigation
    // =====================================================================================

    /** Bottom navigation. Selecting a tab never writes, clears or resets saved data. */
    fun selectTab(tab: AppTab) {
        _ui.update { it.copy(tab = tab) }
        if (tab == AppTab.WISHLIST) refreshLibrary()
    }

    fun selectWishlistSection(section: WishlistSection) {
        _ui.update {
            it.copy(
                wishlistSection = section,
                folderDetail = null,
                sessionDetail = null,
                selection = null,
                batchTarget = null,
                batchDelete = null,
            )
        }
        refreshLibrary()
    }

    /**
     * Which Search list is open (workbook comparison / online results / this search's bookmarks).
     * Pure navigation: switching lists never cancels a request, clears a cache or touches saved data.
     */
    fun selectSearchTab(tab: SearchResultsTab) {
        _ui.update { it.copy(searchTab = tab) }
    }

    // =====================================================================================
    // Wishlist: folders  sessions  results, plus direct items
    // =====================================================================================

    /** Opens one folder and shows its sessions and the items saved directly into it. */
    fun openFolder(id: String) {
        viewModelScope.launch {
            val library = withContext(wishlistDispatcher) { wishlist.library() }
            val detail = buildFolderDetail(library, id) ?: return@launch
            _ui.update {
                it.copy(
                    wishlistSection = WishlistSection.FOLDERS,
                    sessionDetail = null,
                    folderDetail = detail,
                    selection = null,
                )
            }
        }
    }

    fun closeFolder() = _ui.update { it.copy(folderDetail = null, sessionDetail = null, selection = null) }

    /** Opens one session: its results, bookmarked ones first. */
    fun openSession(id: String) {
        viewModelScope.launch {
            val library = withContext(wishlistDispatcher) { wishlist.library() }
            val detail = buildSessionDetail(library, id) ?: return@launch
            _ui.update { it.copy(sessionDetail = detail, selection = null) }
        }
    }

    fun closeSession() = _ui.update { it.copy(sessionDetail = null, selection = null) }

    /** `+ New folder`: a real, user-named folder; the rename dialog gives it a name immediately. */
    fun createFolder() {
        viewModelScope.launch {
            try {
                val folder = withContext(wishlistDispatcher) { wishlist.createFolder("") }
                refreshLibrary()
                _ui.update { it.copy(renaming = RenameUi(folder.id, folder.name, "Name this folder")) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast("Wishlist storage error \u2014 could not create the folder.", ToastKind.ERR)
            }
        }
    }

    fun requestRenameFolder(id: String) {
        val folder = _ui.value.folders.firstOrNull { it.id == id } ?: return
        if (folder.reserved) return
        _ui.update { it.copy(renaming = RenameUi(folder.id, folder.name, "Rename folder")) }
    }

    fun cancelRename() = _ui.update { it.copy(renaming = null) }

    fun commitRename(id: String, name: String) {
        _ui.update { it.copy(renaming = null) }
        viewModelScope.launch {
            try {
                withContext(wishlistDispatcher) { wishlist.renameFolder(id, name) }
                refreshLibrary()
                _ui.value.folderDetail?.let { detail ->
                    if (detail.id == id) {
                        _ui.update { it.copy(folderDetail = detail.copy(name = name.trim().ifEmpty { detail.name })) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast("Wishlist storage error \u2014 could not rename the folder.", ToastKind.ERR)
            }
        }
    }

    // =====================================================================================
    // Saving: one offline session, one result profile, one item
    // =====================================================================================

    /**
     * v2.1.0's "Add to wishlist", restored correctly: the **whole matching operation** is saved as
     * one session inside a folder the user picks. It never creates a folder on its own, and the five
     * matches are not turned into five independently removable items.
     */
    fun saveOfflineSession() {
        if (targetEmbedding == null) {
            showToast("Scan a cover first.", ToastKind.ERR)
            return
        }
        val matches = lastRanked
        if (matches.isEmpty()) {
            showToast("Run Link & Find Top 5 before saving a session.", ToastKind.ERR)
            return
        }
        openPicker(
            mode = PickerMode.SAVE_SESSION,
            headline = "Save this scan as a session",
            drafts = matches.map { it.toDraft() },
            existingIds = emptyList(),
        )
    }

    /**
     * "Add result profile to wishlist": the whole result profile (offline top-5 + online results)
     * becomes **one session** in the chosen folder. The user still chooses the destination and
     * nothing is written until Confirm.
     */
    fun addResultProfileToWishlist() {
        val drafts = buildList {
            lastRanked.forEach { add(it.toDraft()) }
            (online.ui.value.catalogue as? CatalogueUi.Found)?.items?.forEach { add(it.toDraft()) }
        }
        if (drafts.isEmpty()) {
            showToast("Nothing to add yet \u2014 run \u201CLink & Find Top 5\u201D or an online search first.", ToastKind.ERR)
            return
        }
        openPicker(
            mode = PickerMode.SAVE_RESULT_PROFILE,
            headline = "Add entire result profile to wishlist",
            drafts = drafts,
            existingIds = emptyList(),
        )
    }

    /** `Add to wishlist` on one result card: exactly that one item, into a folder the user picks. */
    fun addResultToWishlist(item: ProductResultUi) {
        openPicker(
            mode = PickerMode.ADD_ITEMS,
            headline = "Add ${item.title} to wishlist",
            drafts = listOf(item.toDraft()),
            existingIds = emptyList(),
        )
    }

    /** `Add to wishlist` on a saved bookmark  works with no scan, workbook or live search. */
    fun addSavedItemToWishlist(itemId: String) {
        val item = _ui.value.bookmarks.firstOrNull { it.id == itemId }
            ?: _ui.value.folderDetail?.directItems?.firstOrNull { it.id == itemId }
            ?: _ui.value.sessionDetail?.items?.firstOrNull { it.id == itemId }
        openPicker(
            mode = PickerMode.ADD_ITEMS,
            headline = "Add ${item?.title ?: "this bookmark"} to wishlist",
            drafts = emptyList(),
            existingIds = listOf(itemId),
        )
    }

    /** Opens the shared folder picker. Nothing is written until [confirmPicker]. */
    private fun openPicker(
        mode: PickerMode,
        headline: String,
        drafts: List<ItemDraft>,
        existingIds: List<String>,
    ) {
        viewModelScope.launch {
            val library = withContext(wishlistDispatcher) { wishlist.library() }
            val draftsByFingerprint = drafts.mapNotNull { draft -> library.itemByFingerprint(draft.fingerprint)?.id }
            val wantedIds = existingIds + draftsByFingerprint
            pickerMode = mode
            pickerDrafts = drafts
            pickerExistingIds = existingIds
            pickerScanThumb = if (mode == PickerMode.SAVE_SESSION) _ui.value.target?.thumbJpeg else null
            pickerQuery = if (mode == PickerMode.SAVE_RESULT_PROFILE) {
                (online.ui.value.catalogue as? CatalogueUi.Found)?.query
                    ?: online.ui.value.query.trim().takeIf { it.isNotEmpty() }
            } else {
                null
            }
            val folders = library.profileFolders.map { folder ->
                PickerFolderUi(
                    id = folder.id,
                    name = folder.name,
                    itemCount = library.itemCount(folder.id),
                    alreadyContains = wantedIds.any { id ->
                        library.memberships.any { it.itemId == id && it.collectionId == folder.id }
                    },
                )
            }
            _ui.update {
                it.copy(picker = PickerUi(mode, headline, drafts.size + existingIds.size, folders, folders.firstOrNull()?.id))
            }
        }
    }

    fun closePicker() {
        pickerDrafts = emptyList()
        pickerExistingIds = emptyList()
        pickerScanThumb = null
        pickerQuery = null
        _ui.update { it.copy(picker = null) }
    }

    /**
     * Applies the picker's pending work in one store write:
     *  * [PickerMode.SAVE_SESSION]/[PickerMode.SAVE_RESULT_PROFILE]  one new session;
     *  * [PickerMode.ADD_ITEMS]  direct folder memberships (one item, or a saved bookmark).
     *
     * A folder typed into the dialog is a genuine, explicit folder creation. Undo removes the
     * session/memberships it just created, never the folder.
     */
    fun confirmPicker(profileId: String?, newProfileName: String) {
        _ui.value.picker ?: return
        _ui.update { it.copy(picker = null) }
        val mode = pickerMode
        val drafts = pickerDrafts
        val existing = pickerExistingIds
        val scanThumb = pickerScanThumb
        val query = pickerQuery
        pickerDrafts = emptyList()
        pickerExistingIds = emptyList()
        pickerScanThumb = null
        pickerQuery = null

        viewModelScope.launch {
            try {
                val outcome = withContext(wishlistDispatcher) {
                    val typedName = newProfileName.trim()
                    val folder: WishlistFolder = if (typedName.isNotEmpty()) {
                        wishlist.createFolder(typedName)
                    } else {
                        val picked = profileId ?: return@withContext null
                        wishlist.library().folder(picked)?.takeIf { !it.isReserved } ?: return@withContext null
                    }
                    when (mode) {
                        PickerMode.ADD_ITEMS -> {
                            var added = 0
                            var already = 0
                            val itemIds = ArrayList<String>()
                            if (drafts.isNotEmpty()) {
                                val report = wishlist.addDrafts(drafts, folder.id)
                                added += report.added
                                already += report.alreadyThere
                                itemIds.addAll(report.itemIds)
                            }
                            existing.forEach { id ->
                                if (wishlist.addExistingItemToFolder(id, folder.id)) {
                                    added++
                                    itemIds.add(id)
                                } else {
                                    already++
                                }
                            }
                            AddOutcome(folder.id, null, added, already, itemIds)
                        }
                        PickerMode.SAVE_SESSION, PickerMode.SAVE_RESULT_PROFILE -> {
                            if (drafts.isEmpty()) return@withContext null
                            val kind = when {
                                mode == PickerMode.SAVE_SESSION -> SessionKind.OFFLINE_MATCH
                                drafts.any { it.kind == ItemKind.WORKBOOK_MATCH } -> SessionKind.RESULT_PROFILE
                                else -> SessionKind.ONLINE_SEARCH
                            }
                            val report = wishlist.saveSession(
                                SessionDraft(
                                    folderId = folder.id,
                                    kind = kind,
                                    title = "",
                                    query = query,
                                    scanThumbJpeg = scanThumb,
                                    results = drafts,
                                ),
                            )
                            AddOutcome(folder.id, report.sessionId, report.added, report.alreadyThere, report.itemIds)
                        }
                    }
                }
                if (outcome == null) {
                    showToast("Pick a folder first.", ToastKind.ERR)
                    return@launch
                }
                val library = withContext(wishlistDispatcher) { wishlist.library() }
                publish(library)
                val folderName = library.folder(outcome.folderId)?.name ?: "wishlist"
                showToast(
                    when {
                        outcome.added == 0 -> "Already in " + folderName + " (" + outcome.already + " item(s))."
                        outcome.already > 0 -> "Added " + outcome.added + " item(s) to " + folderName + " - " + outcome.already + " already there."
                        else -> "Added " + outcome.added + " item(s) to " + folderName + "."
                    },
                    if (outcome.added == 0) ToastKind.INFO else ToastKind.OK,
                )
                if (outcome.added > 0) {
                    lastAdd = LastAdd(outcome.folderId, outcome.sessionId, outcome.itemIds)
                    val what = if (outcome.sessionId != null) "Session added to " + folderName else "Added to " + folderName
                    showPrompt(outcome.sessionId ?: outcome.folderId, what)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: InvalidItemException) {
                showToast(e.message ?: "That item cannot be saved.", ToastKind.ERR)
            } catch (e: Exception) {
                showToast("Wishlist storage error - could not save.", ToastKind.ERR)
            }
        }
    }


    /** "Undo" on the green bar: removes exactly the session/memberships the add produced. */
    fun removePromptRecord() {
        val last = lastAdd
        lastAdd = null
        viewModelScope.launch {
            if (last != null) {
                try {
                    withContext(wishlistDispatcher) {
                        if (last.sessionId != null) {
                            wishlist.deleteSession(last.sessionId)
                        } else {
                            last.itemIds.forEach { wishlist.removeMembership(it, last.folderId) }
                        }
                    }
                    refreshLibrary()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    showToast("Wishlist storage error - could not undo.", ToastKind.ERR)
                }
            }
            showPrompt(null, "Removed from wishlist")
            hidePromptSoon(600)
        }
    }

    fun dismissPrompt() = hidePromptSoon(0)

    // -------------------------------------------------------------------------------------
    // Bookmarks (independent of folders and sessions)
    // -------------------------------------------------------------------------------------

    /** `Bookmark` on a result card: toggles membership in `Bookmarks` only, idempotently. */
    fun toggleBookmark(item: ProductResultUi) {
        viewModelScope.launch {
            try {
                val nowBookmarked = withContext(wishlistDispatcher) {
                    val library = wishlist.library()
                    val existing = library.itemByFingerprint(item.key)
                        ?: library.items.firstOrNull { it.url != null && it.url == item.detailUrl }
                    if (existing != null && library.isBookmarked(existing.id)) {
                        wishlist.removeBookmark(existing.id)
                        false
                    } else {
                        wishlist.bookmark(item.toDraft())
                        true
                    }
                }
                refreshLibrary()
                showToast(
                    if (nowBookmarked) "Bookmark added - saved in Bookmarks." else "Bookmark removed. Wishlist entries are untouched.",
                    if (nowBookmarked) ToastKind.OK else ToastKind.INFO,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast("Wishlist storage error - could not change the bookmark.", ToastKind.ERR)
            }
        }
    }

    /**
     * `Bookmark all results`: every online result currently on screen into `Bookmarks`, one write.
     * A bookmark is not a wishlist entry, so this never opens the picker and never creates a folder.
     */
    fun bookmarkAllResults() {
        val items = (online.ui.value.catalogue as? CatalogueUi.Found)?.items.orEmpty()
        if (items.isEmpty()) {
            showToast("No online results to bookmark yet.", ToastKind.ERR)
            return
        }
        viewModelScope.launch {
            try {
                val report = withContext(wishlistDispatcher) {
                    wishlist.bookmarkAll(items.map { it.toDraft() })
                }
                refreshLibrary()
                showToast(
                    when {
                        report.added == 0 -> "All ${report.alreadyThere} result(s) were already bookmarked."
                        report.alreadyThere > 0 -> "Bookmarked ${report.added} result(s) - ${report.alreadyThere} already there."
                        else -> "Bookmarked ${report.added} result(s) in Bookmarks."
                    },
                    if (report.added == 0) ToastKind.INFO else ToastKind.OK,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast("Wishlist storage error - could not bookmark the results.", ToastKind.ERR)
            }
        }
    }




    // -------------------------------------------------------------------------------------
    // Custom bookmark form
    // -------------------------------------------------------------------------------------

    fun openCustomBookmark() = _ui.update { it.copy(customBookmark = CustomBookmarkUi()) }

    fun closeCustomBookmark() = _ui.update { it.copy(customBookmark = null) }

    /**
     * `+ Create bookmark`: stores exactly what was typed. No metadata, thumbnail or price is fetched,
     * and the values that were typed are not marked as verified. The bookmark is independent of any
     * scan, session or folder.
     */
    fun createCustomBookmark(
        title: String,
        url: String,
        authors: String,
        description: String,
        identifier: String,
        price: String,
    ) {
        val problem = BookmarkRules.validate(title, url)
        if (problem != null) {
            _ui.update { it.copy(customBookmark = CustomBookmarkUi(problem)) }
            return
        }
        viewModelScope.launch {
            try {
                withContext(wishlistDispatcher) {
                    wishlist.bookmark(
                        ItemDraft(
                            kind = ItemKind.MANUAL_BOOKMARK,
                            title = title,
                            url = url.trim(),
                            authors = authors.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                            description = description,
                            identifier = identifier,
                            price = price,
                            matchType = MatchType.MANUAL,
                            matchTypeVerified = false,
                        ),
                    )
                }
                _ui.update {
                    it.copy(customBookmark = null, tab = AppTab.WISHLIST, wishlistSection = WishlistSection.BOOKMARKS)
                }
                refreshLibrary()
                showToast("Bookmark created.", ToastKind.OK)
            } catch (e: CancellationException) {
                throw e
            } catch (e: InvalidItemException) {
                _ui.update { it.copy(customBookmark = CustomBookmarkUi(e.message)) }
            } catch (e: Exception) {
                showToast("Wishlist storage error - could not save.", ToastKind.ERR)
            }
        }
    }


    // -------------------------------------------------------------------------------------
    // Removal
    // -------------------------------------------------------------------------------------

    fun requestDeleteFolder(id: String) {
        val folder = _ui.value.folders.firstOrNull { it.id == id } ?: return
        if (folder.reserved) return
        _ui.update { it.copy(pendingDelete = PendingDeleteUi(DeleteKind.FOLDER, id, folder.name)) }
    }

    fun requestDeleteSession(id: String) {
        val fromFolder = _ui.value.folderDetail?.sessions?.firstOrNull { it.id == id }
        val fromDetail = _ui.value.sessionDetail?.takeIf { it.id == id }
        val title = fromFolder?.title ?: fromDetail?.title ?: return
        _ui.update { it.copy(pendingDelete = PendingDeleteUi(DeleteKind.SESSION, id, title)) }
    }

    fun requestDeleteBookmark(itemId: String) {
        val item = _ui.value.bookmarks.firstOrNull { it.id == itemId } ?: return
        _ui.update { it.copy(pendingDelete = PendingDeleteUi(DeleteKind.BOOKMARK, itemId, item.title)) }
    }

    /** An item leaves one folder; `Bookmarks` and every other folder keep their memberships. */
    fun requestRemoveFromFolder(itemId: String, folderId: String) {
        val item = _ui.value.folderDetail?.directItems?.firstOrNull { it.id == itemId } ?: return
        _ui.update { it.copy(pendingDelete = PendingDeleteUi(DeleteKind.MEMBERSHIP, itemId, item.title, folderId)) }
    }

    fun cancelDelete() = _ui.update { it.copy(pendingDelete = null) }

    fun confirmDelete() {
        val target = _ui.value.pendingDelete ?: return
        _ui.update { it.copy(pendingDelete = null) }
        viewModelScope.launch {
            try {
                withContext(wishlistDispatcher) {
                    when (target.kind) {
                        DeleteKind.FOLDER -> wishlist.deleteFolder(target.id)
                        DeleteKind.SESSION -> wishlist.deleteSession(target.id)
                        DeleteKind.BOOKMARK -> wishlist.removeBookmark(target.id)
                        DeleteKind.MEMBERSHIP -> target.collectionId?.let { wishlist.removeMembership(target.id, it) }
                    }
                }
                if (target.kind == DeleteKind.FOLDER && _ui.value.folderDetail?.id == target.id) {
                    _ui.update { it.copy(folderDetail = null, sessionDetail = null) }
                }
                if (target.kind == DeleteKind.SESSION) {
                    _ui.update {
                        it.copy(
                            sessionDetail = null,
                            folderDetail = it.folderDetail?.copy(
                                sessions = it.folderDetail.sessions.filterNot { s -> s.id == target.id },
                            ),
                        )
                    }
                }
                if (target.kind == DeleteKind.MEMBERSHIP) {
                    _ui.update {
                        it.copy(
                            folderDetail = it.folderDetail?.copy(
                                directItems = it.folderDetail.directItems.filterNot { item -> item.id == target.id },
                            ),
                        )
                    }
                }
                refreshLibrary()
                showToast(
                    when (target.kind) {
                        DeleteKind.FOLDER -> "Folder deleted. Shared items stay in their other folders."
                        DeleteKind.SESSION -> "Session deleted. Bookmarks and other sessions are untouched."
                        DeleteKind.BOOKMARK -> "Bookmark removed. Wishlist entries are untouched."
                        DeleteKind.MEMBERSHIP -> "Removed from this folder."
                    },
                    ToastKind.INFO,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast("Wishlist storage error - could not delete.", ToastKind.ERR)
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // Multi-selection and batch actions
    //
    // Selection is navigation state, never stored data: entering it writes nothing, leaving it
    // writes nothing, and every batch is one repository call that writes once (or not at all).
    // -------------------------------------------------------------------------------------

    /** Enters selection mode for one list. Nothing is written until an action is confirmed. */
    fun startSelection(kind: BatchKind, containerId: String? = null) {
        _ui.update {
            it.copy(
                selection = SelectionUi(kind, SelectionLogic.clear(), containerId),
                batchTarget = null,
                batchDelete = null,
            )
        }
    }

    /**
     * Long-press entry point: starts selection mode for this list *and* selects the held row, so a
     * long-press is a one-step way in and never opens the item by accident.
     */
    fun startSelectionWith(kind: BatchKind, id: String, containerId: String? = null) {
        val current = _ui.value.selection
        if (current != null && current.kind == kind && current.containerId == containerId) {
            toggleSelected(id)
            return
        }
        _ui.update {
            it.copy(
                selection = SelectionUi(kind, setOf(id), containerId),
                batchTarget = null,
                batchDelete = null,
            )
        }
    }

    fun toggleSelected(id: String) {
        _ui.update { state ->
            val selection = state.selection ?: return@update state
            state.copy(selection = selection.copy(ids = SelectionLogic.toggle(selection.ids, id)))
        }
    }

    /** Selects exactly what this screen shows, so "All" can never select an invisible row. */
    fun selectAllVisible() {
        val state = _ui.value
        val selection = state.selection ?: return
        val visible = when (selection.kind) {
            BatchKind.FOLDERS -> state.folders.map { it.id }
            BatchKind.BOOKMARKS -> state.bookmarks.map { it.id }
            BatchKind.SESSIONS -> state.folderDetail?.sessions?.map { it.id }.orEmpty()
            BatchKind.ITEMS -> state.folderDetail?.directItems?.map { it.id }.orEmpty()
            BatchKind.SESSION_ITEMS ->
                state.sessionDetail?.items?.filterNot { it.comparisonOnly }?.map { it.id }.orEmpty()
        }
        _ui.update { it.copy(selection = selection.copy(ids = SelectionLogic.selectAll(visible))) }
    }

    fun clearSelection() = _ui.update { it.copy(selection = it.selection?.copy(ids = SelectionLogic.clear())) }

    /** Leaves selection mode, dropping any pending action. Never touches saved data. */
    fun exitSelection() = _ui.update { it.copy(selection = null, batchTarget = null, batchDelete = null) }

    /** Copy: folder copies need no target (they stay where they are), everything else picks one. */
    fun requestBatchCopy() = openBatchTarget(BatchAction.COPY)

    /** Move: only offered for sessions, folder items and session results. */
    fun requestBatchMove() = openBatchTarget(BatchAction.MOVE)

    private fun openBatchTarget(action: BatchAction) {
        val selection = _ui.value.selection ?: return
        if (!selection.active) {
            showToast("Select at least one ${SelectionLogic.noun(selection.kind)} first.", ToastKind.INFO)
            return
        }
        if (action == BatchAction.MOVE && !SelectionLogic.canMove(selection.kind)) {
            // The UI hides Move for these lists; this guard keeps a stale click from doing the wrong
            // thing (a folder "move" would otherwise become a copy).
            showToast("Move is not available for ${SelectionLogic.noun(selection.kind)}.", ToastKind.INFO)
            return
        }
        if (selection.kind == BatchKind.FOLDERS) {
            // A folder has no parent in this model, so a folder copy needs no destination.
            runBatch(action, selection.kind, selection.ids.toList(), selection.containerId, null)
            return
        }
        val folders = _ui.value.folders
        if (folders.isEmpty()) {
            showToast("Create a wishlist folder first.", ToastKind.ERR)
            return
        }
        val verb = if (action == BatchAction.MOVE) "Move" else "Copy"
        _ui.update {
            it.copy(
                batchTarget = BatchTargetUi(
                    action = action,
                    kind = selection.kind,
                    ids = selection.ids.toList(),
                    containerId = selection.containerId,
                    title = "$verb ${selection.count} ${SelectionLogic.noun(selection.kind)} to",
                    folders = folders.map { folder -> PickerFolderUi(folder.id, folder.name, folder.itemCount, false) },
                    defaultFolderId = folders.firstOrNull { folder ->
                        action != BatchAction.MOVE || folder.id != selection.containerId
                    }?.id,
                ),
            )
        }
    }

    fun cancelBatchTarget() = _ui.update { it.copy(batchTarget = null) }

    fun confirmBatchTarget(folderId: String) {
        val target = _ui.value.batchTarget ?: return
        _ui.update { it.copy(batchTarget = null) }
        runBatch(target.action, target.kind, target.ids, target.containerId, folderId)
    }

    /** Asks before anything destructive; the dialog states the count and the exact scope. */
    fun requestBatchDelete() {
        val selection = _ui.value.selection ?: return
        if (!selection.active) {
            showToast("Select at least one item first.", ToastKind.INFO)
            return
        }
        val n = selection.count
        val (title, detail) = when (selection.kind) {
            BatchKind.FOLDERS -> "Delete $n folder(s)?" to
                "Their sessions and saved items go with them. Bookmarked items and items in other folders stay."
            BatchKind.SESSIONS -> "Delete $n session(s)?" to
                "Their results are removed with them. Bookmarks and other sessions are untouched."
            BatchKind.BOOKMARKS -> "Remove $n bookmark(s)?" to
                "Only the bookmark is removed. Wishlist folders and sessions keep their copy."
            BatchKind.ITEMS -> "Remove $n item(s) from this folder?" to
                "The bookmark and any other folder membership stay."
            BatchKind.SESSION_ITEMS -> "Remove $n result(s) from this session?" to
                "Bookmarks and folder memberships stay. Comparison rows are never removed one by one."
        }
        _ui.update {
            it.copy(
                batchDelete = BatchDeleteUi(
                    kind = selection.kind,
                    ids = selection.ids.toList(),
                    containerId = selection.containerId,
                    title = title,
                    detail = detail,
                ),
            )
        }
    }

    fun cancelBatchDelete() = _ui.update { it.copy(batchDelete = null) }

    fun confirmBatchDelete() {
        val pending = _ui.value.batchDelete ?: return
        _ui.update { it.copy(batchDelete = null) }
        runBatch(BatchAction.DELETE, pending.kind, pending.ids, pending.containerId, null)
    }

    /**
     * Runs one batch and then re-reads the store, so the screen always shows what was actually
     * persisted - a partial failure cannot leave the UI ahead of the file. [targetFolderId] is null
     * for a delete and for a folder copy (which needs no destination).
     */
    private fun runBatch(
        action: BatchAction,
        kind: BatchKind,
        ids: List<String>,
        containerId: String?,
        targetFolderId: String?,
    ) {
        viewModelScope.launch {
            try {
                val report = withContext(wishlistDispatcher) {
                    executeBatch(action, kind, ids, containerId, targetFolderId)
                }
                _ui.update { it.copy(selection = null, batchTarget = null, batchDelete = null) }
                refreshLibrary()
                showToast(batchMessage(action, kind, report), batchToastKind(report))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(selection = null, batchTarget = null, batchDelete = null) }
                refreshLibrary()
                showToast("Wishlist storage error \u2014 nothing was changed.", ToastKind.ERR)
            }
        }
    }

    private fun executeBatch(
        action: BatchAction,
        kind: BatchKind,
        ids: List<String>,
        containerId: String?,
        targetFolderId: String?,
    ): BatchReport {
        val folder = targetFolderId.orEmpty()
        val container = containerId.orEmpty()
        if (action == BatchAction.DELETE) {
            return when (kind) {
                BatchKind.FOLDERS -> wishlist.deleteFolders(ids)
                BatchKind.SESSIONS -> wishlist.deleteSessions(ids)
                BatchKind.BOOKMARKS -> wishlist.deleteBookmarks(ids)
                BatchKind.ITEMS -> wishlist.removeItemsFromFolder(ids, container)
                BatchKind.SESSION_ITEMS -> wishlist.removeSessionEntries(ids, container)
            }
        }
        return when (kind) {
            BatchKind.FOLDERS -> wishlist.copyFolders(ids)
            BatchKind.BOOKMARKS -> wishlist.copyItemsToFolder(ids, folder)
            BatchKind.SESSIONS ->
                if (action == BatchAction.MOVE) {
                    wishlist.moveSessionsToFolder(ids, folder)
                } else {
                    wishlist.copySessionsToFolder(ids, folder)
                }
            BatchKind.ITEMS ->
                if (action == BatchAction.MOVE) {
                    wishlist.moveItemsToFolder(ids, container, folder)
                } else {
                    wishlist.copyItemsToFolder(ids, folder)
                }
            BatchKind.SESSION_ITEMS ->
                if (action == BatchAction.MOVE) {
                    wishlist.moveSessionEntries(ids, container, folder)
                } else {
                    wishlist.copySessionEntries(ids, container, folder)
                }
        }
    }

    private fun batchNoun(kind: BatchKind): String = SelectionLogic.noun(kind)

    private fun batchMessage(action: BatchAction, kind: BatchKind, report: BatchReport): String {
        val verb = when (action) {
            BatchAction.COPY -> "Copied"
            BatchAction.MOVE -> "Moved"
            BatchAction.DELETE -> "Removed"
        }
        val base = "$verb ${report.changed} ${batchNoun(kind)}."
        val skipped = if (report.skipped > 0) {
            " ${report.skipped} skipped (already there, or read-only comparison rows)."
        } else {
            ""
        }
        val failed = if (report.failed > 0) " ${report.failed} failed." else ""
        val reason = report.message?.let { " $it" }.orEmpty()
        return base + reason + skipped + failed
    }

    private fun batchToastKind(report: BatchReport): ToastKind = when {
        report.failed > 0 -> ToastKind.ERR
        report.changed == 0 -> ToastKind.INFO
        else -> ToastKind.OK
    }

    /** "Clear session cache": temporary online results only - never a bookmark or wishlist item. */
    fun clearSessionCache() {
        online.clearSession()
        refreshLibrary()
    }

    private class AddOutcome(
        val folderId: String,
        val sessionId: String?,
        val added: Int,
        val already: Int,
        val itemIds: List<String>,
    )

    private class LastAdd(val folderId: String, val sessionId: String?, val itemIds: List<String>)



    // -------------------------------------------------------------------------------------
    // Reading and publishing the store
    // -------------------------------------------------------------------------------------

    private fun refreshLibrary() {
        viewModelScope.launch {
            try {
                publish(withContext(wishlistDispatcher) { wishlist.library() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // an unreadable store simply shows as empty; the file is kept for recovery
            }
        }
    }

    /**
     * Publishes the store. Profiles, the `Bookmarks` collection and the per-result indicators all
     * come from this one read, so the counts, the lists and the badges cannot disagree.
     */
    /**
     * Publishes the store. Folders, their sessions, the `Bookmarks` folder and the per-result
     * indicators all come from this one read, so the counts, the lists and the badges cannot
     * disagree. The currently open folder/session is rebuilt too, so a delete cannot leave a stale
     * screen behind.
     */
    private fun publish(library: Library) {
        val folderCards = library.profileFolders.map { folder ->
            FolderUi(
                id = folder.id,
                name = folder.name,
                sessionCount = library.sessionCount(folder.id),
                itemCount = library.itemCount(folder.id),
                cover = library.itemsInFolder(folder.id).firstNotNullOfOrNull { thumbFile(it) },
                reserved = false,
            )
        }
        val detail = _ui.value.folderDetail?.let { open -> buildFolderDetail(library, open.id) }
        val sessionDetail = _ui.value.sessionDetail?.let { open -> buildSessionDetail(library, open.id) }
        val bookmarkCards = library.bookmarkedItems().map { item -> item.toUi(library) }
        // A selection must never outlive the rows it points at: after a delete, a move or a refresh,
        // ids that are no longer visible are dropped here, so no action can target an invisible row.
        val liveSelection = _ui.value.selection?.let { selection ->
            val visible = when (selection.kind) {
                BatchKind.FOLDERS -> folderCards.map { it.id }
                BatchKind.BOOKMARKS -> bookmarkCards.map { it.id }
                BatchKind.SESSIONS -> detail?.sessions?.map { it.id }.orEmpty()
                BatchKind.ITEMS -> detail?.directItems?.map { it.id }.orEmpty()
                BatchKind.SESSION_ITEMS ->
                    sessionDetail?.items?.filterNot { it.comparisonOnly }?.map { it.id }.orEmpty()
            }
            selection.copy(ids = SelectionLogic.reconcile(selection.ids, SelectionLogic.eligible(selection.kind, visible)))
        }
        _ui.update {
            it.copy(
                folders = folderCards,
                unfiledSessions = library.unfiledSessions().map { session -> session.toUi(library) },
                bookmarks = bookmarkCards,
                folderDetail = detail,
                sessionDetail = sessionDetail,
                selection = liveSelection,
                bookmarkedKeys = library.items.filter { item -> library.isBookmarked(item.id) }
                    .map { item -> item.fingerprint }.toSet(),
                wishlistedKeys = library.items.filter { item -> library.isWishlisted(item.id) }
                    .map { item -> item.fingerprint }.toSet(),
            )
        }
    }

    private fun buildFolderDetail(library: Library, id: String): FolderDetailUi? {
        val folder = library.folder(id) ?: return null
        if (folder.isReserved) return null
        return FolderDetailUi(
            id = folder.id,
            name = folder.name,
            sessions = library.sessionsIn(folder.id).map { session -> session.toUi(library) },
            directItems = library.directItems(folder.id).map { item -> item.toUi(library) },
        )
    }

    private fun buildSessionDetail(library: Library, id: String): SessionDetailUi? {
        val session = library.session(id) ?: return null
        val items = library.sessionItems(id)
        val bookmarked = items.count { library.isBookmarked(it.id) }
        val meta = buildString {
            append(Formatting.timestamp(session.createdAtMillis))
            append(" \u00B7 ").append(items.size).append(" result(s)")
            if (bookmarked > 0) append(" \u00B7 ").append(bookmarked).append(" bookmarked first")
        }
        return SessionDetailUi(
            id = session.id,
            folderId = session.folderId,
            title = session.title,
            kindLabel = session.kind.label(),
            meta = meta,
            query = session.query,
            items = items.map { item -> item.toUi(library) },
            comparisonOnly = session.kind == SessionKind.OFFLINE_MATCH,
        )
    }

    private fun SearchSession.toUi(library: Library): SessionUi {
        val items = library.sessionItems(id)
        val bookmarked = items.count { library.isBookmarked(it.id) }
        val meta = buildString {
            append(Formatting.timestamp(createdAtMillis))
            append(" \u00B7 ").append(items.size).append(" result(s)")
            if (bookmarked > 0) append(" \u00B7 ").append(bookmarked).append(" bookmarked")
        }
        return SessionUi(
            id = id,
            title = title,
            kind = kind,
            kindLabel = kind.label(),
            meta = meta,
            query = query,
            scanThumb = scanThumbName?.let { wishlist.thumbFile(it) }?.takeIf { it.isFile }
                ?: items.firstNotNullOfOrNull { thumbFile(it) },
            resultCount = items.size,
            bookmarkedCount = bookmarked,
        )
    }

    private fun SessionKind.label(): String = when (this) {
        SessionKind.OFFLINE_MATCH -> "Offline comparison"
        SessionKind.ONLINE_SEARCH -> "Online search"
        SessionKind.RESULT_PROFILE -> "Offline + online"
    }

    private fun thumbFile(item: SavedItem): File? = item.thumbName?.let { wishlist.thumbFile(it) }?.takeIf { it.isFile }

    private fun SavedItem.toUi(library: Library) = SavedItemUi(
        id = id,
        title = title,
        url = url,
        domain = sourceDomain,
        provider = provider,
        meta = listOfNotNull(
            authors.joinToString(", ").ifEmpty { null },
            identifier?.let { "id $it" },
            cell?.let { "cell $it" },
            percent?.let { Formatting.percent(it) + "%" },
            price,
            availability,
        ).joinToString(" \u00B7 "),
        kind = kind,
        kindLabel = when (kind) {
            ItemKind.WORKBOOK_MATCH -> "Workbook match"
            ItemKind.ONLINE_RESULT -> "Online result"
            ItemKind.MANUAL_BOOKMARK -> "Custom bookmark"
        },
        bookmarked = library.isBookmarked(id),
        thumb = thumbFile(this),
        addedText = Formatting.timestamp(createdAtMillis),
        cell = cell,
        percentText = percent?.let { Formatting.percent(it) + "%" },
    )

    /** An online result as the store sees it. Workbook-only fields stay null. */
    private fun ProductResultUi.toDraft() = ItemDraft(
        kind = ItemKind.ONLINE_RESULT,
        title = title,
        url = detailUrl,
        provider = provider,
        providerKey = key,
        identifier = identifier,
        description = description,
        authors = authors.split(',').map { it.trim() }.filter { it.isNotEmpty() && it != "Unknown author" },
        price = price,
        availability = availability,
        matchType = MatchType.TEXT_QUERY,
        matchTypeVerified = true,
        thumbJpeg = coverJpeg,
    )

    /** One workbook match as the store sees it: cell, cosine and percent are real, the rest is not invented. */
    private fun RankedMatch.toDraft() = ItemDraft(
        kind = ItemKind.WORKBOOK_MATCH,
        title = image.cell,
        description = image.source.label,
        matchType = MatchType.WORKBOOK,
        matchTypeVerified = true,
        cell = image.cell,
        cosine = cosine,
        percent = percent,
        thumbJpeg = image.thumbJpeg,
    )


    // =====================================================================================
    // Toast + prompt (always touched on the main thread)
    // =====================================================================================

    fun showToast(text: String, kind: ToastKind) {
        viewModelScope.launch {
            val id = ++toastSeq
            _ui.update { it.copy(toast = ToastUi(id, text, kind)) }
            toastJob?.cancel()
            toastJob = launch {
                delay(TOAST_MS)
                _ui.update { s -> if (s.toast?.id == id) s.copy(toast = null) else s }
            }
        }
    }

    private fun showPrompt(recordId: String?, text: String) {
        val id = ++promptSeq
        _ui.update { it.copy(prompt = PromptUi(id, recordId, text)) }
        hidePromptSoon(Config.WISHLIST_PROMPT_MS, id)
    }

    private fun hidePromptSoon(afterMs: Long, onlyId: Long? = null) {
        promptJob?.cancel()
        promptJob = viewModelScope.launch {
            if (afterMs > 0) delay(afterMs)
            _ui.update { s -> if (onlyId == null || s.prompt?.id == onlyId) s.copy(prompt = null) else s }
        }
    }

    override fun onCleared() {
        camera.shutdown()
        online.shutdown()
        embedder?.close()
        embedder = null
        wishlistExecutor.shutdown()
        super.onCleared()
    }

    private companion object {
        const val TOAST_MS = 4500L
    }
}
