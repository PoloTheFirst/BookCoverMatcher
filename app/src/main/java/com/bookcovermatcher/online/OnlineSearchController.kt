package com.bookcovermatcher.online

import android.content.Context
import com.bookcovermatcher.BridgeUi
import com.bookcovermatcher.CatalogueUi
import com.bookcovermatcher.OcrUi
import com.bookcovermatcher.OnlineSearchUi
import com.bookcovermatcher.ProductResultUi
import com.bookcovermatcher.ProviderState
import com.bookcovermatcher.ProviderStatusUi
import com.bookcovermatcher.withQuery
import com.bookcovermatcher.ToastKind
import com.bookcovermatcher.core.online.ImageFingerprint
import com.bookcovermatcher.core.online.OcrQueryExtractor
import com.bookcovermatcher.core.online.OnlineAggregator
import com.bookcovermatcher.core.online.OnlineFailure
import com.bookcovermatcher.core.online.ProductSearchDataSource
import com.bookcovermatcher.core.online.ProductSearchOutcome
import com.bookcovermatcher.core.online.ProductSearchResult
import com.bookcovermatcher.core.online.ProviderCatalog
import com.bookcovermatcher.core.online.ProviderSpec
import com.bookcovermatcher.core.online.SearchLimits
import com.bookcovermatcher.core.online.SearchRouting
import com.bookcovermatcher.core.online.SessionCache
import com.bookcovermatcher.core.wishlist.ItemIdentity
import com.bookcovermatcher.core.wishlist.MatchType
import com.bookcovermatcher.patchCoverById
import com.bookcovermatcher.data.OnlineSearchPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

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
     * Every provider the app can query itself, each through its own adapter:
     *  * **Open Library** — library catalogue, JSON API;
     *  * **Google Books** — book catalogue, JSON API;
     *  * **DLsite** — doujinshi / digital works shop, public search page (HTML).
     *
     * Melonbooks and Suruga-ya are deliberately **not** here: the first is browser-only by product
     * decision (v2.2.2 patch), the second because its `robots.txt` disallows `/search/`. Doujin no Mori
     * disallows its own search route in `robots.txt`, Doujinshi.info and nhentai answer HTTP 403 to
     * automated clients, and MyAnimeList has no unauthenticated API — all five are listed by
     * [ProviderCatalog] for the browser-handoff card only (E-Hentai too, as an adult catalogue).
     * Which of these actually run is decided by the user's platform selection ([SearchRouting]).
     */
    private val catalogues: List<ProductSearchDataSource> = listOf(
        OpenLibraryBooksSource(),
        GoogleBooksSource(),
        DlsiteSource(),
    ),
    private val covers: CoverImageLoader = CoverImageLoader(),
    private val onMessage: (String, ToastKind) -> Unit = { _, _ -> },
) {
    private val _ui = MutableStateFlow(
        OnlineSearchUi(
            enabled = prefs.enabled,
            autoSearch = prefs.autoSearch,
            ocrLanguage = prefs.ocrLanguage,
            targetLanguage = prefs.targetLanguage,
            selectedProviders = prefs.selectedProviders,
            resultLimit = prefs.resultLimit,
            providers = providerStatuses(query = "", selected = prefs.selectedProviders),
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
        /** The maximum the user asked for; a 5-result answer must not satisfy a 20-result request. */
        val limit: Int,
        /** Which platforms were selected when this answer was produced. */
        val selectionKey: String,
        val results: List<ProductSearchResult>,
        val totalAvailable: Int?,
        val source: String,
        val note: String?,
        /** The per-provider outcome of the search that produced these results. */
        val providers: List<ProviderStatusUi>,
    )

    /**
     * The provider rows for [query] under [selected].
     *
     * Four honest states, all drawn:
     *  * a browser-only platform is always [ProviderState.MANUAL] — it is never queried, so its row can
     *    never claim a result, whether or not it is selected;
     *  * an unselected platform is [ProviderState.SKIPPED] — not queried, and it must not look like an
     *    empty successful search;
     *  * a selected automated platform is [ProviderState.SEARCHING] during a search, then whatever
     *    [automated] says it actually returned;
     *  * otherwise it is [ProviderState.READY] with its own note.
     */
    private fun providerStatuses(
        query: String,
        selected: Set<String>,
        automated: Map<String, Pair<ProviderState, String>> = emptyMap(),
        searching: Boolean = false,
    ): List<ProviderStatusUi> = ProviderCatalog.ALL.map { spec ->
        val manual = !spec.selectable
        val isSelected = spec.selectable && spec.id in selected
        val (state, detail) = when {
            manual -> ProviderState.MANUAL to spec.note
            !isSelected -> ProviderState.SKIPPED to "Not selected for this search."
            searching -> ProviderState.SEARCHING to "Searching\u2026"
            else -> automated[spec.id] ?: (ProviderState.READY to spec.note)
        }
        ProviderStatusUi(
            id = spec.id,
            name = spec.name,
            host = spec.host,
            manual = manual,
            selected = isSelected,
            state = state,
            detail = detail,
            // Every platform with a verified public page gets a link, selected or not; the link is
            // built and validated by ProviderLinks. A provider with no verified page has none.
            searchUrl = if (spec.hasLink) runCatching { spec.searchUrl(query) }.getOrNull() else null,
        )
    }

    /** The registry row a live source belongs to, matched by the provider name the source reports. */
    private fun specFor(source: ProductSearchDataSource): ProviderSpec? =
        ProviderCatalog.ALL.firstOrNull { it.name.equals(source.name, ignoreCase = true) }

    // =========================================================================================
    // Settings
    // =========================================================================================

    fun setEnabled(on: Boolean) {
        prefs.enabled = on
        _ui.update {
            it.copy(
                enabled = on,
                ocr = OcrUi.Idle,
                catalogue = CatalogueUi.Idle,
                query = "",
                // A disabled feature has searched nothing: every row goes back to its resting state.
                providers = providerStatuses(query = "", selected = prefs.selectedProviders),
            )
        }
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
        _ui.update {
            it.copy(
                catalogue = CatalogueUi.Idle,
                providers = providerStatuses(query = it.query, selected = it.selectedProviders),
            )
        }
        onMessage("Session cache cleared — saved bookmarks and wishlist items are untouched.", ToastKind.INFO)
    }

    // =========================================================================================
    // Platform selection and result count
    // =========================================================================================

    /**
     * Selects or deselects one platform for the **next** search. Never starts a search itself.
     *
     * A running search belongs to the previous selection, so it is cancelled and its cache entry is
     * dropped: a late answer must not be shown as if it came from the new set of platforms.
     */
    fun toggleProvider(id: String) {
        val spec = ProviderCatalog.byId(id) ?: return
        // Browser-only cards are deliberately not selectable: they have no in-app search to enable.
        if (!spec.selectable) {
            onMessage(
                "${spec.name} is browser-only \u2014 use its Open \u2197 button; it is never searched in the app.",
                ToastKind.INFO,
            )
            return
        }
        val current = prefs.selectedProviders
        val next = SearchRouting.toggle(current, id)
        if (next == current) return
        prefs.selectedProviders = next

        val wasRunning = _ui.value.catalogue is CatalogueUi.Running
        if (wasRunning) {
            job?.cancel()
            job = null
        }
        session.invalidate()
        _ui.update {
            it.copy(
                selectedProviders = next,
                catalogue = if (it.catalogue is CatalogueUi.Running) CatalogueUi.Idle else it.catalogue,
                providers = providerStatuses(query = it.query, selected = next),
            )
        }
        val message = if (spec.id in next) "${spec.name} selected." else "${spec.name} deselected."
        onMessage(message, ToastKind.INFO)
    }

    /**
     * Sets the maximum number of online results for the **next** search and persists it.
     *
     * The value is validated by [SearchLimits] (an unknown value becomes the default) and only affects
     * the online list; the offline comparison keeps `Config.TOP_MATCHES`. The cached answer for the
     * current cover is dropped because it was produced for a different maximum.
     */
    fun setResultLimit(value: Int) {
        val sanitized = SearchLimits.sanitize(value)
        if (sanitized == prefs.resultLimit) return
        prefs.resultLimit = sanitized

        val wasRunning = _ui.value.catalogue is CatalogueUi.Running
        if (wasRunning) {
            job?.cancel()
            job = null
        }
        session.invalidate()
        _ui.update {
            it.copy(
                resultLimit = sanitized,
                catalogue = if (it.catalogue is CatalogueUi.Running) CatalogueUi.Idle else it.catalogue,
            )
        }
        onMessage("Up to $sanitized online result(s) per search.", ToastKind.INFO)
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

    /**
     * The text field's only writer, and the **source of truth** for every browser-only link.
     *
     * The cards' `Open ↗` targets are rebuilt for [text] on each edit, so typing a title and opening
     * Melonbooks/Suruga-ya/Toranoana… never requires a dummy in-app search first. Nothing is launched
     * here and no provider is contacted: a search still starts only from [search]. The rows keep the
     * states/details of the last search that actually ran, so an edit never fakes a result.
     */
    fun setQuery(text: String) = _ui.update {
        it.copy(query = text, providers = it.providers.withQuery(text))
    }

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

    /**
     * "Search the providers" / auto-invoked after a successful OCR pass.
     *
     * Routing is decided by the user's selection ([SearchRouting]) and the configured maximum
     * ([SearchLimits]):
     *  * only selected **automated** platforms are queried, each with its own capped limit;
     *  * selected **browser-only** platforms are never queried — if they are the only thing selected,
     *    no request is made at all and the panel says where those results can be seen;
     *  * an empty selection searches nothing (and says so) rather than silently querying defaults.
     */
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

        val selection = prefs.selectedProviders
        val limit = SearchLimits.sanitize(prefs.resultLimit)
        val selectionKey = SearchRouting.key(selection)
        val automatedSpecs = SearchRouting.automated(selection)

        if (automatedSpecs.isEmpty()) {
            // Browser-only platforms are never part of the selection, so this can only mean that no
            // in-app platform is selected. The cards and their Open buttons stay available.
            session.invalidate()
            val shortcuts = SearchRouting.shortcuts().filter { it.hasLink }.joinToString { it.name }
            val message = if (shortcuts.isEmpty()) {
                "Select at least one platform to search (Open Library, Google Books or DLsite)."
            } else {
                "No in-app platform is selected. Browser-only shortcuts ($shortcuts) open their own " +
                    "search pages with the Open \u2197 buttons above."
            }
            _ui.update {
                it.copy(
                    query = trimmed,
                    catalogue = CatalogueUi.BrowserOnly(message),
                    selectedProviders = selection,
                    resultLimit = limit,
                    providers = providerStatuses(trimmed, selection).withQuery(trimmed),
                )
            }
            onMessage("Select at least one in-app platform to search.", ToastKind.INFO)
            return
        }

        val selectedIds = automatedSpecs.map { it.id }.toSet()
        val sources = catalogues.filter { specFor(it)?.id in selectedIds }

        val fingerprint = capture?.let { ImageFingerprint.of(it) }
        if (fingerprint != null) {
            val cached = session.cached(fingerprint)
            // Same cover, same query, same platforms, same maximum: only then is the answer reused.
            if (cached != null &&
                cached.query == trimmed &&
                cached.limit == limit &&
                cached.selectionKey == selectionKey
            ) {
                applyResults(
                    results = cached.results,
                    query = cached.query,
                    totalAvailable = cached.totalAvailable,
                    fingerprint = fingerprint,
                    fromCache = true,
                    source = cached.source,
                    note = cached.note,
                    providers = cached.providers,
                    limit = cached.limit,
                    selectionKey = cached.selectionKey,
                )
                loadCovers(cached.results, session.token())
                return
            }
        }

        _ui.update {
            it.copy(
                query = trimmed,
                catalogue = CatalogueUi.Running(trimmed),
                selectedProviders = selection,
                resultLimit = limit,
                providers = providerStatuses(query = trimmed, selected = selection, searching = true)
                    .withQuery(trimmed),
            )
        }
        val token = session.token()
        job = scope.launch {
            // Providers run concurrently: a slow shop must not delay a fast catalogue, and one
            // provider's timeout must not cancel the others. Each call gets its own deadline and
            // its own capped limit.
            val outcomes = withContext(Dispatchers.IO) {
                coroutineScope {
                    sources.map { source ->
                        async {
                            val ask = SearchLimits.forProvider(limit, source.maxResults)
                            source to runCatching {
                                withTimeout(PROVIDER_TIMEOUT_MS) { source.search(trimmed, ask) }
                            }
                        }
                    }.awaitAll()
                }
            }
            if (!session.isCurrent(token)) return@launch
            applyOutcomes(
                query = trimmed,
                outcomes = outcomes,
                fingerprint = fingerprint,
                token = token,
                limit = limit,
                selection = selection,
                selectionKey = selectionKey,
            )
        }
    }

    /**
     * Merges every provider that answered. One failing provider does not hide another's results: the
     * failure is reported in the panel note, in that provider's own status row and in the toast, and
     * only a total failure becomes [CatalogueUi.Failed].
     *
     * The per-provider rows are the honest record of the search: a provider that answered with
     * results, one that answered with nothing, one that failed, one that was not selected and one that
     * can only be used in a browser are five different states, and the UI shows all five.
     *
     * Deduplication happens inside [OnlineAggregator.merge] before [limit] is applied, so the final
     * count is the number of real, distinct cards the panel draws.
     */
    private fun applyOutcomes(
        query: String,
        outcomes: List<Pair<ProductSearchDataSource, Result<ProductSearchOutcome>>>,
        fingerprint: String?,
        token: Long,
        limit: Int,
        selection: Set<String>,
        selectionKey: String,
    ) {
        val found = ArrayList<ProductSearchResult>()
        val answered = ArrayList<String>()
        val failures = ArrayList<String>()
        val perProvider = LinkedHashMap<String, Pair<ProviderState, String>>()
        var total: Int? = null
        var totalCount = 0

        outcomes.forEach { (source, attempt) ->
            val outcome = attempt.getOrElse { error ->
                val timedOut = error is kotlinx.coroutines.TimeoutCancellationException
                ProductSearchOutcome.Failed(
                    if (timedOut) OnlineFailure.TIMEOUT else OnlineFailure.INTERNAL,
                    if (timedOut) {
                        "no answer within ${PROVIDER_TIMEOUT_MS / 1000} s"
                    } else {
                        error.message ?: error.javaClass.simpleName
                    },
                )
            }
            val spec = specFor(source)
            val id = spec?.id ?: source.name.lowercase()
            when (outcome) {
                is ProductSearchOutcome.Found -> {
                    found += outcome.results
                    answered += source.name
                    // The row reports what this provider returned, not what survived merging: a
                    // provider whose items were all duplicates still answered.
                    perProvider[id] = ProviderState.RESULTS to "${outcome.results.size} result(s)"
                    outcome.totalAvailable?.let {
                        total = (total ?: 0) + it
                        totalCount++
                    }
                }
                is ProductSearchOutcome.Empty -> {
                    answered += source.name
                    perProvider[id] = ProviderState.EMPTY to "no match"
                }
                is ProductSearchOutcome.Failed -> {
                    val why = messageFor(outcome.failure, outcome.message)
                    failures += "${source.name}: $why"
                    perProvider[id] = if (outcome.failure == OnlineFailure.BLOCKED) {
                        ProviderState.BLOCKED to why
                    } else {
                        ProviderState.FAILED to why
                    }
                }
            }
        }

        // The rows report this search's outcome, but their Open links follow the text field: the user
        // may have typed something else while this search was running, and a late answer must never
        // drag the browser links back to the query that produced it.
        val providers = providerStatuses(query, selection, perProvider).withQuery(_ui.value.query)
        val sourceLabel = answered.distinct().joinToString(" + ")
            .ifEmpty { catalogues.joinToString(" + ") { it.name } }
        if (answered.isEmpty() && found.isEmpty()) {
            val reason = failures.joinToString("; ").ifEmpty { "No provider answered." }
            _ui.update { it.copy(catalogue = CatalogueUi.Failed(reason), providers = providers) }
            onMessage("Catalogue search failed.", ToastKind.ERR)
            return
        }

        val merged = OnlineAggregator.merge(listOf(found), limit = limit)
        val note = failures.takeIf { it.isNotEmpty() }?.joinToString("; ")
        if (merged.isEmpty()) {
            _ui.update {
                it.copy(catalogue = CatalogueUi.Empty(query, sourceLabel, note), providers = providers)
            }
            onMessage("No match in $sourceLabel.", ToastKind.INFO)
            return
        }
        applyResults(
            results = merged,
            query = query,
            totalAvailable = total.takeIf { totalCount == 1 },
            fingerprint = fingerprint,
            fromCache = false,
            source = sourceLabel,
            note = note,
            providers = providers,
            limit = limit,
            selectionKey = selectionKey,
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
        providers: List<ProviderStatusUi>,
        limit: Int,
        selectionKey: String,
    ) {
        if (fingerprint != null && !fromCache) {
            session.store(
                fingerprint,
                CachedSearch(query, limit, selectionKey, results, totalAvailable, source, note, providers),
            )
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
                providers = providers,
                resultLimit = limit,
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
        price = priceText,
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
        OnlineFailure.BLOCKED -> "The site refused automated access. Use its search link instead."
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

        /**
         * One provider's budget for one search. Providers run concurrently, so this is also the
         * worst case a user waits when a shop is slow: a hung provider is dropped, the others are
         * kept, and its row says it timed out.
         */
        const val PROVIDER_TIMEOUT_MS = 20_000L
    }
}

