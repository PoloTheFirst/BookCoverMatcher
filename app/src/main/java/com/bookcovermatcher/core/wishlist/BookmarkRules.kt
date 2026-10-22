package com.bookcovermatcher.core.wishlist

/**
 * Validation for the hand-typed bookmark form.
 *
 * The app only ever opens `https` links ([com.bookcovermatcher.ui.AppScreen] refuses anything else),
 * so the store refuses to keep a link it could never open. Nothing is fetched, guessed or rewritten:
 * the URL is stored exactly as typed, minus surrounding whitespace.
 */
object BookmarkRules {

    const val ERROR_TITLE = "Enter a title."
    const val ERROR_URL_MISSING = "Enter a URL."
    const val ERROR_HTTPS = "Only https:// links can be saved — the app never opens anything else."
    const val ERROR_URL_SHAPE = "That does not look like a full URL, e.g. https://example.com/item."

    /** Null when the input is usable; otherwise a message the form can show as-is. */
    fun validate(title: String, url: String): String? {
        if (title.trim().isEmpty()) return ERROR_TITLE
        val link = url.trim()
        if (link.isEmpty()) return ERROR_URL_MISSING
        if (!link.startsWith("https://", ignoreCase = true)) return ERROR_HTTPS
        if (ItemIdentity.domainOf(link) == null) return ERROR_URL_SHAPE
        return null
    }

    /** The source domain shown on the card and in the bookmarks list; derived, never asked for. */
    fun domainOf(url: String): String? = ItemIdentity.domainOf(url)
}

/** Thrown by [WishlistRepository] when a caller tries to persist something invalid. */
class InvalidItemException(message: String) : IllegalArgumentException(message)
