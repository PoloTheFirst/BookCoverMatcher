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
import com.bookcovermatcher.core.online.CandidateDecision
import com.bookcovermatcher.core.online.CandidateDecisionRecord
import com.bookcovermatcher.core.online.CandidateReviews
import com.bookcovermatcher.core.online.ProviderCapabilities
import com.bookcovermatcher.core.online.reverse.IqdbProtocol
import com.bookcovermatcher.core.online.reverse.IqdbResultParser
import com.bookcovermatcher.core.online.reverse.ReverseImageAggregator
import com.bookcovermatcher.core.online.reverse.ReverseImageProviders
import com.bookcovermatcher.core.online.reverse.ReverseImageRequest
import com.bookcovermatcher.core.online.reverse.ReverseImageRun
import com.bookcovermatcher.core.online.reverse.ReverseImageSearchRunner
import com.bookcovermatcher.core.online.reverse.ReverseImageSource
import com.bookcovermatcher.core.online.reverse.ReverseImageState
import com.bookcovermatcher.core.online.reverse.SauceNaoKeyRules
import com.bookcovermatcher.core.online.reverse.SauceNaoKeySaveResult
import com.bookcovermatcher.ReverseCandidateUi
import com.bookcovermatcher.ReverseImageProviderUi
import com.bookcovermatcher.ReverseImageUi
import com.bookcovermatcher.toUi
import com.bookcovermatcher.data.CandidateDecisionStore
import com.bookcovermatcher.data.OnlineSearchPrefs
import com.bookcovermatcher.data.SauceNaoKeyStore
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
 *     ([OcrTextSource]) â€” the only thing that library actually does.
 *  2. **Product search** through a catalogue ([ProductSearchDataSource]) â€” a different service
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
     *  * **Open Library** â€” library catalogue, JSON API;
     *  * **Google Books** â€” book catalogue, JSON API;
     *  * **DLsite** â€” doujinshi / digital works shop, public search page (HTML).
     *
     * Melonbooks and Suruga-ya are deliberately **not** here: the first is browser-only by product
     * decision (v2.2.2 patch), the second because its `robots.txt` disallows `/search/`. Doujin no Mori
     * disallows its own search route in `robots.txt`, Doujinshi.info and nhentai answer HTTP 403 to
     * automated clients, and MyAnimeList has no unauthenticated API â€” all five are listed by
     * [ProviderCatalog] for the browser-handoff card only (E-Hentai too, as an adult catalogue).
     * Which of these actually run is decided by the user's platform selection ([SearchRouting]).
     */
    private val catalogues: List<ProductSearchDataSource> = listOf(
        OpenLibraryBooksSource(),
        GoogleBooksSource(),
        DlsiteSource(),
    ),
    private val covers: CoverImageLoader = CoverImageLoader(),
    /**
     * The user's SauceNAO API key, encrypted with a Keystore-held key. Read at request time, never cached in a
     * field that could end up in a dump.
     */
    private val sauceNaoKey: SauceNaoKeyStore = SauceNaoKeyStore(context),
    /** Durable confirmations/rejections, one file per wishlist profile. */
    private val decisionStore: CandidateDecisionStore = CandidateDecisionStore(context),
    /**
     * The reverse-image providers of this build, in the order the specification fixes: SauceNAO first, IQDB
     * second. IQDB's adapter is manual-only and says so; see [IqdbProtocol] for the verified reason.
     */
    private val reverseSources: List<ReverseImageSource> = listOf(
        SauceNaoReverseImageSource(apiKey = { sauceNaoKey.key() }),
        IqdbReverseImageSource(),
    ),
    private val onMessage: (String, ToastKind) -> Unit = { _, _ -> },
) {
    // ---- reverse-image discovery ---------------------------------------------------------------
    //
    // Declared **before** `_ui` on purpose: the UI state below is built in a property initializer, and Kotlin
    // runs initializers in declaration order. In v2.5.0-dev.2 these fields came after `_ui`, so the initializer
    // read `reverseRuns` while it was still null and the constructor threw â€” a crash on every launch. Anything a
    // property initializer touches must already be initialised above it.
    //
    // SauceNAO is tried first, IQDB second, and every provider's state is published on its own. The generation
    // counter is the stale-response guard: a result whose token is no longer current is dropped instead of
    // overwriting the answer to a newer request.
    private val reverseRunner = ReverseImageSearchRunner(reverseSources)
    private val reverseRuns = LinkedHashMap<String, ReverseImageRun>()
    private var reverseJob: Job? = null

    @Volatile
    private var reverseCancelled = false
    private var reverseGeneration = 0L

    /** Candidates the last automated reverse-image run produced. */
    private var searchCandidates: List<ReverseCandidateUi> = emptyList()

    /**
     * Candidates the **user** supplied through the IQDB handoff (the paste box). Kept apart from the automated
     * run's candidates so a new SauceNAO/IQDB run never discards what the user brought back.
     */
    private var pastedCandidates: List<ReverseCandidateUi> = emptyList()

    private val _ui = MutableStateFlow(
        OnlineSearchUi(
            enabled = prefs.enabled,
            autoSearch = prefs.autoSearch,
            ocrLanguage = prefs.ocrLanguage,
            targetLanguage = prefs.targetLanguage,
            selectedProviders = prefs.selectedProviders,
            resultLimit = prefs.resultLimit,
            providers = providerStatuses(query = "", selected = prefs.selectedProviders),
            reverse = ReverseImageUi(
                // Explicitly "no runs yet": the resting rows come from the providers' own readiness, so this
                // initializer depends on nothing mutable.
                providers = reverseProviderRows(runs = emptyMap()),
                sauceNaoKeyHint = sauceNaoKey.hint(),
                secondarySources = prefs.secondarySources,
            ),
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

    /** Confirmations and rejections, loaded once from the profile's decision log. */
    private var decisions: Map<String, CandidateDecisionRecord> =
        runCatching { decisionStore.load() }.getOrDefault(emptyMap())

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
     *  * a browser-only platform is always [ProviderState.MANUAL] â€” it is never queried, so its row can
     *    never claim a result, whether or not it is selected;
     *  * an unselected platform is [ProviderState.SKIPPED] â€” not queried, and it must not look like an
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
    ): List<ProviderStatusUi> = ProviderCatalog.ALL.map { spec ->
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
                providers = providerStatuses(query = "", selected = prefs.selectedProviders),
            )
        }
        if (on) {
            checkBridge()
        } else {
            job?.cancel()
            // switching the feature off hides the results immediately and drops the session cache
            session.invalidate()
            resetReverse("Online search is off.")
        }
    }

    /** "Clear session cache": temporary results only, never a bookmark, item or membership. */
    fun clearSession() {
        session.invalidate()
        resetReverse()
        _ui.update {
            it.copy(
                catalogue = CatalogueUi.Idle,
                providers = providerStatuses(query = it.query, selected = it.selectedProviders),
            )
        }
        onMessage("Session cache cleared â€” saved bookmarks and wishlist items are untouched.", ToastKind.INFO)
    }

    // =========================================================================================
    // Reverse-image discovery â€” SauceNAO first, IQDB second
    // =========================================================================================

    /**
     * The two provider rows, built from the sources themselves plus whatever the last run reported.
     *
     * The rows themselves are built by [ReverseProviderRows], which takes everything it needs as parameters â€”
     * so this call is safe from a property initializer, where reading a field declared further down the class
     * would see `null` (the v2.5.0-dev.2 startup crash).
     */
    private fun reverseProviderRows(
        runs: Map<String, ReverseImageRun> = reverseRuns,
    ): List<ReverseImageProviderUi> = ReverseProviderRows.of(reverseSources, runs)

    /**
     * Stores a SauceNAO key in the Keystore-backed store. The key itself is never echoed anywhere.
     *
     * On success the previous SauceNAO run is discarded: a run recorded *before* the key existed says
     * `NOT_CONFIGURED`, and keeping it would leave the row claiming "needs key" after a successful configuration
     * (a stale-state defect of its own). The row is then rebuilt from the provider's live readiness.
     */
    fun saveSauceNaoKey(raw: String) {
        when (val result = sauceNaoKey.save(raw)) {
            is SauceNaoKeySaveResult.Rejected -> {
                onMessage(SauceNaoKeyRules.describe(result.reason), ToastKind.ERR)
                // Nothing was stored, so the row must keep telling the truth about that.
                _ui.update { it.copy(reverse = it.reverse.copy(sauceNaoKeyHint = sauceNaoKey.hint())) }
                return
            }
            SauceNaoKeySaveResult.Saved -> Unit
        }
        reverseRuns.remove(ReverseImageProviders.SAUCE_NAO_ID)
        _ui.update {
            it.copy(
                reverse = it.reverse.copy(
                    sauceNaoKeyHint = sauceNaoKey.hint(),
                    providers = reverseProviderRows(),
                ),
            )
        }
        onMessage("SauceNAO key saved in encrypted storage; SauceNAO is ready to be asked.", ToastKind.OK)
    }

    fun clearSauceNaoKey() {
        sauceNaoKey.clear()
        reverseRuns.remove(ReverseImageProviders.SAUCE_NAO_ID)
        _ui.update { it.copy(reverse = it.reverse.copy(sauceNaoKeyHint = null, providers = reverseProviderRows())) }
        onMessage("SauceNAO key removed; SauceNAO will not be queried.", ToastKind.INFO)
    }

    /**
     * Switches the supplementary general-book sources on or off.
     *
     * Off means **not queried**: [search] filters them out before any request, and their rows say so, so a
     * switched-off provider can never be reported as "searched, nothing found".
     */
    fun setSecondarySources(on: Boolean) {
        prefs.secondarySources = on
        _ui.update {
            it.copy(
                reverse = it.reverse.copy(secondarySources = on),
                providers = providerStatuses(it.query, prefs.selectedProviders, secondaryOn = on),
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
     * Starts reverse-image discovery on the captured cover.
     *
     * Sequence, fixed by the release: SauceNAO first (if a key is stored), IQDB second (manual-only, so it
     * reports `MANUAL_ONLY` and offers its own page). Each provider publishes its row as it runs, so SauceNAO's
     * results are visible while IQDB is still being handled â€” nothing waits for the slower provider.
     */
    fun runReverseSearch() {
        val bytes = capture
        if (bytes == null) {
            onMessage("Scan a cover first â€” reverse-image search needs the image itself.", ToastKind.ERR)
            return
        }
        if (!_ui.value.enabled) {
            onMessage("Turn online search on first.", ToastKind.INFO)
            return
        }
        if (_ui.value.reverse.running || _ui.value.isBusy) {
            onMessage("Still working â€” tap Cancel to stop.", ToastKind.INFO)
            return
        }

        val generation = ++reverseGeneration
        reverseCancelled = false
        reverseRuns.clear()
        searchCandidates = emptyList()
        val request = ReverseImageRequest(
            bytes = bytes,
            fileName = "cover.jpg",
            mimeType = "image/jpeg",
            maxResults = SearchLimits.sanitize(prefs.resultLimit).coerceAtMost(ReverseImageRequest.MAX_RESULTS),
        )
        val runner = reverseRunner

        reverseJob?.cancel()
        reverseJob = scope.launch {
            _ui.update {
                it.copy(
                    reverse = it.reverse.copy(
                        running = true,
                        candidates = withDecisions(pastedCandidates),
                        note = "SauceNAO first, then IQDB\u2026",
                    ),
                )
            }
            val result = withContext(Dispatchers.IO) {
                runner.run(
                    request = request,
                    token = generation,
                    // Cancellation is cooperative: the runner asks before every provider.
                    shouldContinue = { token -> !reverseCancelled && token == reverseGeneration },
                    onRun = { run -> publishReverseRun(run, generation) },
                )
            }
            // A newer run (or a cancel) took over: this answer belongs to a request nobody is waiting for.
            if (generation != reverseGeneration) return@launch
            applyReverseResult(result)
        }
    }

    /** Publishes one provider's row: `SEARCHING` first, then the provider's real answer. */
    private fun publishReverseRun(run: ReverseImageRun, generation: Long) {
        if (generation != reverseGeneration) return
        reverseRuns[run.providerId] = run
        _ui.update { state -> state.copy(reverse = state.reverse.copy(providers = reverseProviderRows())) }
    }

    private fun applyReverseResult(result: ReverseImageSearchRunner.Result) {
        searchCandidates = result.candidates.map { it.toUi(CandidateReviews.stateOf(decisions, it.key)) }
        val note = result.describeStates()
        _ui.update {
            it.copy(
                reverse = it.reverse.copy(
                    running = false,
                    candidates = withDecisions(pastedCandidates + searchCandidates),
                    note = note,
                    providers = reverseProviderRows(),
                ),
            )
        }
        val found = result.candidates.size
        when {
            found > 0 -> onMessage("$found reverse-image candidate(s). $note", ToastKind.OK)
            result.anyAnswered -> onMessage("The providers answered and found nothing. $note", ToastKind.INFO)
            else -> onMessage("No provider results. $note", ToastKind.INFO)
        }
    }

    /**
     * Stops the run. A provider already inside a blocking HTTP call cannot be preempted, so its answer is
     * discarded by the generation check instead of being applied to a request the user abandoned.
     */
    fun cancelReverseSearch() {
        reverseCancelled = true
        reverseGeneration++
        reverseJob?.cancel()
        reverseJob = null
        _ui.update {
            it.copy(
                reverse = it.reverse.copy(
                    running = false,
                    note = "Reverse-image search cancelled.",
                    providers = reverseProviderRows(),
                ),
            )
        }
        onMessage("Reverse-image search cancelled.", ToastKind.INFO)
    }


    // =========================================================================================
    // Pasted IQDB results and user decisions
    // =========================================================================================

    /**
     * Accepts an IQDB result the user pasted (a result line, or a saved page's markup).
     *
     * This is the manual half of IQDB: the app did not query the service, the user did, and
     * [IqdbResultParser] only normalises what they brought back. Rows without a URL are dropped.
     */
    fun importIqdbResult(text: String) {
        val now = System.currentTimeMillis()
        val pasted = IqdbResultParser.parsePasted(text, now)
        val hits = pasted.ifEmpty { IqdbResultParser.parsePage(text, now) }
        if (hits.isEmpty()) {
            onMessage("No IQDB result URL found in that text.", ToastKind.ERR)
            return
        }
        val run = ReverseImageRun(
            providerId = ReverseImageProviders.IQDB_ID,
            displayName = IqdbProtocol.DISPLAY_NAME,
            priority = ReverseImageProviders.IQDB_PRIORITY,
            state = ReverseImageState.SUCCESS,
            hits = hits,
        )
        // The aggregator does the dedupe, so a pasted row that matches an existing candidate merges with it
        // instead of standing beside it.
        val candidates = ReverseImageAggregator.merge(listOf(run), limit = PASTED_LIMIT)
            .map { it.toUi(CandidateReviews.stateOf(decisions, it.key)).copy(manual = true) }
        pastedCandidates = (candidates + pastedCandidates).distinctBy { it.key }
        publishCandidates()
        onMessage("${candidates.size} IQDB result(s) from your browser added.", ToastKind.OK)
    }

    /** Confirms a candidate: this is the only thing that turns a suggestion into a stated identity. */
    fun confirmCandidate(key: String) = decide(key, CandidateDecision.CONFIRMED)

    /** Rejects a candidate. The record is kept, so the same key is not re-suggested as if new. */
    fun rejectCandidate(key: String) = decide(key, CandidateDecision.REJECTED)

    /**
     * Dismisses one result card from the current list — the rubbish-bin action on an IQDB card.
     *
     * Deliberately *not* a deletion: it removes the card from this session's list only. The decision log, the
     * workbook, the wishlist, the cover cache and the provider's own records are untouched, and a later search may
     * legitimately show the same result again. Because a dismissal only edits the two in-memory lists, an
     * already-published stale run cannot resurrect the card: the generation guard drops such a run before it
     * reaches [applyReverseResult], and a fresh run replaces the list wholesale.
     */
    fun dismissCandidate(key: String) {
        if (key.isBlank()) return
        val before = searchCandidates.size + pastedCandidates.size
        searchCandidates = searchCandidates.filterNot { it.key == key }
        pastedCandidates = pastedCandidates.filterNot { it.key == key }
        if (searchCandidates.size + pastedCandidates.size == before) return
        publishCandidates()
    }

    private fun decide(key: String, decision: CandidateDecision) {
        val candidate = _ui.value.reverse.candidates.firstOrNull { it.key == key } ?: return
        decisions = CandidateReviews.applyKey(
            records = decisions,
            key = key,
            decision = decision,
            now = System.currentTimeMillis(),
            title = candidate.title,
            provider = candidate.evidence,
            url = candidate.sourceUrl,
        )
        persistDecisions()
        publishCandidates()
        val verb = if (decision == CandidateDecision.CONFIRMED) "Confirmed" else "Rejected"
        onMessage(
            "$verb: ${candidate.title}",
            if (decision == CandidateDecision.CONFIRMED) ToastKind.OK else ToastKind.INFO,
        )
    }

    private fun persistDecisions() {
        val snapshot = decisions
        scope.launch(Dispatchers.IO) { runCatching { decisionStore.save(snapshot) } }
    }

    /** Re-draws the candidate list with the decisions that are current now. */
    private fun publishCandidates() = _ui.update {
        it.copy(reverse = it.reverse.copy(candidates = withDecisions(pastedCandidates + searchCandidates)))
    }

    private fun withDecisions(candidates: List<ReverseCandidateUi>): List<ReverseCandidateUi> = candidates.map {
        it.copy(decision = CandidateReviews.stateOf(decisions, it.key))
    }

    /**
     * Drops everything a previous cover produced. Called when the capture changes, the feature is switched
     * off, or the session cache is cleared: a candidate on screen must never belong to a cover the user is no
     * longer looking at. Manual candidates are the user's own material and survive.
     */
    private fun resetReverse(note: String? = null) {
        reverseGeneration++
        reverseCancelled = true
        reverseJob?.cancel()
        reverseJob = null
        reverseRuns.clear()
        searchCandidates = emptyList()
        _ui.update {
            it.copy(
                reverse = it.reverse.copy(
                    running = false,
                    candidates = withDecisions(pastedCandidates),
                    providers = reverseProviderRows(),
                    note = note,
                ),
            )
        }
    }


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
     * The cards' `Open â†—` targets are rebuilt for [text] on each edit, so typing a title and opening
     * Melonbooks/Suruga-ya/Toranoanaâ€¦ never requires a dummy in-app search first. Nothing is launched
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
    // Step 1 â€” OCR of the captured cover
    // =========================================================================================

    /**
     * A cover was captured (camera, Auto Scan or a picked photo). Remembers it and, when the
     * feature is on, reads its text.
     *
     * [jpegBytes] is produced by
     * [com.bookcovermatcher.vision.AndroidImageCodec.encodeJpeg] â€” the 224-pixel thumbnail is far
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
            resetReverse()
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
        resetReverse()
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
            onMessage("Still working â€” tap Cancel to stop.", ToastKind.INFO)
            return
        }

        job?.cancel()
        job = scope.launch {
            val token = session.token()
            _ui.update { it.copy(ocr = OcrUi.Running("Reading the cover with Google Lensâ€¦")) }
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
     *  * selected **browser-only** platforms are never queried â€” if they are the only thing selected,
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
        val secondaryOn = prefs.secondarySources
        // A switched-off secondary source is filtered out **before** any request, so it can never be reported
        // as having been searched.
        val automatedSpecs = SearchRouting.automated(selection)
            .filter { secondaryOn || !ProviderCapabilities.isSecondary(it.id) }

        if (automatedSpecs.isEmpty()) {
            session.invalidate()
            val switchedOffOnly = SearchRouting.automated(selection).isNotEmpty()
            val shortcuts = SearchRouting.shortcuts().filter { it.hasLink }.joinToString { it.name }
            val message = when {
                // Selected, but every selected provider is a general-book source the user switched off.
                switchedOffOnly ->
                    "Open Library and Google Books are switched off, so nothing was queried. Turn the " +
                        "general-book sources back on, or select DLsite."
                shortcuts.isEmpty() ->
                    "Select at least one platform to search (Open Library, Google Books or DLsite)."
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
                if (switchedOffOnly) "General-book sources are off â€” nothing to search." else "Select at least one in-app platform to search.",
                ToastKind.INFO,
            )
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
     * read off the cover, so it is a text match â€” never a visual one.
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
        OnlineFailure.RATE_LIMITED -> "The catalogue is throttling requests â€” try again in a minute."
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
        reverseJob?.cancel()
        reverseJob = null
    }

    private companion object {
        /** Generous: the first Lens call of a session also pays for TLS setup. */
        const val OCR_TIMEOUT_SECONDS = 60

        /** How many pasted IQDB rows one submission may add: a browsing session's worth, not a dump. */
        const val PASTED_LIMIT = 20

        /**
         * One provider's budget for one search. Providers run concurrently, so this is also the
         * worst case a user waits when a shop is slow: a hung provider is dropped, the others are
         * kept, and its row says it timed out.
         */
        const val PROVIDER_TIMEOUT_MS = 20_000L
    }
}

