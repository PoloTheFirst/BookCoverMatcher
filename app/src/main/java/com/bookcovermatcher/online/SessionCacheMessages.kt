// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.online

/**
 * The one sentence "Clear session cache" shows.
 *
 * It is a named constant rather than an inline literal because the em dash in it was carrying a
 * double-encoded UTF-8 byte sequence all the way to the toast: the file had been read as Windows-1252
 * and written back as UTF-8, so U+2014 arrived as three characters. Naming it makes the exact text
 * unit-testable, and a test also fails if any of that damage comes back.
 *
 * The wording states what the action really does: it drops the in-memory/session results only. Saved
 * bookmarks, wishlist folders and items, result profiles and their images are stored elsewhere and are
 * not touched (`OnlineSearchController.clearSession` invalidates the session cache and nothing else).
 */
object SessionCacheMessages {

    /** Exactly what the user must see after a cache clear, em dash and all. */
    const val CLEARED = "Session cache cleared \u2014 saved bookmarks and wishlist items are untouched."
}
