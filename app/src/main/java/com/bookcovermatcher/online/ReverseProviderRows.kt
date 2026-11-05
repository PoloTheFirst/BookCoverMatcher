package com.bookcovermatcher.online

import com.bookcovermatcher.ReverseImageProviderUi
import com.bookcovermatcher.core.online.reverse.IqdbProtocol
import com.bookcovermatcher.core.online.reverse.ReverseImageAutomation
import com.bookcovermatcher.core.online.reverse.ReverseImageProviders
import com.bookcovermatcher.core.online.reverse.ReverseImageRun
import com.bookcovermatcher.core.online.reverse.ReverseImageSource
import com.bookcovermatcher.core.online.reverse.ReverseImageState

/**
 * Builds the two reverse-image provider rows the panel draws.
 *
 * A row's state is the provider's *own* state: `NOT_CONFIGURED` before a key exists, `MANUAL_ONLY` for IQDB,
 * `SEARCHING` while a request is outstanding, and only `SUCCESS`/`NO_RESULTS` when a genuine answer arrived.
 * Nothing here can turn "not run" into "found nothing".
 *
 * **Why this is a standalone object that takes its inputs as parameters.** `OnlineSearchController` builds its
 * UI state in a property initializer, and Kotlin runs property initializers in *declaration order*. In
 * v2.5.0-dev.2 the row builder read the controller's `runs` field from that initializer while the field was
 * still unassigned (`null`), so `runs[providerId]` threw a `NullPointerException` inside the constructor and the
 * app crashed on **every** launch. Taking the map as a parameter removes the failure mode rather than moving the
 * declaration around: there is no field left to read early, and the function is plain Kotlin, so it is covered by
 * the default unit-test suite (`ReverseProviderRowsTest`).
 */
object ReverseProviderRows {

    /**
     * @param sources the providers of this build, in any order (they are sorted by priority)
     * @param runs what each provider did in the last run; empty before the first run, which is the state the
     *        controller initialises with
     */
    fun of(
        sources: List<ReverseImageSource>,
        runs: Map<String, ReverseImageRun>,
    ): List<ReverseImageProviderUi> = sources
        .sortedBy { it.priority }
        .map { source ->
            val run = runs[source.id]
            val state = run?.state ?: source.readiness()
            ReverseImageProviderUi(
                id = source.id,
                displayName = source.displayName,
                priority = source.priority,
                manualOnly = source.automation == ReverseImageAutomation.MANUAL_ONLY,
                searchPageUrl = source.searchPageUrl,
                state = state,
                detail = run?.message ?: detailFor(source.id, state),
                resultCount = run?.hits?.size ?: 0,
                quota = run?.quota?.describe(),
            )
        }

    /** The resting explanation for a provider that has not run yet — never a claim about results. */
    fun detailFor(providerId: String, state: ReverseImageState): String = when (state) {
        ReverseImageState.NOT_CONFIGURED ->
            "Needs your own SauceNAO API key. Stored encrypted; never shown again after saving."
        ReverseImageState.READY -> "Ready. Tried first."
        ReverseImageState.MANUAL_ONLY -> IqdbProtocol.manualOnlyReason()
        else -> if (providerId == ReverseImageProviders.IQDB_ID) {
            "Tried second, after SauceNAO."
        } else {
            "Tried first, before IQDB."
        }
    }
}
