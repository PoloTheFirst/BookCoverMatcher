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
import com.bookcovermatcher.core.wishlist.NewWishlistMatch
import com.bookcovermatcher.core.wishlist.WishlistRecord
import com.bookcovermatcher.core.wishlist.WishlistRepository
import com.bookcovermatcher.data.ModelStore
import com.bookcovermatcher.data.Prefs
import com.bookcovermatcher.data.StagedWorkbook
import com.bookcovermatcher.data.WorkbookFiles
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

    /** Fires once per accepted cover (flash + haptic in the UI). */
    private val _captureEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val captureEvents: SharedFlow<Unit> = _captureEvents.asSharedFlow()

    @Volatile private var embedder: OrtClipEmbedder? = null
    @Volatile private var capturing = false
    @Volatile private var targetEmbedding: FloatArray? = null
    @Volatile private var targetThumb: ByteArray? = null
    @Volatile private var lastRanked: List<RankedMatch> = emptyList()
    private var staged: StagedWorkbook? = null
    private var memo: Pair<String, CatalogIndex>? = null

    private var matchJob: Job? = null
    private var toastJob: Job? = null
    private var promptJob: Job? = null
    private var toastSeq = 0L
    private var promptSeq = 0L
    private var announceCameraReady = false

    init {
        camera.autoIntervalMs = AutoScanConfig().intervalMs
        if (_ui.value.autoScan) camera.autoSink = AutoScanSink { now, frame -> onAutoTick(now, frame) }
        prepareModel()
        refreshWishlist()
    }

    // =====================================================================================
    // Model
    // =====================================================================================

    private fun prepareModel() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var installed = modelStore.installed()
                if (installed == null && modelStore.hasBundledAsset()) {
                    setModel(ModelUi.Installing("Preparing the image model…", null))
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
                setModel(ModelUi.Installing("Copying the model…", null))
                val installed = modelStore.importFrom(uri) { bytes ->
                    setModel(ModelUi.Installing("Copying the model… ${Formatting.fileSize(bytes)}", null))
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
        setModel(ModelUi.Installing("Loading the image model…", null))
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
            _ui.update { it.copy(cameraWanted = true, cameraLive = false, cameraRestart = it.cameraRestart + 1, cameraBadge = "Starting…") }
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
            showToast("Camera ready — tap Scan again to capture.", ToastKind.OK)
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
                val image = withContext(Dispatchers.IO) {
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IOException("Cannot open the selected image.")
                    codec.decodePhoto(bytes, Config.MAX_IMAGE_DECODE) ?: throw IOException("Unsupported or corrupt image file.")
                }
                applyCapture(image, auto = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast("Could not read image: " + (e.message ?: e.toString()), ToastKind.ERR)
            }
        }
    }

    /** Makes [image] the cover to search for: thumbnail + embedding. */
    private fun applyCapture(image: PixelImage, auto: Boolean) {
        capturing = true
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val thumb = codec.makeThumbJpeg(
                    image,
                    Config.THUMB_SCAN_W * Config.THUMB_PIXEL_SCALE,
                    Config.THUMB_SCAN_H * Config.THUMB_PIXEL_SCALE,
                )
                val emb = embedder?.embed(image)
                targetThumb = thumb
                targetEmbedding = emb
                lastRanked = emptyList()
                _ui.update { it.copy(target = TargetUi(thumb, describeEmbedding(emb)), results = ResultsUi.Empty) }
                _captureEvents.tryEmit(Unit)
                if (!auto) showToast("Cover captured — embedding computed.", ToastKind.OK)
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
        return "${e.size}-d · $head …"
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
            setBusy("Reading spreadsheet…", null)
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
                setBusy("Ranking…", null)
                val ranked = withContext(Dispatchers.Default) { catalog.rank(query, Config.TOP_MATCHES) }
                lastRanked = ranked
                _ui.update { it.copy(results = ResultsUi.Ranked(ranked.mapIndexed { i, m -> toResultUi(i, m) }, catalog.size)) }
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

    /** Runs on a background dispatcher; honours cancellation between pictures. */
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
                    val label = if (total == 0) "Extracting images…" else "Embedding images… $done/$total"
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
    // Wishlist
    // =====================================================================================

    fun openWishlist() {
        refreshWishlist()
        _ui.update { it.copy(wishlistOpen = true) }
    }

    fun closeWishlist() = _ui.update { it.copy(wishlistOpen = false) }

    fun addToWishlist() {
        if (targetEmbedding == null) {
            showToast("Scan a cover first.", ToastKind.ERR)
            return
        }
        val matches = lastRanked
        if (matches.isEmpty()) {
            showToast("Run “Link & Find Top 5” before saving to wishlist.", ToastKind.ERR)
            return
        }
        val scanThumb = targetThumb
        viewModelScope.launch {
            try {
                val record = withContext(wishlistDispatcher) {
                    wishlist.add(
                        scanThumb,
                        matches.map { NewWishlistMatch(it.image.cell, it.cosine, it.percent, it.image.source.label, it.image.thumbJpeg) },
                    )
                }
                publishWishlist(withContext(wishlistDispatcher) { wishlist.load() })
                showPrompt(record.id, "Record added to wishlist")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast("Wishlist storage error — could not save.", ToastKind.ERR)
            }
        }
    }

    /** "Remove" on the green bar. */
    fun removePromptRecord() {
        val id = _ui.value.prompt?.recordId
        viewModelScope.launch {
            if (id != null) {
                withContext(wishlistDispatcher) { wishlist.delete(id) }
                publishWishlist(withContext(wishlistDispatcher) { wishlist.load() })
            }
            showPrompt(null, "Removed from wishlist")
            hidePromptSoon(600)
        }
    }

    fun dismissPrompt() = hidePromptSoon(0)

    fun requestDelete(id: String) = _ui.update { it.copy(pendingDeleteId = id) }

    fun cancelDelete() = _ui.update { it.copy(pendingDeleteId = null) }

    fun confirmDelete() {
        val id = _ui.value.pendingDeleteId
        _ui.update { it.copy(pendingDeleteId = null) }
        if (id == null) return
        viewModelScope.launch {
            withContext(wishlistDispatcher) { wishlist.delete(id) }
            publishWishlist(withContext(wishlistDispatcher) { wishlist.load() })
        }
    }

    fun requestRename(id: String) {
        val rec = _ui.value.wishlist.firstOrNull { it.id == id } ?: return
        _ui.update { it.copy(renaming = RenameUi(rec.id, rec.name)) }
    }

    fun cancelRename() = _ui.update { it.copy(renaming = null) }

    fun commitRename(id: String, name: String) {
        _ui.update { it.copy(renaming = null) }
        viewModelScope.launch {
            withContext(wishlistDispatcher) { wishlist.rename(id, name) }
            publishWishlist(withContext(wishlistDispatcher) { wishlist.load() })
        }
    }

    private fun refreshWishlist() {
        viewModelScope.launch {
            try {
                publishWishlist(withContext(wishlistDispatcher) { wishlist.load() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // an unreadable wishlist simply shows as empty
            }
        }
    }

    private fun publishWishlist(records: List<WishlistRecord>) {
        val ui = records.map { r ->
            WishlistRecordUi(
                id = r.id,
                name = r.name,
                timeText = Formatting.timestamp(r.createdAtMillis),
                scanThumb = r.scanThumbName?.let { wishlist.thumbFile(it) }?.takeIf { it.isFile },
                matches = r.matches.map { m ->
                    WishlistMatchUi(m.cell, m.source, Formatting.percent(m.percent) + "%", m.thumbName?.let { wishlist.thumbFile(it) }?.takeIf { it.isFile })
                },
            )
        }
        _ui.update { it.copy(wishlist = ui) }
    }

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
        embedder?.close()
        embedder = null
        wishlistExecutor.shutdown()
        super.onCleared()
    }

    private companion object {
        const val TOAST_MS = 4500L
    }
}
