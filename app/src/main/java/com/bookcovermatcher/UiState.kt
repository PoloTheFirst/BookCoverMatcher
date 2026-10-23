package com.bookcovermatcher

import com.bookcovermatcher.core.Config
import com.bookcovermatcher.core.autoscan.ScanBadge
import com.bookcovermatcher.core.autoscan.ScanTick
import com.bookcovermatcher.core.search.MatchLevel
import com.bookcovermatcher.core.wishlist.ItemKind
import com.bookcovermatcher.core.wishlist.SessionKind
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

/** The green "Session added to …" bar. [recordId] is the session id, or null once undone. */
class PromptUi(val id: Long, val recordId: String?, val text: String)

// ---------------------------------------------------------------------------------------------
// Phase 2 navigation
// ---------------------------------------------------------------------------------------------

/** Bottom-navigation destinations. Persistent data never depends on which tab is selected. */
enum class AppTab { HOME, SEARCH, WISHLIST, MORE }

/** The two lists of the Wishlist destination. Bookmarks is a folder, not a separate screen stack. */
enum class WishlistSection { FOLDERS, BOOKMARKS }

/**
 * The three lists of the Search destination, kept apart on purpose:
 * `WORKBOOK` is the offline comparison with the spreadsheet (comparison output, no per-row
 * bookmark/remove controls), `ONLINE` is the catalogue search, `BOOKMARKS` shows the results of the
 * current search that are already in the Bookmarks collection. One mixed list would let an online
 * record look like a workbook match.
 */
enum class SearchResultsTab { WORKBOOK, ONLINE, BOOKMARKS }

/** What a confirmation dialog is about to remove. One dialog, four honest labels. */
enum class DeleteKind { FOLDER, SESSION, BOOKMARK, MEMBERSHIP }

/** What the shared folder picker is about to do; the copy and the write differ accordingly. */
enum class PickerMode {
    /** Individual results/bookmarks join a folder as direct items. */
    ADD_ITEMS,

    /** One offline matching operation is saved as a session inside the chosen folder. */
    SAVE_SESSION,

    /** The whole result profile (offline top-5 + online results) becomes one session. */
    SAVE_RESULT_PROFILE,
}

/**
 * One saved item, ready to draw. Cell/percent are workbook-only and stay null for online and
 * manual items. [kind] lets a row know whether it is comparison output (no per-item actions).
 */
class SavedItemUi(
    val id: String,
    val title: String,
    val url: String?,
    val domain: String?,
    val provider: String?,
    val meta: String,
    val kind: ItemKind,
    val kindLabel: String,
    val bookmarked: Boolean,
    val thumb: File?,
    val addedText: String,
    val cell: String?,
    val percentText: String?,
) {
    val comparisonOnly: Boolean get() = kind == ItemKind.WORKBOOK_MATCH
}

/** A folder card: a user folder or the reserved `Bookmarks` card. Counts come from the same read. */
class FolderUi(
    val id: String,
    val name: String,
    val sessionCount: Int,
    val itemCount: Int,
    val cover: File?,
    val reserved: Boolean,
)

/** One session row inside a folder. */
class SessionUi(
    val id: String,
    val title: String,
    val kind: SessionKind,
    val kindLabel: String,
    val meta: String,
    val query: String?,
    val scanThumb: File?,
    val resultCount: Int,
    val bookmarkedCount: Int,
)

/** A folder opened: its sessions plus the items saved directly into it (bookmarks, adds). */
data class FolderDetailUi(
    val id: String,
    val name: String,
    val sessions: List<SessionUi>,
    val directItems: List<SavedItemUi>,
)

/**
 * One session opened. [comparisonOnly] is true for offline matching sessions: their rows are the
 * comparison result and deliberately have no bookmark/add/remove controls.
 */
data class SessionDetailUi(
    val id: String,
    val folderId: String?,
    val title: String,
    val kindLabel: String,
    val meta: String,
    val query: String?,
    val items: List<SavedItemUi>,
    val comparisonOnly: Boolean,
)

/** One folder in the picker. [alreadyContains] is shown instead of silently skipping. */
class PickerFolderUi(val id: String, val name: String, val itemCount: Int, val alreadyContains: Boolean)

/** The shared folder picker used by per-item adds, bulk adds and saving an offline session. */
class PickerUi(
    val mode: PickerMode,
    val headline: String,
    val itemCount: Int,
    val folders: List<PickerFolderUi>,
    val defaultFolderId: String?,
) {
    val needsFolder: Boolean get() = folders.isEmpty()
}

/** [collectionId] is set for a membership removal (an item leaving one folder). */
class PendingDeleteUi(
    val kind: DeleteKind,
    val id: String,
    val title: String,
    val collectionId: String? = null,
)

/** Renaming a folder or a session. [title] keeps the dialog honest about what is being renamed. */
class RenameUi(val recordId: String, val currentName: String, val title: String = "Rename folder")
/** State of the hand-typed bookmark form. [error] is a message the form shows verbatim. */
class CustomBookmarkUi(val error: String? = null)


data class UiState(
    val model: ModelUi = ModelUi.Checking,

    // camera
    val cameraWanted: Boolean = false,
    val cameraLive: Boolean = false,
    val cameraRestart: Int = 0,
    val cameraBadge: String = ScanBadge.TAP_TO_START,
    val autoScan: Boolean = false,
    val scanTick: ScanTick? = null,

    // offline controls: workbook, range, captured cover, matching
    val workbook: WorkbookUi? = null,
    val workbookLoading: Boolean = false,
    val rangeText: String = Config.DEFAULT_RANGE,
    val target: TargetUi? = null,
    /** Display name of the selected image file, e.g. `book_cover.jpg`. Null for a camera capture. */
    val imageName: String? = null,
    val busy: BusyUi? = null,

    // offline comparison results (Search > Workbook tab)
    val results: ResultsUi = ResultsUi.Initial,

    // online results (Search > Online tab); the offline workflow above never reads it
    val online: OnlineSearchUi = OnlineSearchUi(),

    // Phase 2 navigation
    val tab: AppTab = AppTab.HOME,
    val wishlistSection: WishlistSection = WishlistSection.FOLDERS,

    /** Which Search list is open. Navigation state only — it never decides what is persisted. */
    val searchTab: SearchResultsTab = SearchResultsTab.WORKBOOK,

    // wishlist: folders, their sessions and the reserved Bookmarks folder, from one store read
    val folders: List<FolderUi> = emptyList(),
    val unfiledSessions: List<SessionUi> = emptyList(),
    val bookmarks: List<SavedItemUi> = emptyList(),
    val folderDetail: FolderDetailUi? = null,
    val sessionDetail: SessionDetailUi? = null,
    val pendingDelete: PendingDeleteUi? = null,
    val picker: PickerUi? = null,
    val customBookmark: CustomBookmarkUi? = null,
    val renaming: RenameUi? = null,

    /** Multi-selection mode (bookmarks, folders, sessions, saved items, session results). */
    val selection: SelectionUi? = null,
    /** A Copy/Move batch waiting for its target folder. */
    val batchTarget: BatchTargetUi? = null,
    /** A destructive batch waiting for confirmation. */
    val batchDelete: BatchDeleteUi? = null,

    /** Fingerprints of saved items, so a result card can show its bookmark/wishlist state. */
    val bookmarkedKeys: Set<String> = emptySet(),
    val wishlistedKeys: Set<String> = emptySet(),

    // transient
    val prompt: PromptUi? = null,
    val toast: ToastUi? = null,
) {
    val isBusy: Boolean get() = busy != null

    val offlineMatches: List<ResultUi>
        get() = (results as? ResultsUi.Ranked)?.items.orEmpty()
}
