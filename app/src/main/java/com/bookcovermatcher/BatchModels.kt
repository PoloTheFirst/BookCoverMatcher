package com.bookcovermatcher

/** What a multi-selection is made of. Actions are only ever offered for the matching kind. */
enum class BatchKind { FOLDERS, SESSIONS, ITEMS, BOOKMARKS, SESSION_ITEMS }

/** What a batch can do. `MOVE` is only offered where the model has a meaningful place to move to. */
enum class BatchAction { COPY, MOVE, DELETE }

/**
 * A multi-selection over one list. [containerId] is the folder (items/sessions) or the session
 * (results) the selection was started in, so a batch never has to guess its scope.
 */
data class SelectionUi(
    val kind: BatchKind,
    val ids: Set<String> = emptySet(),
    val containerId: String? = null,
) {
    val count: Int get() = ids.size
    val active: Boolean get() = ids.isNotEmpty()
}

/** The folder picker a Copy/Move batch opens; nothing is written until a folder is chosen. */
class BatchTargetUi(
    val action: BatchAction,
    val kind: BatchKind,
    val ids: List<String>,
    val containerId: String?,
    val title: String,
    val folders: List<PickerFolderUi>,
    val defaultFolderId: String?,
) {
    val count: Int get() = ids.size
}

/** A destructive batch waiting for confirmation. [detail] says exactly what will be removed. */
class BatchDeleteUi(
    val kind: BatchKind,
    val ids: List<String>,
    val containerId: String?,
    val title: String,
    val detail: String,
) {
    val count: Int get() = ids.size
}
