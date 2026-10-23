package com.bookcovermatcher

/**
 * The rules behind multi-selection, kept out of Compose and out of the ViewModel so they can be
 * unit-tested as plain Kotlin.
 *
 * Selection is *navigation state*: it is never persisted, never written to `records.json`, and it
 * never changes what an item is. [reconcile] is the important one — after any delete, move or
 * refresh, ids that no longer exist are dropped, so an action can never target an invisible row.
 */
object SelectionLogic {

    /** What one selection can contain. Only the matching kind may be selected at a time. */
    fun eligible(kind: BatchKind, candidates: Collection<String>): List<String> =
        candidates.filter { it.isNotBlank() }.distinct()

    /** Toggles one row. Unknown/blank ids are ignored rather than stored. */
    fun toggle(ids: Set<String>, id: String): Set<String> {
        if (id.isBlank()) return ids
        return if (id in ids) ids - id else ids + id
    }

    /** Selects exactly what is visible: no hidden row can be acted on. */
    fun selectAll(visible: Collection<String>): Set<String> =
        visible.filter { it.isNotBlank() }.toSet()

    fun clear(): Set<String> = emptySet()

    /** Keeps only ids that still exist in the list being shown; empty means "nothing left". */
    fun reconcile(ids: Set<String>, visible: Collection<String>): Set<String> {
        val alive = visible.toSet()
        return ids.filter { it in alive }.toSet()
    }

    /**
     * Move is only offered where the model has a meaningful destination: a folder has no parent in
     * this model, and a bookmark *is* a `Bookmarks` membership, so moving it would un-bookmark it.
     */
    fun canMove(kind: BatchKind): Boolean = when (kind) {
        BatchKind.FOLDERS, BatchKind.BOOKMARKS -> false
        BatchKind.SESSIONS, BatchKind.ITEMS, BatchKind.SESSION_ITEMS -> true
    }

    /** Copy and delete are meaningful for every kind the UI offers. */
    fun canCopy(kind: BatchKind): Boolean = kind in BatchKind.values()
    fun canDelete(kind: BatchKind): Boolean = kind in BatchKind.values()

    /** "bookmark(s)", "folder(s)", ... - one spelling for dialogs, toasts and buttons. */
    fun noun(kind: BatchKind): String = when (kind) {
        BatchKind.BOOKMARKS -> "bookmark(s)"
        BatchKind.FOLDERS -> "folder(s)"
        BatchKind.SESSIONS -> "session(s)"
        BatchKind.ITEMS -> "item(s)"
        BatchKind.SESSION_ITEMS -> "result(s)"
    }

    /** What the selection panel says under the count. */
    fun hint(kind: BatchKind): String = when (kind) {
        BatchKind.BOOKMARKS -> "Tap bookmarks to select them. Copy keeps the bookmark; Remove deletes only the bookmark."
        BatchKind.FOLDERS -> "Tap folders to select them. Copy duplicates a folder with its sessions; Delete removes the folder."
        BatchKind.SESSIONS -> "Tap sessions to select them. Copy keeps the results; Move changes the folder; Delete removes the session."
        BatchKind.ITEMS -> "Tap saved items to select them. Move changes the folder; Delete removes only this folder's membership."
        BatchKind.SESSION_ITEMS -> "Tap results to select them. Move takes them out of this session into a folder."
    }
}
