package com.bookcovermatcher.online

import android.content.Context
import com.bookcovermatcher.BridgeUi
import com.bookcovermatcher.CatalogueUi
import com.bookcovermatcher.OcrUi
import com.bookcovermatcher.OnlineSearchUi
import com.bookcovermatcher.ProductResultUi
import com.bookcovermatcher.ToastKind
import com.bookcovermatcher.core.online.ImageFingerprint
import com.bookcovermatcher.core.online.OcrQueryExtractor
import com.bookcovermatcher.core.online.OnlineAggregator
import com.bookcovermatcher.core.online.OnlineFailure
import com.bookcovermatcher.core.online.ProductSearchDataSource
import com.bookcovermatcher.core.online.ProductSearchOutcome
import com.bookcovermatcher.core.online.ProductSearchResult
import com.bookcovermatcher.core.online.SessionCache
import com.bookcovermatcher.core.wishlist.ItemIdentity
import com.bookcovermatcher.core.wishlist.MatchType
import com.bookcovermatcher.patchCoverById
import com.bookcovermatcher.data.OnlineSearchPrefs
import kotlinx.coroutines.CancellationException
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
    /**
     * Every catalogue the search queries. Two today: Open Library and Google Books. They are
     * catalogues, not shops, so no price or stock is ever fabricated. A failure of one is reported
     * without hiding the results of the other.
     */
    private val catalogues: List<ProductSearchDataSource> = listOf(OpenLibraryBooksSource(), GoogleBooksSource()),
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

    /**
     * Results of the current capture, in memory only, keyed by the SHA-256 of the cover JPEG.
     * Selecting another cover drops the previous results: a card on screen must never belong to a
     * cover the user is no longer looking at. Nothing here can touch the wishlist store.
     */
    private val session = SessionCache<CachedSearch>()

    private class CachedSearch(
        val query: String,
        val results: List<ProductSearchResult>,
        val totalAvailable: Int?,
        val source: String,
        val note: String?,
    )

    // =========================================================================================
    // Settings
    // =========================================================================================

    fun setEnabled(on: Boolean) {
        prefs.enabled = on
        _ui.update { it.copy(enabled = on, ocr = OcrUi.Idle, catalogue = CatalogueUi.Idle, query = "") }
        if (on) {
            checkBridge()
        } else {
            job?.cancel()
            // switching the feature off hides the results immediately and drops the session cache
            session.invalidate()
        }
    }

    /** "Clear session cache": temporary results only, never a bookmark, item or membership. */
    fun clearSession() {
        session.invalidate()
        _ui.update { it.copy(catalogue = CatalogueUi.Idle) }
        onMessage("Session cache cleared — saved bookmarks and wishlist items are untouched.", ToastKind.INFO)
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
        val fingerprint = ImageFingerprint.of(jpegBytes)
        val cached = session.select(fingerprint)
        // A different cover: the cards on screen cannot belong to it, so they are dropped at once.
        if (cached == null) _ui.update { it.copy(catalogue = CatalogueUi.Idle) }
        if (!_ui.value.enabled) return
        readCover()
    }

    /**
     * A different cover was selected while the feature is off. Drops the previous capture's results
     * without encoding anything: they cannot belong to the new cover.
     */
    fun onCoverChanged() {
        session.invalidate()
        _ui.update { it.copy(catalogue = CatalogueUi.Idle) }
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
            val token = session.token()
            _ui.update { it.copy(ocr = OcrUi.Running("Reading the cover with Google Lens…")) }
            val result = withContext(Dispatchers.IO) {
                runCatching { bridge.recognise(bytes, _ui.value.ocrLanguage, _ui.value.targetLanguage, OCR_TIMEOUT_SECONDS) }
            }
            // A Lens request already inside Python cannot be preempted; its answer is ignored when
            // another cover has been selected in the meantime, which is what the token checks.
            result.fold(
                onSuccess = { text -> if (session.isCurrent(token)) applyOcrText(text) },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    if (session.isCurrent(token)) reportOcrFailure(e)
                },
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

    // Step 2 - catalogue (product) search
    // =========================================================================================

    /** "Search the catalogues" / auto-invoked after a successful OCR pass. */
    fun search(query: String = _ui.value.query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            onMessage("Nothing to search for - read the cover or type a title.", ToastKind.ERR)
            return
        }
        if (!_ui.value.enabled) {
            onMessage("Turn online search on first.", ToastKind.INFO)
            return
        }
        if (_ui.value.isBusy) return

        val fingerprint = capture?.let { ImageFingerprint.of(it) }
        if (fingerprint != null) {
            val cached = session.cached(fingerprint)
            if (cached != null && cached.query == trimmed) {
                // Same cover, same query, session still alive: reuse instead of asking again.
                applyResults(
                    results = cached.results,
                    query = cached.query,
                    totalAvailable = cached.totalAvailable,
                    fingerprint = fingerprint,
                    fromCache = true,
                    source = cached.source,
                    note = cached.note,
                )
                loadCovers(cached.results, session.token())
                return
            }
        }

        _ui.update { it.copy(query = trimmed, catalogue = CatalogueUi.Running(trimmed)) }
        val token = session.token()
        job = scope.launch {
            val outcomes = withContext(Dispatchers.IO) {
                catalogues.map { source ->
                    source to runCatching { source.search(trimmed, prefs.resultLimit) }
                }
            }
            if (!session.isCurrent(token)) return@launch
            applyOutcomes(trimmed, outcomes, fingerprint, token)
        }
    }

    /**
     * Merges every catalogue that answered. One failing catalogue does not hide another's results:
     * the failure is reported in the panel note and in the toast, and only a total failure becomes
     * [CatalogueUi.Failed].
     */
    private fun applyOutcomes(
        query: String,
        outcomes: List<Pair<ProductSearchDataSource, Result<ProductSearchOutcome>>>,
        fingerprint: String?,
        token: Long,
    ) {
        val found = ArrayList<ProductSearchResult>()
        val answered = ArrayList<String>()
        val failures = ArrayList<String>()
        var total: Int? = null
        var totalCount = 0

        outcomes.forEach { (source, attempt) ->
            val outcome = attempt.getOrElse { error ->
                ProductSearchOutcome.Failed(
                    OnlineFailure.INTERNAL,
                    error.message ?: error.javaClass.simpleName,
                )
            }
            when (outcome) {
                is ProductSearchOutcome.Found -> {
                    found += outcome.results
                    answered += source.name
                    outcome.totalAvailable?.let {
                        total = (total ?: 0) + it
                        totalCount++
                    }
                }
                is ProductSearchOutcome.Empty -> answered += source.name
                is ProductSearchOutcome.Failed -> failures += "${source.name}: ${messageFor(outcome.failure, outcome.message)}"
            }
        }

        val sourceLabel = answered.distinct().joinToString(" + ")
            .ifEmpty { catalogues.joinToString(" + ") { it.name } }
        if (answered.isEmpty() && found.isEmpty()) {
            val reason = failures.firstOrNull() ?: "No catalogue answered."
            _ui.update { it.copy(catalogue = CatalogueUi.Failed(reason)) }
            onMessage("Catalogue search failed.", ToastKind.ERR)
            return
        }

        val merged = OnlineAggregator.merge(listOf(found), limit = prefs.resultLimit)
        if (merged.isEmpty()) {
            _ui.update { it.copy(catalogue = CatalogueUi.Empty(query, sourceLabel)) }
            onMessage("No match in $sourceLabel.", ToastKind.INFO)
            return
        }
        val note = failures.takeIf { it.isNotEmpty() }?.joinToString("; ")
        applyResults(
            results = merged,
            query = query,
            totalAvailable = total.takeIf { totalCount == 1 },
            fingerprint = fingerprint,
            fromCache = false,
            source = sourceLabel,
            note = note,
        )
        loadCovers(merged, token)
    }

    /** Draws [results], which is exactly the list the "N results" message counts. */
    private fun applyResults(
        results: List<ProductSearchResult>,
        query: String,
        totalAvailable: Int?,
        fingerprint: String?,
        fromCache: Boolean,
        source: String,
        note: String?,
    ) {
        if (fingerprint != null && !fromCache) {
            session.store(fingerprint, CachedSearch(query, results, totalAvailable, source, note))
        }
        _ui.update {
            it.copy(
                catalogue = CatalogueUi.Found(
                    query = query,
                    items = results.map { r -> r.toUi(null) },
                    totalAvailable = totalAvailable,
                    source = source,
                    note = note,
                ),
            )
        }
        val suffix = if (fromCache) " (cached for this cover)" else ""
        onMessage("${results.size} result(s) from $source$suffix.", ToastKind.OK)
    }


    /** Covers arrive after the text, one at a time, so the list is readable immediately. */
    private fun loadCovers(results: List<ProductSearchResult>, token: Long) {
        scope.launch {
            for (item in results) {
                val bytes = withContext(Dispatchers.IO) { runCatching { covers.load(item.coverUrl) }.getOrNull() }
                    ?: continue
                if (!session.isCurrent(token)) return@launch
                _ui.update { state ->
                    val current = state.catalogue as? CatalogueUi.Found ?: return@update state
                    // by id, never by index: the list can be deduplicated or reordered underneath us
                    state.copy(catalogue = current.copy(items = patchCoverById(current.items, item.id, bytes)))
                }
            }
        }
    }

    private fun ProductSearchResult.toUi(coverJpeg: ByteArray?) = ProductResultUi(
        id = id,
        key = OnlineAggregator.itemKey(this),
        title = title,
        authors = authorLine,
        meta = metaLine,
        languages = languages.joinToString(", "),
        coverJpeg = coverJpeg,
        detailUrl = detailUrl,
        source = source,
        provider = source,
        sourceDomain = ItemIdentity.domainOf(detailUrl),
        identifier = identifier ?: id,
        description = description,
        price = null,
        availability = null,
        matchType = matchTypeLabel(),
    )

    /**
     * The honest label for how this result was found. Open Library is searched with the text the OCR
     * read off the cover, so it is a text match — never a visual one.
     */
    private fun ProductSearchResult.matchTypeLabel(): String = when (OnlineAggregator.matchTypeOf(this)) {
        MatchType.TEXT_QUERY -> "Text match"
        MatchType.VISUAL -> "Visual match"
        MatchType.WORKBOOK -> "Workbook match"
        MatchType.MANUAL -> "Manual"
    }

    private fun messageFor(failure: OnlineFailure, detail: String): String = when (failure) {
        OnlineFailure.OFFLINE -> "No connection to the catalogue. The offline matcher still works."
        OnlineFailure.TIMEOUT -> "The catalogue did not answer in time."
        OnlineFailure.RATE_LIMITED -> "The catalogue is throttling requests — try again in a minute."
        OnlineFailure.SERVER_ERROR -> "The catalogue is having trouble right now."
        OnlineFailure.BAD_RESPONSE -> "The catalogue sent something unexpected."
        OnlineFailure.NOT_CONFIGURED -> "No product catalogue is configured."
        OnlineFailure.INTERNAL -> "The catalogue search failed unexpectedly."
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

