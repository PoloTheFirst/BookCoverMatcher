// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.online

import android.content.Context
import com.bookcovermatcher.CandidateLists
import com.bookcovermatcher.OnlineSearchUi
import com.bookcovermatcher.ToastKind
import com.bookcovermatcher.core.online.SearchLimits
import com.bookcovermatcher.core.online.CandidateDecision
import com.bookcovermatcher.core.online.CandidateDecisionRecord
import com.bookcovermatcher.core.online.CandidateReviews
import com.bookcovermatcher.core.online.reverse.DiscoveryImageState
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun createDiscovery(
    context: Context,
    scope: CoroutineScope,
    prefs: OnlineSearchPrefs,
    state: MutableStateFlow<OnlineSearchUi>,
    capture: () -> ByteArray?,
    onMessage: (String, ToastKind) -> Unit,
): ReverseDiscovery? {
    val key = SauceNaoKeyStore(context)
    return ReverseDiscovery(context, scope, prefs, state, capture, onMessage, key,
        listOf(SauceNaoReverseImageSource(apiKey = key::key), IqdbReverseImageSource()),
        { true },
    )
}

internal class ReverseDiscovery(
    context: Context,
    private val scope: CoroutineScope,
    private val prefs: OnlineSearchPrefs,
    private val _ui: MutableStateFlow<OnlineSearchUi>,
    private val capture: () -> ByteArray?,
    private val onMessage: (String, ToastKind) -> Unit,
    private val sauceNaoKey: SauceNaoKeyStore,
    private val reverseSources: List<ReverseImageSource>,
    val reverseAvailable: () -> Boolean,
) : DiscoveryLifecycle {
    private val discoveryCovers = DiscoveryCoverLoader()
    private val decisionStore = CandidateDecisionStore(context)

    private val reverseRuns = LinkedHashMap<String, ReverseImageRun>()
    private var reverseJob: Job? = null

    /** The picture batch of the current search; cancelled when a newer search replaces it. */
    private var coverJob: Job? = null

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

    /** Confirmations and rejections, loaded once from the profile's decision log. */
    private var decisions: Map<String, CandidateDecisionRecord> =
        runCatching { decisionStore.load() }.getOrDefault(emptyMap())

    init {
        _ui.update { it.copy(reverse = it.reverse.copy(providers = reverseProviderRows(), sauceNaoKeyHint = sauceNaoKey.hint())) }
    }

    /**
     * The two provider rows, built from the sources themselves plus whatever the last run reported.
     *
     * The rows themselves are built by [ReverseProviderRows], which takes everything it needs as parameters —
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
        if (!reverseAvailable()) return
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
        if (!reverseAvailable()) return
        sauceNaoKey.clear()
        reverseRuns.remove(ReverseImageProviders.SAUCE_NAO_ID)
        _ui.update { it.copy(reverse = it.reverse.copy(sauceNaoKeyHint = null, providers = reverseProviderRows())) }
        onMessage("SauceNAO key removed; SauceNAO will not be queried.", ToastKind.INFO)
    }

    /**
     * Starts reverse-image discovery on the captured cover.
     *
     * Sequence, fixed by the release: SauceNAO first (if a key is stored), IQDB second (manual-only, so it
     * reports `MANUAL_ONLY` and offers its own page). Each provider publishes its row as it runs, so SauceNAO's
     * results are visible while IQDB is still being handled — nothing waits for the slower provider.
     */
    fun runReverseSearch() {
        if (!reverseAvailable()) return
        val bytes = capture()
        if (bytes == null) {
            onMessage("Scan a cover first — reverse-image search needs the image itself.", ToastKind.ERR)
            return
        }
        if (!_ui.value.enabled) {
            onMessage("Turn online search on first.", ToastKind.INFO)
            return
        }
        if (_ui.value.reverse.running || _ui.value.isBusy) {
            onMessage("Still working — tap Cancel to stop.", ToastKind.INFO)
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
        val runner = ReverseImageSearchRunner(reverseSources)

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
                    shouldContinue = { token -> !reverseCancelled && token == reverseGeneration && reverseAvailable() },
                    onRun = { run -> publishReverseRun(run, generation) },
                )
            }
            // A newer run (or a cancel) took over: this answer belongs to a request nobody is waiting for.
            if (generation != reverseGeneration || !reverseAvailable()) return@launch
            applyReverseResult(result)
        }
    }

    /** Publishes one provider's row: `SEARCHING` first, then the provider's real answer. */
    private fun publishReverseRun(run: ReverseImageRun, generation: Long) {
        if (generation != reverseGeneration || !reverseAvailable()) return
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
        // Pictures are decoration: they are fetched after the results are already on screen, and a failure changes
        // nothing about the provider's status, the candidate list or the user's ability to bookmark the result.
        loadDiscoveryImages(generation = reverseGeneration)
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
        if (!reverseAvailable()) return
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
        if (!reverseAvailable()) return
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
        // Pasted rows are candidates like any other: the same conservative picture loader runs for them, and it can
        // only ever decorate the card.
        loadDiscoveryImages(generation = reverseGeneration)
        onMessage("${candidates.size} IQDB result(s) from your browser added.", ToastKind.OK)
    }

    /** Confirms a candidate: this is the only thing that turns a suggestion into a stated identity. */
    fun confirmCandidate(key: String) = decide(key, CandidateDecision.CONFIRMED)

    /** Rejects a candidate. The record is kept, so the same key is not re-suggested as if new. */
    fun rejectCandidate(key: String) = decide(key, CandidateDecision.REJECTED)

    /**
     * Dismisses one result card from the current list — the rubbish-bin action on a candidate card.
     *
     * Deliberately *not* a deletion: it removes the card from this session's list only. The decision log, the
     * workbook, the wishlist, the cover cache and the provider's own records are untouched, and a later search may
     * legitimately show the same result again. Because a dismissal only edits the two in-memory lists, an
     * already-published stale run cannot resurrect the card: the generation guard drops such a run before it
     * reaches [applyReverseResult], and a fresh run replaces the list wholesale. The same rule protects the card from
     * a picture that is still being fetched — [patchCandidate] refuses a key that is no longer present.
     */
    fun dismissCandidate(key: String) {
        if (!reverseAvailable()) return
        val next = currentLists().dismiss(key) ?: return
        adopt(next)
        publishCandidates()
    }

    private fun decide(key: String, decision: CandidateDecision) {
        if (!reverseAvailable()) return
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
        it.copy(reverse = it.reverse.copy(candidates = withDecisions(currentLists().visible)))
    }

    private fun withDecisions(candidates: List<ReverseCandidateUi>): List<ReverseCandidateUi> = candidates.map {
        it.copy(decision = CandidateReviews.stateOf(decisions, it.key))
    }

    /**
     * Starts (or restarts) the picture batch for the candidates now on screen.
     *
     * Every candidate that has not been tried yet is marked [DiscoveryImageState.LOADING] immediately, so the card
     * can draw its placeholder without a layout jump, and the fetch itself runs sequentially on the IO dispatcher —
     * one page at a time, so a slow host cannot open ten sockets at once.
     *
     * The batch belongs to a search [generation]. A result that arrives after a newer search started, or after the
     * card was dismissed, is dropped by [patchCandidate] instead of being written into the list: a picture can never
     * reinsert a card the user removed, and it can never appear on a candidate from a previous cover.
     */
    private fun loadDiscoveryImages(generation: Long) {
        val targets = _ui.value.reverse.candidates.filter { it.imageState == DiscoveryImageState.NOT_REQUESTED }
        if (targets.isEmpty()) return
        markImageState(targets.map { it.key }, DiscoveryImageState.LOADING)
        coverJob?.cancel()
        coverJob = scope.launch {
            for (target in targets) {
                if (generation != reverseGeneration || !reverseAvailable()) return@launch
                val loaded = withContext(Dispatchers.IO) {
                    discoveryCovers.load(target.imageUrl, target.sourceUrl)
                }
                if (generation != reverseGeneration || !reverseAvailable()) return@launch
                patchCandidate(target.key) { candidate ->
                    if (loaded == null) {
                        candidate.copy(imageState = DiscoveryImageState.UNAVAILABLE)
                    } else {
                        candidate.copy(
                            imageBytes = loaded.bytes,
                            imageSource = loaded.source,
                            imageState = DiscoveryImageState.AVAILABLE,
                        )
                    }
                }
            }
        }
    }

    private fun markImageState(keys: Collection<String>, state: DiscoveryImageState) {
        var lists = currentLists()
        var touched = false
        for (key in keys) {
            val next = lists.patch(key) { it.copy(imageState = state) } ?: continue
            lists = next
            touched = true
        }
        if (!touched) return
        adopt(lists)
        publishCandidates()
    }

    /**
     * Rewrites one candidate in place.
     *
     * Returns false — and writes nothing — when the key is in neither list any more, which is exactly the
     * "the user dismissed this card while the image was loading" case. That is why an asynchronous answer can never
     * bring a dismissed card back.
     */
    private fun patchCandidate(key: String, transform: (ReverseCandidateUi) -> ReverseCandidateUi): Boolean {
        val next = currentLists().patch(key, transform) ?: return false
        adopt(next)
        publishCandidates()
        return true
    }

    /** The two lists as one value, so the editing rules live in [CandidateLists] and are unit-tested there. */
    private fun currentLists() = CandidateLists(pasted = pastedCandidates, searched = searchCandidates)

    private fun adopt(lists: CandidateLists) {
        pastedCandidates = lists.pasted
        searchCandidates = lists.searched
    }

    /**
     * Drops everything a previous cover produced. Called when the capture changes, the feature is switched
     * off, or the session cache is cleared: a candidate on screen must never belong to a cover the user is no
     * longer looking at. Manual candidates are the user's own material and survive.
     */
    override fun reset(note: String?) {
        reverseGeneration++
        reverseCancelled = true
        reverseJob?.cancel()
        reverseJob = null
        coverJob?.cancel()
        coverJob = null
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


    override fun close() {
        reverseGeneration++
        reverseCancelled = true
        reverseJob?.cancel()
        coverJob?.cancel()
        _ui.update { it.copy(reverse = ReverseImageUi(secondarySources = it.reverse.secondarySources)) }
    }

    private companion object { const val PASTED_LIMIT = 20 }
}
