package com.bookcovermatcher

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
}

/** What one provider did in the last search. Drawn as a status row, never as a success claim. */
enum class ProviderState {
    /** Automated provider, not queried yet. */
    READY,
    SEARCHING,
    RESULTS,
    EMPTY,
    FAILED,

    /** The provider refuses automated clients; the row offers its search page in the browser. */
    MANUAL,
}

/**
 * One row of the panel's provider list.
 *
 * [detail] is the provider's own explanation (its note) or the measured outcome of the last search.
 * [searchUrl] is always the provider's real search page for the current query, so a manual provider
 * is still usable — by the user's browser, not by a disguised request.
 */
data class ProviderStatusUi(
    val id: String,
    val name: String,
    val host: String,
    val manual: Boolean,
    val state: ProviderState,
    val detail: String?,
    val searchUrl: String,
)

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


data class OnlineSearchUi(
    val enabled: Boolean = false,
    val bridge: BridgeUi = BridgeUi.Checking,
    val ocr: OcrUi = OcrUi.Idle,
    val query: String = "",
    val catalogue: CatalogueUi = CatalogueUi.Idle,
    val autoSearch: Boolean = true,
    val ocrLanguage: String = "",
    val targetLanguage: String = "",
    /**
     * Every provider of this build, with what it did in the last search. Filled from
     * [com.bookcovermatcher.core.online.ProviderCatalog], so the panel can never show a provider the
     * app does not actually have, nor imply that a manual provider answered.
     */
    val providers: List<ProviderStatusUi> = emptyList(),
) {
    /** True while either half of the feature is working. */
    val isBusy: Boolean get() = ocr is OcrUi.Running || catalogue is CatalogueUi.Running

    /** Providers the app queried and that answered with at least one result. */
    val answeredProviders: List<ProviderStatusUi>
        get() = providers.filter { it.state == ProviderState.RESULTS }
}
