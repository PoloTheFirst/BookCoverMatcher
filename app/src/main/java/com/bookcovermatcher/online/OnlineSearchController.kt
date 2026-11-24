// SPDX-License-Identifier: GPL-3.0-or-later

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
import com.bookcovermatcher.core.online.DistributionEdition
import com.bookcovermatcher.plugins.ActivePlatforms
import com.bookcovermatcher.plugins.platforms
import com.bookcovermatcher.core.online.ProviderCatalog
import com.bookcovermatcher.core.online.ProviderSpec
import com.bookcovermatcher.core.online.SearchLimits
import com.bookcovermatcher.core.online.SearchRouting
import com.bookcovermatcher.core.online.SessionCache
import com.bookcovermatcher.core.wishlist.ItemIdentity
import com.bookcovermatcher.core.wishlist.MatchType
import com.bookcovermatcher.patchCoverById
import com.bookcovermatcher.core.online.ProviderCapabilities
import com.bookcovermatcher.ReverseImageUi
import com.bookcovermatcher.toUi
import com.bookcovermatcher.data.OnlineSearchPrefs
import com.bookcovermatcher.ocr.OcrEngineChoice
import com.bookcovermatcher.ocr.OcrEngines
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
 *  1. **OCR** of the captured cover through the selected engine ([OcrTextSource]): the embedded
 *     `chrome-lens-py` package by default, or the native Rust PP-OCRv6 engine when the debug-only
 *     engine setting says so. Neither is used for anything else.
 *  2. **Product search** through a catalogue ([ProductSearchDataSource]) — a different service
 *     with a different failure model. `chrome-lens-py` returns no products, so nothing here asks
 *     it for any.
 *
 * Nothing runs when [OnlineSearchPrefs.enabled] is off, and no step touches the offline matcher.
 */
class OnlineSearchController internal constructor(
    context: Context,
    private val scope: CoroutineScope,
    private val prefs: OnlineSearchPrefs = OnlineSearchPrefs(context),
    private val bridge: OcrTextSource = OcrEngines.create(context),
    private val covers: CoverImageLoader = CoverImageLoader(),
    private val activeGroups: () -> List<ActivePlatforms> = { emptyList() },
    private val onMessage: (String, ToastKind) -> Unit = { _, _ -> },
) {
    private fun effectiveSelection(groups: List<ActivePlatforms> = activeGroups()): Set<String> {
        val legacySelection = prefs.selectedProviders
        return groups.flatMap { activation ->
            val entries = activation.group.entries
            entries.filter { entry ->
                entry.selectable && prefs.isProviderSelected(entry.id, entries.size == 1 || entry.id in legacySelection)
            }
        }.mapTo(mutableSetOf()) { it.id }
    }

    private fun activeSources() = activeGroups().flatMap { it.sources }
    private var searchSources = emptyList<ProductSearchDataSource>()

    fun canOpenPlatform(url: String): Boolean = activeGroups().platforms().any {
        it.hasLink && runCatching { it.searchUrl(_ui.value.query) }.getOrNull() == url
    }

    /** Revoke the previous activation even if another instance of the same group is already active. */
    fun refreshCataloguePlugins() {
        val activation = activeReverseGroup()
        if (activation !== discoveryActivation) {
            discovery?.close()
            discoveryActivation = activation
            discovery = newDiscovery(activation)
        }
        val active = activeSources()
        val removed = searchSources.filterNot { it in active }.map { it.name }.toSet()
        val invalid = removed.isNotEmpty()
        if (invalid) {
            if (_ui.value.catalogue is CatalogueUi.Running) job?.cancel()
            searchSources = searchSources.filter { it in active }
        }
        session.clearEntries()
        val selection = effectiveSelection()
        _ui.update { state ->
            val current = state.catalogue
            val catalogue = when {
                !invalid -> current
                current is CatalogueUi.Found -> {
                    val remaining = current.items.filterNot { it.provider in removed }
                    if (remaining.isEmpty()) CatalogueUi.Idle else current.copy(
                        items = remaining,
                        source = remaining.map { it.source }.distinct().joinToString(" + "),
                        totalAvailable = null,
                        note = null,
                    )
                }
                else -> CatalogueUi.Idle
            }
            val previousRows = state.providers.associateBy { it.id }
            val providers = providerStatuses(state.query, selection).map { row ->
                val old = previousRows[row.id]
                if (old != null && row.name !in removed && row.selected == old.selected && (!invalid || old.state != ProviderState.SEARCHING)) old else row
            }
            state.copy(selectedProviders = selection, providers = providers, catalogue = catalogue)
        }
    }

    private val _ui = MutableStateFlow(
        OnlineSearchUi(
            enabled = prefs.enabled,
            autoSearch = prefs.autoSearch,
            ocrLanguage = prefs.ocrLanguage,
            targetLanguage = prefs.targetLanguage,
            ocrEngine = prefs.ocrEngine,
            selectedProviders = effectiveSelection(),
            resultLimit = prefs.resultLimit,
            providers = providerStatuses(query = "", selected = effectiveSelection()),
            reverse = ReverseImageUi(secondarySources = prefs.secondarySources),
        ),
    )
    val ui: StateFlow<OnlineSearchUi> = _ui.asStateFlow()

    /** The captured cover, JPEG-encoded and ready to hand to the OCR bridge. */
    private var capture: ByteArray? = null
    private fun activeReverseGroup() = activeGroups().firstOrNull { it.group.reverseSources != null }
    private val newDiscovery = { group: ActivePlatforms? ->
        createDiscovery(context, scope, prefs, _ui, { capture }, onMessage, group) { activeReverseGroup() === group }
    }
    private var discoveryActivation = activeReverseGroup()
    internal var discovery = newDiscovery(discoveryActivation)
        private set
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
        val sources: List<ProductSearchDataSource>,
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
        secondaryOn: Boolean = prefs.secondarySources,
    ): List<ProviderStatusUi> = activeGroups().platforms().map { spec ->
        val manual = !spec.selectable
        val isSelected = spec.selectable && spec.id in selected
        val switchedOff = isSelected && !secondaryOn && ProviderCapabilities.isSecondary(spec.id)
        val (state, detail) = when {
            manual -> ProviderState.MANUAL to spec.note
            switchedOff -> ProviderState.SKIPPED to "General-book sources are switched off; it is not queried."
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
                providers = providerStatuses(query = "", selected = effectiveSelection()),
            )
        }
        if (on) {
            checkBridge()
        } else {
            job?.cancel()
            // switching the feature off hides the results immediately and drops the session cache
            session.invalidate()
            discovery?.reset("Online search is off.")
        }
    }

    /** "Clear session cache": temporary results only, never a bookmark, item or membership. */
    fun clearSession() {
        session.invalidate()
        discovery?.reset()
        _ui.update {
            it.copy(
                catalogue = CatalogueUi.Idle,
                providers = providerStatuses(query = it.query, selected = it.selectedProviders),
            )
        }
        onMessage(SessionCacheMessages.CLEARED, ToastKind.INFO)
    }

    // =========================================================================================
    // Reverse-image discovery — SauceNAO first, IQDB second
    // =========================================================================================

    /**
     * Switches the supplementary general-book sources on or off.
     *
     * Off means **not queried**: [search] filters them out before any request, and their rows say so, so a
     * switched-off provider can never be reported as "searched, nothing found".
     */
    fun setSecondarySources(on: Boolean) {
        if (!DistributionEdition.catalogueEnabled) return
        prefs.secondarySources = on
        _ui.update {
            it.copy(
                reverse = it.reverse.copy(secondarySources = on),
                providers = providerStatuses(it.query, effectiveSelection(), secondaryOn = on),
            )
        }
        onMessage(
            if (on) {
                "Open Library and Google Books take part in searches again."
            } else {
                "Open Library and Google Books are switched off and will not be queried."
            },
            ToastKind.INFO,
        )
    }

    /**
     * Selects or deselects one platform for the **next** search. Never starts a search itself.
     *
     * A running search belongs to the previous selection, so it is cancelled and its cache entry is
     * dropped: a late answer must not be shown as if it came from the new set of platforms.
     */
    fun toggleProvider(id: String) {
        if (!DistributionEdition.catalogueEnabled) return
        val group = activeGroups().firstOrNull { active -> active.group.entries.any { it.id == id } } ?: return
        val spec = group.group.entries.first { it.id == id }
        // Browser-only cards are deliberately not selectable: they have no in-app search to enable.
        if (!spec.selectable) {
            onMessage(
                "${spec.name} is browser-only \u2014 use its Open \u2197 button; it is never searched in the app.",
                ToastKind.INFO,
            )
            return
        }
        val current = effectiveSelection()
        val next = SearchRouting.toggle(current, id)
        if (next == current) return
        prefs.setProviderSelected(id, id in next)

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
        if (!DistributionEdition.catalogueEnabled) return
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
     * Selects the OCR engine for READ THE COVER.
     *
     * The choice is persisted, validated by [OcrEngineChoice], and announced in the log with the
     * identifier that will be stored — which is how a device log can be tied to a selection. The
     * availability line describes the *selected* engine, so it is re-checked instead of being left to
     * describe the engine that was used a moment ago.
     */
    fun selectOcrEngine(engine: String) {
        val choice = OcrEngineChoice.fromId(engine)
        prefs.ocrEngine = choice.id
        OnlineSearchLog.ocrEngineSelection(choice.id, choice.label)
        _ui.update { it.copy(ocrEngine = prefs.ocrEngine, bridge = BridgeUi.Checking) }
        bridgeChecked = false
        if (_ui.value.enabled) checkBridge()
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
        // Resolve the engine this check is for: a slow availability call must not be labelled with a
        // different engine's name if the user switches while it runs.
        val engine = OcrEngineChoice.fromId(_ui.value.ocrEngine)
        scope.launch(Dispatchers.IO) {
            val status = try {
                val a = bridge.availability()
                if (a is OcrAvailability.Ready) {
                    BridgeUi.Ready(a.libraryName, a.libraryVersion)
                } else {
                    BridgeUi.Unavailable(
                        (a as? OcrAvailability.Unavailable)?.reason ?: "${engine.shortLabel} is unavailable.",
                    )
                }
            } catch (t: Throwable) {
                BridgeUi.Unavailable(t.message ?: t.javaClass.simpleName)
            }
            OnlineSearchLog.ocrEngineResult(
                engine.id,
                "availability " + when (status) {
                    is BridgeUi.Ready -> "ready ${status.library} ${status.version}"
                    is BridgeUi.Unavailable -> "unavailable"
                    BridgeUi.Checking -> "checking"
                },
            )
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
        if (cached == null) {
            _ui.update { it.copy(catalogue = CatalogueUi.Idle) }
            // Reverse-image candidates belong to the cover they were found from, not to the next one.
            discovery?.reset()
        }
        if (!_ui.value.enabled) return
        readCover()
    }

    /**
     * A different cover was selected while the feature is off. Drops the previous capture's results
     * without encoding anything: they cannot belong to the new cover.
     */
    fun onCoverChanged() {
        session.invalidate()
        discovery?.reset()
        _ui.update { it.copy(catalogue = CatalogueUi.Idle) }
    }

    /**
     * The cover could not even be encoded, so OCR was never attempted. Reported separately on
     * purpose: "we could not make a JPEG of this" and "Google Lens could not read this" send the
     * user in completely different directions, and collapsing them into one message is what made
     * this feature so hard to diagnose.
     */
    fun onCaptureFailed(message: String, detail: String? = null) {
        OnlineSearchLog.warn("cover encoding failed")
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

        // The engine is fixed for the whole request: if the user switches while it runs, this request
        // is still reported as — and labelled with — the engine it actually used. A later request
        // picks up the new selection, because `bridge` re-reads the preference when it is next called.
        val engine = OcrEngineChoice.fromId(_ui.value.ocrEngine)
        OnlineSearchLog.ocrEngineRequest(engine.id, engine.label, bytes.size)

        job?.cancel()
        job = scope.launch {
            val token = session.token()
            _ui.update { it.copy(ocr = OcrUi.Running("Reading the cover with ${engine.shortLabel}…")) }
            val result = withContext(Dispatchers.IO) {
                runCatching { bridge.recognise(bytes, _ui.value.ocrLanguage, _ui.value.targetLanguage, OCR_TIMEOUT_SECONDS) }
            }
            // An OCR call already inside Python or ONNX cannot be preempted; its answer is ignored
            // when another cover has been selected in the meantime, which is what the token checks.
            result.fold(
                onSuccess = { text ->
                    OnlineSearchLog.ocrEngineResult(engine.id, "ok blocks=${text.blockCount} library=${text.libraryVersion}")
                    if (session.isCurrent(token)) applyOcrText(text, engine)
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    OnlineSearchLog.ocrEngineResult(engine.id, "failed")
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
        // No fallback: the selected engine's own failure is what the user sees, so switching engines
        // stays an explicit choice rather than something the app does behind their back.
        _ui.update { it.copy(ocr = OcrUi.Failed(shown)) }
        onMessage(OcrErrorMessages.brief(category), ToastKind.ERR)
    }

    private fun applyOcrText(text: OcrText, engine: OcrEngineChoice) {
        if (text.isEmpty) {
            _ui.update { it.copy(ocr = OcrUi.NoText(text.libraryVersion, engine.shortLabel), query = "") }
            onMessage("No text found on the cover.", ToastKind.INFO)
            return
        }
        // The translation is searched when it was requested: a Japanese cover is more likely to be
        // in an English-language catalogue under its translated title.
        val searchable = text.translatedText?.takeIf { it.isNotBlank() } ?: text.text
        val suggestion = OcrQueryExtractor.suggest(searchable, text.blockHeights)

        _ui.update {
            it.copy(
                ocr = OcrUi.Done(
                    searchable,
                    text.detectedLanguage,
                    suggestion?.query.orEmpty(),
                    text.libraryVersion,
                    engine.shortLabel,
                ),
                query = suggestion?.query.orEmpty(),
            )
        }
        when {
            suggestion == null -> onMessage("Text found, but no title could be picked out.", ToastKind.INFO)
            prefs.autoSearch && _ui.value.providers.any { !it.manual } -> search(suggestion.query)
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
        if (!DistributionEdition.catalogueEnabled) return
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

        val groups = activeGroups()
        val selection = effectiveSelection(groups)
        val limit = SearchLimits.sanitize(prefs.resultLimit)
        val selectionKey = SearchRouting.key(selection)
        val secondaryOn = prefs.secondarySources
        // A switched-off secondary source is filtered out **before** any request, so it can never be reported
        // as having been searched.
        val automatedSpecs = SearchRouting.automated(selection)
            .filter { secondaryOn || !ProviderCapabilities.isSecondary(it.id) }

        if (automatedSpecs.isEmpty()) {
            session.invalidate()
            val switchedOffOnly = SearchRouting.automated(selection).isNotEmpty()
            val shortcuts = groups.platforms().filter { !it.selectable && it.hasLink }.joinToString { it.name }
            val message = when {
                // Selected, but every selected provider is a general-book source the user switched off.
                switchedOffOnly ->
                    "Open Library and Google Books are switched off, so nothing was queried. Turn the " +
                        "general-book sources back on or enable an imported catalogue plugin."
                shortcuts.isEmpty() ->
                    "Select a platform to search."
                else ->
                    "No in-app platform is selected. Browser-only shortcuts ($shortcuts) open their own " +
                        "search pages with the Open \u2197 buttons above."
            }
            _ui.update {
                it.copy(
                    query = trimmed,
                    catalogue = if (switchedOffOnly) CatalogueUi.SwitchedOff(message) else CatalogueUi.BrowserOnly(message),
                    selectedProviders = selection,
                    resultLimit = limit,
                    providers = providerStatuses(trimmed, selection, secondaryOn = secondaryOn).withQuery(trimmed),
                )
            }
            onMessage(
                if (switchedOffOnly) "General-book sources are off — nothing to search." else "Select at least one in-app platform to search.",
                ToastKind.INFO,
            )
            return
        }

        val selectedIds = automatedSpecs.map { it.id }.toSet()
        val sources = groups.flatMap { it.sources }.filter { specFor(it)?.id in selectedIds }
        searchSources = sources

        val fingerprint = capture?.let { ImageFingerprint.of(it) }
        if (fingerprint != null) {
            val cached = session.cached(fingerprint)
            // Same cover, same query, same platforms, same maximum: only then is the answer reused.
            if (cached != null &&
                cached.query == trimmed &&
                cached.limit == limit &&
                cached.selectionKey == selectionKey && cached.sources == sources
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
                            if (source !in activeSources()) throw kotlinx.coroutines.CancellationException()
                            val ask = SearchLimits.forProvider(limit, source.maxResults)
                            source to runCatching {
                                withTimeout(PROVIDER_TIMEOUT_MS) { source.search(trimmed, ask) }
                            }
                        }
                    }.awaitAll()
                }
            }
            if (!session.isCurrent(token) || !activeSources().containsAll(sources)) return@launch
            applyOutcomes(
                query = trimmed,
                outcomes = outcomes,
                fingerprint = fingerprint,
                token = token,
                limit = limit,
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
        val providers = providerStatuses(query, effectiveSelection(), perProvider).withQuery(_ui.value.query)
        val sourceLabel = answered.distinct().joinToString(" + ")
            .ifEmpty { outcomes.joinToString(" + ") { it.first.name } }
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
        // `applyResults` stores the search and may select a new capture, which bumps the cache
        // generation. Covers must carry the token *after* that write: a token captured before it is
        // already stale, which is why the first search used to show no covers until a second click.
        loadCovers(merged, session.token())
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
                CachedSearch(query, limit, selectionKey, searchSources, results, totalAvailable, source, note, providers),
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
        val sources = searchSources.associateBy { it.name }
        fun revoked(item: ProductSearchResult) = sources[item.source] !in activeSources()
        scope.launch {
            for (item in results) {
                if (!session.isCurrent(token)) return@launch
                if (revoked(item)) continue
                val bytes = withContext(Dispatchers.IO) {
                    if (revoked(item)) null
                    else runCatching { covers.load(item.coverUrl) }.getOrNull()
                }
                    ?: continue
                if (!session.isCurrent(token)) return@launch
                if (revoked(item)) continue
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
        circle = circle,
        artistNames = authors,
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
        discovery?.close()
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

