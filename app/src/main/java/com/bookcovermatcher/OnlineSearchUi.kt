// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher
import com.bookcovermatcher.core.wishlist.ItemDraft
import com.bookcovermatcher.core.wishlist.ItemKind
import com.bookcovermatcher.core.wishlist.MatchType

import com.bookcovermatcher.core.online.CandidateDecision
import com.bookcovermatcher.core.online.DiscoveryDrafts
import com.bookcovermatcher.core.online.ProviderCatalog
import com.bookcovermatcher.core.online.reverse.DiscoveryImageSource
import com.bookcovermatcher.core.online.reverse.DiscoveryImageState
import com.bookcovermatcher.core.online.reverse.ReverseImageCandidate
import com.bookcovermatcher.core.online.reverse.ReverseImageState

/**
 * UI state of the online-search panel.
 *
 * Kept in its own file next to [UiState] (rather than inside it) because the whole feature is an
 * addition to the offline matcher: `UiState` still describes the workbook workflow exactly as
 * before, and `UiState.online` is the only new field.
 */

/** Status of the embedded `chrome-lens-py` runtime. */
sealed interface BridgeUi {
    data object Checking : BridgeUi
    data class Ready(val library: String, val version: String) : BridgeUi
    data class Unavailable(val reason: String) : BridgeUi
}

/** Reading the text printed on the captured cover. */
sealed interface OcrUi {
    /** Nothing captured yet, or the feature is off. */
    data object Idle : OcrUi
    data class Running(val label: String) : OcrUi

    /** Text was recognised. [text] is editable: the user may fix it before searching. */
    data class Done(val text: String, val language: String?, val suggestion: String, val libraryVersion: String) : OcrUi

    /** The cover came back with no readable text. */
    data class NoText(val libraryVersion: String) : OcrUi

    data class Failed(val message: String) : OcrUi
}

/** The catalogue (product) search. Separate from [OcrUi] because it is a separate service. */
sealed interface CatalogueUi {
    data object Idle : CatalogueUi
    data class Running(val query: String) : CatalogueUi
    data class Found(
        val query: String,
        val items: List<ProductResultUi>,
        val totalAvailable: Int?,
        val source: String,
        /** One catalogue failed while another answered; shown, never hidden. */
        val note: String? = null,
    ) : CatalogueUi
    data class Empty(val query: String, val source: String, /** Other providers' failures, when some answered. */ val note: String? = null) : CatalogueUi
    data class Failed(val message: String) : CatalogueUi

    /**
     * The search could not run in the app because the selected platforms are browser-only. No request
     * was made and no result list exists; the message explains where the results can be seen.
     */
    data class BrowserOnly(val message: String) : CatalogueUi

    /**
     * Nothing was queried because every selected provider is switched off (the general-book toggle). Distinct
     * from [BrowserOnly] — there is no browser page to offer — and distinct from [Empty], which would claim a
     * search happened.
     */
    data class SwitchedOff(val message: String) : CatalogueUi
}

/** What one provider did in the last search. Drawn as a status row, never as a success claim. */
enum class ProviderState {
    /** Automated provider, selected, not queried yet. */
    READY,
    SEARCHING,
    RESULTS,
    EMPTY,
    FAILED,

    /** The site refused the automated request (401/403/451); it is not an empty success. */
    BLOCKED,

    /** The provider refuses automated clients; the row offers its search page in the browser. */
    MANUAL,

    /** Not selected for the next search, so it was not queried. */
    SKIPPED,
}

/**
 * One row of the panel's provider list.
 *
 * [detail] is the provider's own explanation (its note) or the measured outcome of the last search.
 * [searchUrl] is the provider's real search page for the current query — built and validated by
 * `ProviderLinks` — so every platform with a verified page can be opened in the browser independently
 * of selection. It is null only for a provider with no configured page — no shipped card is in that
 * state today, and `ProviderLinks` refuses to build one rather than drawing a dead button.
 * [selected] is only ever true for a selectable (automated) provider; [manual] is a capability fact
 * that selection cannot change.
 */
data class ProviderStatusUi(
    val id: String,
    val name: String,
    val host: String,
    val manual: Boolean,
    val selected: Boolean,
    val state: ProviderState,
    val detail: String?,
    val searchUrl: String?,
)

/**
 * The same rows with every browser link rebuilt for [query], which is the **text field's current
 * value**, not the query of the last search.
 *
 * A card's `Open ↗` target is a pure function of (provider, current query), so it must never lag
 * behind the field: the user may type a title and open a browser-only shop without searching first,
 * and a link pointing at a previous search is a bug, not a feature.
 *
 * Only `searchUrl` changes. [ProviderStatusUi.state]/[ProviderStatusUi.detail] still describe the
 * last search that actually ran, so editing the text never pretends a platform answered. A provider
 * with no verified page keeps a null link, exactly as `ProviderLinks` decides.
 */
fun List<ProviderStatusUi>.withQuery(query: String): List<ProviderStatusUi> = map { row ->
    val spec = ProviderCatalog.byId(row.id)
    row.copy(
        searchUrl = if (spec != null && spec.hasLink) {
            runCatching { spec.searchUrl(query) }.getOrNull()
        } else {
            null
        },
    )
}

/**
 * One catalogue result, ready to draw.
 *
 * [coverJpeg] arrives after the text does (covers are fetched one by one), and stays null when the
 * catalogue has no cover. [detailUrl] is the catalogue's own page — the app never builds a shopping
 * link of its own.
 *
 * Everything a provider may or may not send is nullable: a value it did not send stays null and the
 * card draws nothing for it. [price] and [availability] exist so the layout stays capable, but Open
 * Library returns neither, so they are null today and the card says so by omission rather than by
 * inventing a number.
 *
 * [key] is the store identity ([com.bookcovermatcher.core.wishlist.ItemIdentity]); the panel uses it
 * to look up whether this result is bookmarked or already in a wishlist profile without asking the
 * online controller to know about the wishlist.
 */
data class ProductResultUi(
    val id: String,
    val key: String,
    val title: String,
    val authors: String,
    /** Circle (shop listing), kept apart from [authors]. */
    val circle: String? = null,
    /** Genuine artist names. Never the display line, which falls back to the circle for shop listings. */
    val artistNames: List<String> = emptyList(),
    val meta: String,
    val languages: String,
    val coverJpeg: ByteArray?,
    val detailUrl: String,
    val source: String,
    val provider: String,
    val sourceDomain: String?,
    val identifier: String?,
    val description: String? = null,
    val price: String? = null,
    val availability: String? = null,
    /** `Text match` for OCR → catalogue query. Never `Visual match` unless a provider really did one. */
    val matchType: String = "Text match",
    val bookmarked: Boolean = false,
    val wishlisted: Boolean = false,
)

/**
 * Replaces the cover of the item whose **id** is [id].
 *
 * Covers are fetched after the text and patched into an already-drawn list. Patching by index (what
 * this app used to do) puts a cover on the wrong card as soon as the list is deduplicated, promoted
 * or reordered, so the list is addressed by identity instead.
 */
fun patchCoverById(items: List<ProductResultUi>, id: String, coverJpeg: ByteArray?): List<ProductResultUi> {
    if (coverJpeg == null) return items
    val index = items.indexOfFirst { it.id == id }
    if (index < 0) return items
    return items.toMutableList().also { it[index] = it[index].copy(coverJpeg = coverJpeg) }
}


/**
 * One reverse-image provider row: who it is, what it may do, and what it did in the last run.
 *
 * [state] is the core [ReverseImageState] itself, not a UI copy, so a state can never be re-labelled on its
 * way to the screen. [detail] carries the provider's own explanation or the measured outcome.
 */
data class ReverseImageProviderUi(
    val id: String,
    val displayName: String,
    /** 1 = SauceNAO, 2 = IQDB: shown as "tried first"/"tried second". */
    val priority: Int,
    /** True when the app never submits the image to this provider. */
    val manualOnly: Boolean,
    val searchPageUrl: String?,
    val state: ReverseImageState,
    val detail: String?,
    val resultCount: Int = 0,
    /** The provider's own remaining allowance, when it reported one. */
    val quota: String? = null,
) {
    val isSearching: Boolean get() = state == ReverseImageState.SEARCHING
}

/**
 * One reverse-image candidate, ready to review.
 *
 * [evidence] is a line such as `SauceNAO 95.1% · IQDB 92%`: each provider's own number, attributed. There is
 * deliberately no combined score, because the two numbers are not on a common scale.
 */
data class ReverseCandidateUi(
    val key: String,
    val title: String,
    val circle: String,
    val author: String,
    val productId: String,
    val evidence: String,
    val sourceUrl: String?,
    val imageUrl: String?,
    /** How many providers reported it. More than one is stronger provenance, not a probability. */
    val providerCount: Int,
    val decision: CandidateDecision,
    /**
     * Provider ids that reported this candidate, in priority order (`saucenao`, `iqdb`).
     *
     * Kept because the card's dismissal control is provider-specific: it may only appear where dismissing cannot
     * throw away another provider's evidence.
     */
    val providerIds: List<String> = emptyList(),
    /** True when the user supplied this candidate themselves (they pasted an IQDB result). */
    val manual: Boolean = false,
    val note: String? = null,
    /**
     * The picture for this candidate, when one could be obtained.
     *
     * Never evidence for the match — it exists so the user can compare covers by eye. [imageState] says what
     * happened, so the card can draw an honest placeholder instead of an empty gap.
     */
    val imageBytes: ByteArray? = null,
    /** Where [imageBytes] came from: the provider's own result image, or a page's metadata. */
    val imageSource: DiscoveryImageSource? = null,
    val imageState: DiscoveryImageState = DiscoveryImageState.NOT_REQUESTED,
) {
    /**
     * True when IQDB found this candidate and SauceNAO did not.
     *
     * Kept from v2.5.0-dev.3 for the IQDB dismissal control, which must not change behaviour.
     */
    val isIqdbOnly: Boolean
        get() = providerIds.any { it.equals("iqdb", ignoreCase = true) } &&
            providerIds.none { it.equals("saucenao", ignoreCase = true) }

    /** True when SauceNAO found this candidate and IQDB did not. */
    val isSauceNaoOnly: Boolean
        get() = providerIds.any { it.equals("saucenao", ignoreCase = true) } &&
            providerIds.none { it.equals("iqdb", ignoreCase = true) }

    /** True when a reverse-image provider reported this card, so it can be dismissed. */
    val isDismissible: Boolean
        get() = providerIds.any { it.equals("saucenao", ignoreCase = true) || it.equals("iqdb", ignoreCase = true) }

    /**
     * The accessibility label of the dismissal control, naming the provider whose result is removed.
     *
     * A card both providers reported is one card with both references, so its label says "discovery" rather than
     * pretending only one provider's evidence would go.
     */
    val dismissLabel: String
        get() = when {
            isIqdbOnly -> "Dismiss IQDB result"
            isSauceNaoOnly -> "Dismiss SauceNAO result"
            else -> "Dismiss discovery result"
        }

    /** `SauceNAO`, `IQDB` or `SauceNAO + IQDB` — the attribution a stored copy keeps. */
    val providerLabel: String get() = DiscoveryDrafts.providerLabel(providerIds)
}

/** Everything the reverse-image section draws. */
data class ReverseImageUi(
    val running: Boolean = false,
    val providers: List<ReverseImageProviderUi> = emptyList(),
    val candidates: List<ReverseCandidateUi> = emptyList(),
    /** `••••••••ab12` when a SauceNAO key is stored; null when none is. */
    val sauceNaoKeyHint: String? = null,
    /** Whether the supplementary general-book sources take part in a search. */
    val secondarySources: Boolean = true,
    /** A line about the last reverse-image run (provider states, or why nothing ran). */
    val note: String? = null,
) {
    val sauceNaoConfigured: Boolean get() = sauceNaoKeyHint != null

    /** Candidates the user has not decided about yet. */
    val undecided: List<ReverseCandidateUi>
        get() = candidates.filter { it.decision == CandidateDecision.SUGGESTED }

    val confirmedCount: Int get() = candidates.count { it.decision == CandidateDecision.CONFIRMED }
    val rejectedCount: Int get() = candidates.count { it.decision == CandidateDecision.REJECTED }
}

data class OnlineSearchUi(
    val enabled: Boolean = false,
    val bridge: BridgeUi = BridgeUi.Checking,
    val ocr: OcrUi = OcrUi.Idle,
    val query: String = "",
    val catalogue: CatalogueUi = CatalogueUi.Idle,
    val autoSearch: Boolean = true,
    val ocrLanguage: String = "",
    val targetLanguage: String = "",
    /** Platforms selected for the next search (provider ids). Intent only; see [providers]. */
    val selectedProviders: Set<String> = emptySet(),
    /** Maximum number of online results the user asked for. Never a promise (see `SearchLimits`). */
    val resultLimit: Int = com.bookcovermatcher.core.online.SearchLimits.DEFAULT,
    /**
     * Every provider of this build, with what it did in the last search. Filled from
     * [com.bookcovermatcher.core.online.ProviderCatalog], so the panel can never show a provider the
     * app does not actually have, nor imply that a manual provider answered.
     */
    val providers: List<ProviderStatusUi> = emptyList(),
    /** The reverse-image section: two providers, their states, and the candidates they produced. */
    val reverse: ReverseImageUi = ReverseImageUi(),
    /**
     * Which OCR engine the next cover read will use: `"python"` (the shipped `chrome-lens-py`) or
     * `"rust"` (the native PP-OCRv6 engine). Only a debug build exposes the switch that changes it.
     */
    val ocrEngine: String = "python",
) {
    /** True while either half of the feature is working. */
    val isBusy: Boolean get() = ocr is OcrUi.Running || catalogue is CatalogueUi.Running

    /** Providers the app queried and that answered with at least one result. */
    val answeredProviders: List<ProviderStatusUi>
        get() = providers.filter { it.state == ProviderState.RESULTS }
}

/**
 * One online result as the store sees it.
 *
 * Authors come from the **parsed** artist names only: the display line (`authors`) falls back to the
 * shop circle for marketplace listings, so it must never become an artist.
 */
internal fun ProductResultUi.toDraft(): ItemDraft = ItemDraft(
    kind = ItemKind.ONLINE_RESULT,
    title = title,
    url = detailUrl,
    provider = provider,
    providerKey = key,
    identifier = identifier,
    description = description,
    authors = artistNames.map { it.trim() }.filter { it.isNotEmpty() },
    circle = circle,
    price = price,
    availability = availability,
    matchType = MatchType.TEXT_QUERY,
    matchTypeVerified = true,
    thumbJpeg = coverJpeg,
)

/**
 * One discovery candidate as the **wishlist store** sees it.
 *
 * The identity is the candidate's own key, so the same result bookmarked twice — now or in a later search — is the
 * same item and the store's deduplication can do its job. `provider` keeps the attribution (`SauceNAO`, `IQDB`,
 * `SauceNAO + IQDB`) because "who found it" is part of the record; nothing is invented for a field the provider did
 * not send, and the cover bytes are stored only when one was actually obtained.
 *
 * `MatchType.VISUAL` is honest here in a way `TEXT_QUERY` would not be: a reverse-image provider compared the
 * *pictures*, which is exactly what that value means.
 */
internal fun ReverseCandidateUi.toDraft(): ItemDraft = ItemDraft(
    kind = ItemKind.ONLINE_RESULT,
    title = title.ifBlank { productId.ifBlank { sourceUrl.orEmpty() } },
    url = sourceUrl,
    provider = providerLabel,
    providerKey = DiscoveryDrafts.fingerprintOf(key),
    identifier = productId.takeIf { it.isNotBlank() },
    description = note,
    authors = listOf(author).filter { it.isNotBlank() },
    circle = circle.takeIf { it.isNotBlank() },
    matchType = MatchType.VISUAL,
    matchTypeVerified = true,
    thumbJpeg = imageBytes,
)

/**
 * A reverse-image candidate as the section draws it.
 *
 * The evidence line is built from the core model's attributed references — never re-scored here, which is why this
 * function cannot introduce a combined probability even by accident.
 */
fun ReverseImageCandidate.toUi(decision: CandidateDecision): ReverseCandidateUi = ReverseCandidateUi(
    key = key,
    title = title.ifBlank { productId.ifBlank { sourceUrl.orEmpty() } },
    circle = circle,
    author = author,
    productId = productId,
    evidence = evidenceLine(),
    sourceUrl = sourceUrl,
    imageUrl = imageUrl,
    providerCount = providerIds.size,
    decision = decision,
    providerIds = providerIds,
    manual = false,
)

