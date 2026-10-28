package com.bookcovermatcher.core.wishlist

/**
 * The "Name this folder" flow: opening the dialog reserves nothing, and the folder is created **once**,
 * only when the user confirms a non-blank name.
 *
 * The repository call is injected, so the state machine is testable without Android and the ViewModel
 * keeps a single creation path (`begin` → `confirm`/`cancel` → `create`).
 */
class FolderCreation(private val create: suspend (String) -> Unit) {

    /** True while a dialog is waiting for confirmation. */
    var pending: Boolean = false
        private set

    private var busy = false

    /** Opens the dialog. Re-opening while it is already open does nothing. */
    fun begin() {
        if (!pending && !busy) pending = true
    }

    /** Cancel / dismiss / navigate away: no folder is created and nothing is left behind. */
    fun cancel() {
        pending = false
    }

    /**
     * Confirms the typed name.
     *
     * Returns false (and keeps the dialog open) for a blank name or a repeated tap while a creation is
     * running; returns true when [create] ran exactly once and the dialog may close.
     */
    suspend fun confirm(raw: String): Boolean {
        if (!pending || busy) return false
        val name = raw.trim()
        if (name.isEmpty()) return false
        busy = true
        return try {
            create(name)
            pending = false
            true
        } finally {
            busy = false
        }
    }
}
