package com.bookcovermatcher.online

import com.bookcovermatcher.core.online.reverse.IqdbProtocol
import com.bookcovermatcher.core.online.reverse.ReverseImageAutomation
import com.bookcovermatcher.core.online.reverse.ReverseImageOutcome
import com.bookcovermatcher.core.online.reverse.ReverseImageProviders
import com.bookcovermatcher.core.online.reverse.ReverseImageRequest
import com.bookcovermatcher.core.online.reverse.ReverseImageSource
import com.bookcovermatcher.core.online.reverse.ReverseImageState

/**
 * Reverse-image discovery through **IQDB** — second priority, and **manual-only** in this build.
 *
 * This adapter is real, and it is honest about doing nothing: [readiness] is `MANUAL_ONLY` and [search]
 * never opens a socket. The verification behind that decision is recorded in [IqdbProtocol]:
 *
 *  * IQDB publishes no API (its page is an HTML form with no documented endpoint or response contract);
 *  * its own `robots.txt` disallows `/multi-search`, `/db-search`, `/kc-search`, `/mi-search`, `/index` and
 *    `/?url=`, i.e. the routes an automated query would have to use.
 *
 * The app therefore performs the sanctioned handoff: [searchPageUrl] opens IQDB in the user's browser, and
 * whatever the user brings back (a result line, a saved page, a listing) is parsed by
 * `IqdbResultParser` and merged into the same candidate pipeline. A future automated integration must
 * replace this class — not silently switch it on — because the classification lives in one place.
 */
class IqdbReverseImageSource : ReverseImageSource {

    override val id: String get() = ReverseImageProviders.IQDB_ID
    override val displayName: String get() = IqdbProtocol.DISPLAY_NAME
    override val priority: Int get() = ReverseImageProviders.IQDB_PRIORITY
    override val automation: ReverseImageAutomation get() = ReverseImageAutomation.MANUAL_ONLY

    /**
     * Declared anyway, and longer than SauceNAO's on purpose: IQDB is regularly slower. Nothing in this build
     * spends it, because nothing is submitted — but the number documents the provider's character for the
     * day automation becomes permissible.
     */
    override val timeoutMs: Long = 90_000L

    override val searchPageUrl: String? get() = IqdbProtocol.SEARCH_PAGE

    override fun readiness(): ReverseImageState = ReverseImageState.MANUAL_ONLY

    override fun search(request: ReverseImageRequest): ReverseImageOutcome = ReverseImageOutcome.Failed(
        ReverseImageState.MANUAL_ONLY,
        IqdbProtocol.manualOnlyReason(),
    )
}
