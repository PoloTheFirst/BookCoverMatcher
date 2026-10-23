package com.bookcovermatcher.core.wishlist

/** What one batch operation actually did, so the UI can report real numbers instead of guessing. */
class BatchReport(
    /** How many ids the user selected. */
    val requested: Int,
    /** How many were really moved/copied/deleted. */
    val changed: Int,
    /** Valid ids the operation deliberately did nothing for (duplicates, comparison rows, ...). */
    val skipped: Int,
    /** Ids that could not be processed at all (unknown id, refused target, failed write). */
    val failed: Int,
    /** A short reason for the UI when something was refused or the write failed. */
    val message: String? = null,
) {
    val ok: Boolean get() = failed == 0
}
