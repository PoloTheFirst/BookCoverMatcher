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
    data class Found(val query: String, val items: List<ProductResultUi>, val totalAvailable: Int?, val source: String) : CatalogueUi
    data class Empty(val query: String, val source: String) : CatalogueUi
    data class Failed(val message: String) : CatalogueUi
}

/**
 * One catalogue result, ready to draw.
 *
 * [coverJpeg] arrives after the text does (covers are fetched one by one), and stays null when the
 * catalogue has no cover. [detailUrl] is the catalogue's own page — the app never builds a
 * shopping link of its own.
 */
class ProductResultUi(
    val id: String,
    val title: String,
    val authors: String,
    val meta: String,
    val languages: String,
    val coverJpeg: ByteArray?,
    val detailUrl: String,
    val source: String,
)

data class OnlineSearchUi(
    val enabled: Boolean = false,
    val bridge: BridgeUi = BridgeUi.Checking,
    val ocr: OcrUi = OcrUi.Idle,
    val query: String = "",
    val catalogue: CatalogueUi = CatalogueUi.Idle,
    val autoSearch: Boolean = true,
    val ocrLanguage: String = "",
    val targetLanguage: String = "",
) {
    /** True while either half of the feature is working. */
    val isBusy: Boolean get() = ocr is OcrUi.Running || catalogue is CatalogueUi.Running
}
