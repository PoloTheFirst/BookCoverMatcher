package com.bookcovermatcher

import com.bookcovermatcher.core.Config
import com.bookcovermatcher.core.autoscan.ScanBadge
import com.bookcovermatcher.core.autoscan.ScanTick
import com.bookcovermatcher.core.search.MatchLevel
import java.io.File

/** State of the OpenCLIP model file. */
sealed interface ModelUi {
    data object Checking : ModelUi
    data class Installing(val label: String, val progress: Float?) : ModelUi
    data object Missing : ModelUi
    data class Failed(val message: String) : ModelUi
    data class Ready(val dim: Int, val sizeBytes: Long) : ModelUi
}

class WorkbookUi(val name: String, val sizeText: String)

/** The cover that will be searched for. */
class TargetUi(val thumbJpeg: ByteArray, val summary: String)

class ResultUi(
    val rank: Int,
    val cell: String,
    val source: String,
    val cosine: Float,
    val percent: Float,
    val level: MatchLevel,
    val thumbJpeg: ByteArray,
)

sealed interface ResultsUi {
    /** Nothing searched yet. */
    data object Initial : ResultsUi

    /** A capture was made or the search found nothing. */
    data object Empty : ResultsUi

    class Ranked(val items: List<ResultUi>, val comparedWith: Int) : ResultsUi
}

/** Matching in progress. [progress] is 0..1, or null when it cannot be measured. */
class BusyUi(val label: String, val progress: Float?)

enum class ToastKind { INFO, OK, ERR }

class ToastUi(val id: Long, val text: String, val kind: ToastKind)

/** The green "Record added to wishlist" bar. [recordId] is null once the record was removed. */
class PromptUi(val id: Long, val recordId: String?, val text: String)

class WishlistMatchUi(val cell: String, val source: String, val percentText: String, val thumb: File?)

class WishlistRecordUi(
    val id: String,
    val name: String,
    val timeText: String,
    val scanThumb: File?,
    val matches: List<WishlistMatchUi>,
)

class RenameUi(val recordId: String, val currentName: String)

data class UiState(
    val model: ModelUi = ModelUi.Checking,

    // camera
    val cameraWanted: Boolean = false,
    val cameraLive: Boolean = false,
    val cameraRestart: Int = 0,
    val cameraBadge: String = ScanBadge.TAP_TO_START,
    val autoScan: Boolean = false,
    val scanTick: ScanTick? = null,

    // panel 1
    val workbook: WorkbookUi? = null,
    val workbookLoading: Boolean = false,
    val rangeText: String = Config.DEFAULT_RANGE,
    val target: TargetUi? = null,
    val busy: BusyUi? = null,

    // panel 2
    val results: ResultsUi = ResultsUi.Initial,

    // wishlist
    val wishlistOpen: Boolean = false,
    val wishlist: List<WishlistRecordUi> = emptyList(),
    val pendingDeleteId: String? = null,
    val renaming: RenameUi? = null,

    // transient
    val prompt: PromptUi? = null,
    val toast: ToastUi? = null,
) {
    val isBusy: Boolean get() = busy != null
}
