package com.bookcovermatcher.online

import android.content.Context
import com.bookcovermatcher.BridgeUi
import com.bookcovermatcher.CatalogueUi
import com.bookcovermatcher.OcrUi
import com.bookcovermatcher.OnlineSearchUi
import com.bookcovermatcher.ProductResultUi
import com.bookcovermatcher.ToastKind
import com.bookcovermatcher.core.online.OcrQueryExtractor
import com.bookcovermatcher.core.online.OnlineFailure
import com.bookcovermatcher.core.online.ProductSearchDataSource
import com.bookcovermatcher.core.online.ProductSearchOutcome
import com.bookcovermatcher.core.online.ProductSearchResult
import com.bookcovermatcher.data.OnlineSearchPrefs
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
 * Runs the online-search feature and owns its UI state.
 *
 * Same shape as [com.bookcovermatcher.camera.CameraController](a plain class owned by
 * [com.bookcovermatcher.MainViewModel]) rather than a second ViewModel, because the two halves of
 * the app have to share one captured cover and one toast channel.
 *
 * Two independent network steps, deliberately kept apart:
 *  1. **OCR** of the captured cover through the embedded `chrome-lens-py` package
 *     ([OcrTextSource]) — the only thing that library actually does.
 *  2. **Product search** through a catalogue ([ProductSearchDataSource]) — a different service
 *     with a different failure model. `chrome-lens-py` returns no products, so nothing here asks
 *     it for any.
 *
 * Nothing runs when [OnlineSearchPrefs.enabled] is off, and no step touches the offline matcher.
 */
class OnlineSearchController(
    context: Context,
    private val scope: CoroutineScope,
    private val prefs: OnlineSearchPrefs = OnlineSearchPrefs(context),
    private val bridge: OcrTextSource = ChromeLensPyBridge(PythonRuntime(context)),
    private val catalogue: ProductSearchDataSource = OpenLibraryBooksSource(),
    private val covers: CoverImageLoader = CoverImageLoader(),
    private val onMessage: (String, ToastKind) -> Unit = { _, _ -> },
) {
    private val _ui = MutableStateFlow(
        OnlineSearchUi(
            enabled = prefs.enabled,
            autoSearch = prefs.autoSearch,
            ocrLanguage = prefs.ocrLanguage,
            targetLanguage = prefs.targetLanguage,
        ),
    )
    val ui: StateFlow<OnlineSearchUi> = _ui.asStateFlow()

    /** The captured cover, JPEG-encoded and ready to hand to the OCR bridge. */
    private var capture: ByteArray? = null
    private var job: Job? = null
    private var bridgeChecked = false

    // =========================================================================================
    // Settings
    // =========================================================================================

    fun setEnabled(on: Boolean) {
        prefs.enabled = on
        _ui.update { it.copy(enabled = on, ocr = OcrUi.Idle, catalogue = CatalogueUi.Idle, query = "") }
        if (on) checkBridge() else job?.cancel()
    }

    fun setAutoSearch(on: Boolean) {
        prefs.autoSearch = on
        _ui.update { it.copy(autoSearch = on) }
    }

    fun setOcrLanguage(code: String) {
        prefs.ocrLanguage = code
        _ui.update { it.copy(ocrLanguage = prefs.ocrLanguage) }
    }

    fun setTargetLanguage(code: String) {
        prefs.targetLanguage = code
        _ui.update { it.copy(targetLanguage = prefs.targetLanguage) }
    }

    fun setQuery(text: String) = _ui.update { it.copy(query = text) }

    /** Called by the main screen when the panel becomes visible for the first time. */
    fun checkBridge() {
        if (bridgeChecked || !_ui.value.enabled) return
        bridgeChecked = true
        scope.launch(Dispatchers.IO) {
            val status = try {
                val a = bridge.availability()
                if (a is OcrAvailability.Ready) BridgeUi.Ready(a.libraryName, a.libraryVersion)
                else BridgeUi.Unavailable((a as? OcrAvailability.Unavailable)?.reason ?: "The Lens bridge is unavailable.")
            } catch (t: Throwable) {
                BridgeUi.Unavailable(t.message ?: t.javaClass.simpleName)
            }
            _ui.update { it.copy(bridge = status) }
        }
    }


    // =========================================================================================
    // Step 1 — OCR of the captured cover
    // =========================================================================================

    /**
     * A cover was captured (camera, Auto Scan or a picked photo). Remembers it and, when the
     * feature is on, reads its text.
     *
     * [jpegBytes] is produced by
     * [com.bookcovermatcher.vision.AndroidImageCodec.encodeJpeg] — the 224-pixel thumbnail is far
     * too small for OCR.
     */
    fun onCapture(jpegBytes: ByteArray) {
        capture = jpegBytes
        if (!_ui.value.enabled) return
        readCover()
    }

    /**
     * The cover could not even be encoded, so OCR was never attempted. Reported separately on
     * purpose: "we could not make a JPEG of this" and "Google Lens could not read this" send the
     * user in completely different directions, and collapsing them into one message is what made
     * this feature so hard to diagnose.
     */
    fun onCaptureFailed(message: String, detail: String? = null) {
        OnlineSearchLog.warn("cover encoding failed: ${detail ?: message}")
        _ui.update {
            it.copy(ocr = OcrUi.Failed("${OcrErrorMessages.detail(OcrFailure.BAD_IMAGE)}\n$message"))
        }
        onMessage(OcrErrorMessages.brief(OcrFailure.BAD_IMAGE), ToastKind.ERR)
    }

    /** "Read the cover text" / the retry button on a failed OCR pass. */
    fun readCover() {
        val bytes = capture
        if (bytes == null) {
            onMessage("Scan a book cover first.", ToastKind.ERR)
            return
        }
        if (!_ui.value.enabled) {
            onMessage("Turn online search on first.", ToastKind.INFO)
            return
        }
        if (_ui.value.isBusy) {
            onMessage("Still working — tap Cancel to stop.", ToastKind.INFO)
            return
        }

        job?.cancel()
        job = scope.launch {
            _ui.update { it.copy(ocr = OcrUi.Running("Reading the cover with Google Lens…")) }
            val result = withContext(Dispatchers.IO) {
                runCatching { bridge.recognise(bytes, _ui.value.ocrLanguage, _ui.value.targetLanguage, OCR_TIMEOUT_SECONDS) }
            }
            result.fold(
                onSuccess = { text -> applyOcrText(text) },
                onFailure = { e -> reportOcrFailure(e) },
            )
        }
    }

    /**
     * One toast per failure **category**, so the message names the thing that actually went
     * wrong. The full reason and the Python traceback are already in the panel and the log; the
     * toast stays short enough to read while the phone is still pointed at a shelf.
     *
     * [OcrFailure.INTERNAL] is the only case that still says "Could not read the cover", and it
     * is now genuinely the unexpected case rather than the catch-all it used to be.
     */
    private fun reportOcrFailure(e: Throwable) {
        val ocr = e as? OcrException
        val category = ocr?.failure ?: OcrFailure.INTERNAL
        val shown = ocr?.message ?: e.message ?: OcrErrorMessages.detail(OcrFailure.INTERNAL)
        if (ocr == null) OnlineSearchLog.ocrFailed(0, e)
        _ui.update { it.copy(ocr = OcrUi.Failed(shown)) }
        onMessage(OcrErrorMessages.brief(category), ToastKind.ERR)
    }

    private fun applyOcrText(text: OcrText) {
        if (text.isEmpty) {
            _ui.update { it.copy(ocr = OcrUi.NoText(text.libraryVersion), query = "") }
            onMessage("No text found on the cover.", ToastKind.INFO)
            return
        }
        // The translation is searched when it was requested: a Japanese cover is more likely to be
        // in an English-language catalogue under its translated title.
        val searchable = text.translatedText?.takeIf { it.isNotBlank() } ?: text.text
        val suggestion = OcrQueryExtractor.suggest(searchable, text.blockHeights)

        _ui.update {
            it.copy(
                ocr = OcrUi.Done(searchable, text.detectedLanguage, suggestion?.query.orEmpty(), text.libraryVersion),
                query = suggestion?.query.orEmpty(),
            )
        }
        when {
            suggestion == null -> onMessage("Text found, but no title could be picked out.", ToastKind.INFO)
            prefs.autoSearch -> search(suggestion.query)
            else -> onMessage("Cover text read.", ToastKind.OK)
        }
    }

    // =========================================================================================
    // Step 2 — catalogue (product) search
    // =========================================================================================

    /** "Search the catalogue" / auto-invoked after a successful OCR pass. */
    fun search(query: String = _ui.value.query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            onMessage("Nothing to search for — read the cover or type a title.", ToastKind.ERR)
            return
        }
        if (_ui.value.isBusy) return

        _ui.update { it.copy(query = trimmed, catalogue = CatalogueUi.Running(trimmed)) }
        job = scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { catalogue.search(trimmed, prefs.resultLimit) }
            }
            outcome.fold(
                onSuccess = { applyOutcome(it) },
                onFailure = { e ->
                    _ui.update { it.copy(catalogue = CatalogueUi.Failed(e.message ?: "The catalogue search failed.")) }
                    onMessage("Catalogue search failed.", ToastKind.ERR)
                },
            )
        }
    }

    private fun applyOutcome(outcome: ProductSearchOutcome) {
        when (outcome) {
            is ProductSearchOutcome.Found -> {
                _ui.update {
                    it.copy(
                        catalogue = CatalogueUi.Found(
                            query = outcome.query,
                            items = outcome.results.map { r -> r.toUi(null) },
                            totalAvailable = outcome.totalAvailable,
                            source = catalogue.name,
                        ),
                    )
                }
                onMessage("${outcome.results.size} result(s) from ${catalogue.name}.", ToastKind.OK)
                loadCovers(outcome.results)
            }
            is ProductSearchOutcome.Empty -> {
                _ui.update { it.copy(catalogue = CatalogueUi.Empty(outcome.query, catalogue.name)) }
                onMessage("No match in ${catalogue.name}.", ToastKind.INFO)
            }
            is ProductSearchOutcome.Failed -> {
                _ui.update { it.copy(catalogue = CatalogueUi.Failed(messageFor(outcome.failure, outcome.message))) }
                onMessage("Catalogue unavailable.", ToastKind.ERR)
            }
        }
    }

    /** Covers arrive after the text, one row at a time, so the list is readable immediately. */
    private fun loadCovers(results: List<ProductSearchResult>) {
        scope.launch {
            results.forEachIndexed { index, item ->
                val bytes = withContext(Dispatchers.IO) { runCatching { covers.load(item.coverUrl) }.getOrNull() }
                    ?: return@forEachIndexed
                _ui.update { state ->
                    val current = state.catalogue as? CatalogueUi.Found ?: return@update state
                    if (index >= current.items.size) return@update state
                    val items = current.items.toMutableList()
                    items[index] = item.toUi(bytes)
                    state.copy(catalogue = current.copy(items = items))
                }
            }
        }
    }

    private fun ProductSearchResult.toUi(coverJpeg: ByteArray?) = ProductResultUi(
        id = id,
        title = title,
        authors = authorLine,
        meta = metaLine,
        languages = languages.joinToString(", "),
        coverJpeg = coverJpeg,
        detailUrl = detailUrl,
        source = source,
    )

    private fun messageFor(failure: OnlineFailure, detail: String): String = when (failure) {
        OnlineFailure.OFFLINE -> "No connection to the catalogue. The offline matcher still works."
        OnlineFailure.TIMEOUT -> "The catalogue did not answer in time."
        OnlineFailure.RATE_LIMITED -> "The catalogue is throttling requests — try again in a minute."
        OnlineFailure.SERVER_ERROR -> "The catalogue is having trouble right now."
        OnlineFailure.BAD_RESPONSE -> "The catalogue sent something unexpected."
        OnlineFailure.NOT_CONFIGURED -> "No product catalogue is configured."
    } + " ($detail)"

    // =========================================================================================

    fun cancel() {
        job?.cancel()
        job = null
        _ui.update {
            it.copy(
                ocr = if (it.ocr is OcrUi.Running) OcrUi.Idle else it.ocr,
                catalogue = if (it.catalogue is CatalogueUi.Running) CatalogueUi.Idle else it.catalogue,
            )
        }
        onMessage("Online search cancelled.", ToastKind.INFO)
    }

    /** Called from `MainViewModel.onCleared()`. The Python interpreter itself is process-wide. */
    fun shutdown() {
        job?.cancel()
        job = null
    }

    private companion object {
        /** Generous: the first Lens call of a session also pays for TLS setup. */
        const val OCR_TIMEOUT_SECONDS = 60
    }
}

