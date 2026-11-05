package com.bookcovermatcher.core.online.reverse

/**
 * Runs reverse-image discovery in the order the specification fixes: **SauceNAO first, IQDB second**.
 *
 * The runner is synchronous and deterministic on purpose — no coroutines, no clock it does not own, no
 * hidden threads — so every ordering and failure rule below is unit-tested:
 *
 *  1. **SauceNAO is tried first.** IQDB is still tried afterwards even when SauceNAO found something, as
 *     the release requires; skipping it because the first provider answered would silently halve the
 *     evidence.
 *  2. **Results are published per provider.** [onRun] fires as each provider starts and finishes, so the
 *     user sees SauceNAO's results while IQDB is still working.
 *  3. **A provider that did nothing is reported as such.** [ReverseImageState.NOT_CONFIGURED] and
 *     [ReverseImageState.MANUAL_ONLY] short-circuit before any request, so the app never claims a search
 *     that did not happen.
 *  4. **Slow is not empty.** A provider whose call exceeded *its own* budget is reported as
 *     [ReverseImageState.TIMEOUT] and its late rows are dropped, rather than being presented as a genuine
 *     answer that happened to arrive late.
 *  5. **Failures are isolated.** A failure in one provider leaves the other provider's runs and results
 *     untouched, because every run is recorded separately and only successful runs are merged.
 *  6. **Cancellation is cooperative and honest.** [shouldContinue] is checked *before* every provider; when
 *     it says stop, the remaining providers are recorded as [ReverseImageState.CANCELLED] and never
 *     queried. The result carries the token the caller supplied, which is how a stale answer is dropped.
 */
class ReverseImageSearchRunner(
    private val sources: List<ReverseImageSource>,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** What one run of the whole pipeline produced. */
    class Result(
        /** The generation/token the caller supplied: a caller drops a result whose token is no longer current. */
        val token: Long,
        /** One entry per provider, in the order they were tried. */
        val runs: List<ReverseImageRun>,
        /** Merged candidates from the providers that genuinely answered. */
        val candidates: List<ReverseImageCandidate>,
    ) {
        /** True when at least one provider really answered (with results or with a genuine empty answer). */
        val anyAnswered: Boolean get() = runs.any { it.state.answered }

        /** The providers that returned at least one row. */
        val contributingRuns: List<ReverseImageRun> get() = runs.filter { it.contributed }

        /** True when a provider was never queried because of configuration or access, not because it failed. */
        val someProvidersWereNotQueried: Boolean
            get() = runs.any { it.state.searchedNothing || it.state == ReverseImageState.MANUAL_ONLY }

        fun runOf(providerId: String): ReverseImageRun? =
            runs.firstOrNull { it.providerId.equals(providerId, ignoreCase = true) }

        fun stateOf(providerId: String): ReverseImageState =
            runOf(providerId)?.state ?: ReverseImageState.READY

        /** One line naming every provider state, for a toast or an empty-state message. */
        fun describeStates(): String = runs.joinToString(" \u00B7 ") { run ->
            val suffix = when (run.state) {
                ReverseImageState.SUCCESS -> " ${run.hits.size} result(s)"
                ReverseImageState.NO_RESULTS -> " nothing found"
                ReverseImageState.TIMEOUT -> " timed out"
                ReverseImageState.NOT_CONFIGURED -> " not configured"
                ReverseImageState.MANUAL_ONLY -> " manual only"
                ReverseImageState.CANCELLED -> " cancelled"
                ReverseImageState.RATE_LIMITED -> " rate limited"
                ReverseImageState.QUOTA_EXCEEDED -> " quota used up"
                ReverseImageState.BLOCKED -> " blocked"
                ReverseImageState.UNAVAILABLE -> " unreachable"
                ReverseImageState.ERROR -> " failed"
                ReverseImageState.SEARCHING -> " searching"
                ReverseImageState.READY -> ""
            }
            "${run.displayName}$suffix"
        }
    }

    /**
     * @param shouldContinue asked before every provider with the caller's token; returning false stops the
     *        pipeline and records the remaining providers as cancelled
     * @param onRun progress callback: fires when a provider starts ([ReverseImageState.SEARCHING]) and again
     *        with its final state, so already-received results can be shown immediately
     */
    fun run(
        request: ReverseImageRequest,
        token: Long = 0L,
        shouldContinue: (Long) -> Boolean = { true },
        onRun: (ReverseImageRun) -> Unit = {},
    ): Result {
        val ordered = sources.sortedBy { it.priority }
        val runs = ArrayList<ReverseImageRun>(ordered.size)
        for (source in ordered) {
            if (!shouldContinue(token)) {
                val cancelled = cancelledRun(source)
                runs += cancelled
                onRun(cancelled)
                continue
            }

            val readiness = source.readiness()
            if (readiness != ReverseImageState.READY) {
                val notRun = runOf(source, readiness, emptyList(), notRunMessage(source, readiness))
                runs += notRun
                onRun(notRun)
                continue
            }

            onRun(runOf(source, ReverseImageState.SEARCHING, emptyList(), null))
            val started = clock()
            val outcome = try {
                source.search(request)
            } catch (t: Throwable) {
                ReverseImageOutcome.Failed(
                    ReverseImageState.ERROR,
                    "${source.displayName} failed unexpectedly: ${t.message ?: t.javaClass.simpleName}",
                )
            }
            val elapsed = (clock() - started).coerceAtLeast(0L)

            val finished = when {
                // Checked before the outcome: a late answer must not be presented as a prompt one.
                elapsed > source.timeoutMs -> runOf(
                    source,
                    ReverseImageState.TIMEOUT,
                    emptyList(),
                    "No answer within ${source.timeoutMs / 1000}s \u2014 a timeout, not an empty result.",
                    elapsed,
                )
                outcome is ReverseImageOutcome.Found && outcome.hits.isNotEmpty() -> runOf(
                    source,
                    ReverseImageState.SUCCESS,
                    outcome.hits,
                    null,
                    elapsed,
                    outcome.quota,
                )
                outcome is ReverseImageOutcome.Found || outcome is ReverseImageOutcome.NoResults -> runOf(
                    source,
                    ReverseImageState.NO_RESULTS,
                    emptyList(),
                    null,
                    elapsed,
                    null,
                )
                else -> {
                    val failed = outcome as ReverseImageOutcome.Failed
                    runOf(source, failed.state, emptyList(), failed.message, elapsed, failed.quota)
                }
            }
            runs += finished
            onRun(finished)
        }
        return Result(token, runs, ReverseImageAggregator.merge(runs))
    }

    private fun runOf(
        source: ReverseImageSource,
        state: ReverseImageState,
        hits: List<ReverseImageHit>,
        message: String?,
        elapsedMs: Long = 0L,
        quota: ReverseImageQuota? = null,
    ): ReverseImageRun = ReverseImageRun(
        providerId = source.id,
        displayName = source.displayName,
        priority = source.priority,
        state = state,
        hits = hits,
        message = message,
        elapsedMs = elapsedMs,
        quota = quota,
    )

    private fun cancelledRun(source: ReverseImageSource): ReverseImageRun = runOf(
        source,
        ReverseImageState.CANCELLED,
        emptyList(),
        "Stopped before this provider was queried; nothing was sent.",
    )

    private fun notRunMessage(source: ReverseImageSource, state: ReverseImageState): String = when (state) {
        ReverseImageState.NOT_CONFIGURED ->
            "${source.displayName} needs a configured credential, so it was not queried."
        ReverseImageState.MANUAL_ONLY -> IqdbProtocol.manualOnlyReason()
        ReverseImageState.BLOCKED -> "${source.displayName} refused automated access; use its search page."
        ReverseImageState.UNAVAILABLE -> "${source.displayName} is unavailable in this build."
        else -> "${source.displayName} was not queried."
    }
}
