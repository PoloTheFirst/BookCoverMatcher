package com.bookcovermatcher.core.wishlist

/**
 * The saved-data model, deliberately organised the way the workflow is:
 *
 *  * [WishlistFolder] — a persistent collection the **user** names and creates ("genres" are only
 *    names the user chose; nothing here hardcodes a genre). The reserved `Bookmarks` folder is the
 *    one special case.
 *  * [SearchSession] — the record of **one** matching/search operation. A session belongs to a
 *    folder (or is unfiled after migration), and it is what holds the results of that operation.
 *  * [SavedItem] — one thing: a workbook match, an online result or a hand-typed bookmark.
 *  * [SessionEntry] — "this result belongs to that session", in order.
 *  * [Membership] — "this single item is directly inside that folder" (an individually saved
 *    bookmark/result). A folder therefore holds sessions **and** directly saved items.
 *
 * The lists live in one file ([WishlistRepository]) and are written in one atomic write. A scan
 * therefore adds a session, never a folder, and never a pile of independently removable rows:
 * workbook results are comparison output and only exist inside their session.
 */

enum class CollectionKind { RESERVED, PROFILE }

/** The reserved folders the store guarantees. There is exactly one today. */
enum class ReservedCollection { BOOKMARKS }

/** Where a saved item came from. Workbook items keep their cell/cosine/percent; the others never get one. */
enum class ItemKind { WORKBOOK_MATCH, ONLINE_RESULT, MANUAL_BOOKMARK }

/**
 * How the item was found.
 *
 * [TEXT_QUERY] is the honest label for the catalogue search: the cover was OCR'd and the text was
 * searched. [VISUAL] exists for a future provider that really performs image similarity; nothing
 * sets it today. [WORKBOOK] means the offline OpenCLIP comparison, [MANUAL] a typed bookmark.
 */
enum class MatchType { WORKBOOK, TEXT_QUERY, VISUAL, MANUAL }

/** What operation produced a session. The UI labels sessions from this, never from guesses. */
enum class SessionKind {
    /** The offline OpenCLIP comparison ("Link & Find Top 5"). Results are comparison-only. */
    OFFLINE_MATCH,

    /** Results discovered by the online catalogue search. */
    ONLINE_SEARCH,

    /** An explicit bulk "add the whole result profile" action (offline matches + online results). */
    RESULT_PROFILE,
}

/** A folder of sessions and directly saved items. [reserved] is null for ordinary folders. */
class WishlistFolder(
    val id: String,
    val name: String,
    val kind: CollectionKind,
    val createdAtMillis: Long,
    val reserved: ReservedCollection? = null,
) {
    val isReserved: Boolean get() = kind == CollectionKind.RESERVED

    override fun toString(): String = "WishlistFolder($id, $name, $kind)"
}

/**
 * One matching/search operation.
 *
 * [folderId] is null only for unfiled sessions (legacy v1 records and sessions whose folder was
 * deleted). [query] is the text that was searched for online sessions and null for offline ones:
 * a workbook comparison has no search query and the app must not invent one.
 */
class SearchSession(
    val id: String,
    val folderId: String?,
    val kind: SessionKind,
    val title: String,
    val query: String?,
    val createdAtMillis: Long,
    /** JPEG of the captured cover, stored as a file name in `thumbs/`. Never a result thumbnail. */
    val scanThumbName: String?,
) {
    override fun toString(): String = "SearchSession($id, $title, $kind)"
}

/**
 * One persisted item.
 *
 * Every provider-specific field is nullable on purpose: Open Library/Google Books have a title,
 * URL, authors and an identifier but no price or stock; a manual bookmark has whatever the user
 * typed. A value that was never supplied stays null and the UI draws nothing (or `—`) for it — it
 * is never filled with a guess.
 *
 * [fingerprint] is the identity used for de-duplication (see [ItemIdentity]).
 */
class SavedItem(
    val id: String,
    val kind: ItemKind,
    val title: String,
    val url: String?,
    val sourceDomain: String?,
    val provider: String?,
    val description: String?,
    val authors: List<String>,
    /** The circle shown on the bookmark form; distinct from the artist rows. */
    val circle: String? = null,
    val identifier: String?,
    val price: String?,
    val availability: String?,
    val matchType: MatchType,
    /** False for anything the app did not verify against a provider (manual fields, until fetched). */
    val matchTypeVerified: Boolean,
    val cell: String?,
    val cosine: Float?,
    val percent: Float?,
    val thumbName: String?,
    val createdAtMillis: Long,
    val fingerprint: String,
) {
    override fun toString(): String = "SavedItem($id, $title, $kind)"
}

/** "This result belongs to that session at this position." */
class SessionEntry(
    val sessionId: String,
    val itemId: String,
    val position: Int,
    val addedAtMillis: Long,
)

/** "This individual item is directly inside that folder." */
class Membership(
    val itemId: String,
    val collectionId: String,
    val addedAtMillis: Long,
)

/** Everything the store holds, read in one pass so no two views can disagree. */
class Library(
    val folders: List<WishlistFolder>,
    val sessions: List<SearchSession>,
    val items: List<SavedItem>,
    val entries: List<SessionEntry>,
    val memberships: List<Membership>,
) {
    val bookmarksFolder: WishlistFolder?
        get() = folders.firstOrNull { it.reserved == ReservedCollection.BOOKMARKS }

    val profileFolders: List<WishlistFolder>
        get() = folders.filter { it.kind == CollectionKind.PROFILE }

    fun folder(id: String): WishlistFolder? = folders.firstOrNull { it.id == id }

    fun session(id: String): SearchSession? = sessions.firstOrNull { it.id == id }

    fun item(id: String): SavedItem? = items.firstOrNull { it.id == id }

    fun itemByFingerprint(fingerprint: String): SavedItem? = items.firstOrNull { it.fingerprint == fingerprint }

    /** Sessions of one folder, newest first — the order a folder screen shows them in. */
    fun sessionsIn(folderId: String): List<SearchSession> =
        sessions.filter { it.folderId == folderId }.sortedByDescending { it.createdAtMillis }

    /** Legacy records and sessions whose folder was deleted. Never silently discarded. */
    fun unfiledSessions(): List<SearchSession> =
        sessions.filter { it.folderId == null }.sortedByDescending { it.createdAtMillis }

    fun entriesIn(sessionId: String): List<SessionEntry> =
        entries.filter { it.sessionId == sessionId }.sortedBy { it.position }

    /**
     * The results of one session, **bookmarked results first** (the ordering the workflow asks
     * for), and otherwise in the order the operation produced them.
     */
    fun sessionItems(sessionId: String): List<SavedItem> {
        val ordered = entriesIn(sessionId).mapNotNull { entry -> items.firstOrNull { it.id == entry.itemId } }
        val bookmarked = ordered.filter { isBookmarked(it.id) }
        val plain = ordered.filterNot { isBookmarked(it.id) }
        return bookmarked + plain
    }

    /** Items saved directly into a folder (not through a session), bookmarked ones first. */
    fun directItems(folderId: String): List<SavedItem> {
        val ordered = memberships
            .filter { it.collectionId == folderId }
            .sortedBy { it.addedAtMillis }
            .mapNotNull { m -> items.firstOrNull { it.id == m.itemId } }
        val bookmarked = ordered.filter { isBookmarked(it.id) }
        val plain = ordered.filterNot { isBookmarked(it.id) }
        return bookmarked + plain
    }

    /** Everything a folder contains, for counts and covers: sessions' results plus direct items. */
    fun itemsInFolder(folderId: String): List<SavedItem> {
        val fromSessions = sessionsIn(folderId).flatMap { sessionItems(it.id) }
        return (fromSessions + directItems(folderId)).distinctBy { it.id }
    }

    fun sessionCount(folderId: String): Int = sessions.count { it.folderId == folderId }

    /** Number of results/items a folder holds. A five-result session is five, not a five-item cap. */
    fun itemCount(folderId: String): Int = itemsInFolder(folderId).size

    fun foldersOf(itemId: String): List<WishlistFolder> {
        val direct = memberships.filter { it.itemId == itemId }.map { it.collectionId }.toSet()
        val throughSessions = entries
            .filter { it.itemId == itemId }
            .mapNotNull { entry -> sessions.firstOrNull { it.id == entry.sessionId }?.folderId }
            .toSet()
        val ids = direct + throughSessions
        return folders.filter { it.id in ids }
    }

    fun isBookmarked(itemId: String): Boolean {
        val bookmarks = bookmarksFolder ?: return false
        return memberships.any { it.itemId == itemId && it.collectionId == bookmarks.id }
    }

    fun isWishlisted(itemId: String): Boolean = foldersOf(itemId).any { it.kind == CollectionKind.PROFILE }

    /** Items currently in the reserved `Bookmarks` folder, oldest first unless [descending]. */
    fun bookmarkedItems(descending: Boolean = false): List<SavedItem> {
        val bookmarks = bookmarksFolder ?: return emptyList()
        val ordered = memberships
            .filter { it.collectionId == bookmarks.id }
            .sortedBy { it.addedAtMillis }
            .mapNotNull { m -> items.firstOrNull { it.id == m.itemId } }
        return if (descending) ordered.reversed() else ordered
    }
}


/**
 * An item about to be saved. The thumbnail is still bytes: [WishlistRepository] writes it into
 * `thumbs/` when the item is created, and only when the item is created (a duplicate never rewrites
 * someone else's file).
 */
class ItemDraft(
    val kind: ItemKind,
    val title: String,
    val url: String? = null,
    val provider: String? = null,
    /** Stable provider key, e.g. `ol:OL27482W`. Wins over the URL when present. */
    val providerKey: String? = null,
    val identifier: String? = null,
    val description: String? = null,
    val authors: List<String> = emptyList(),
    /** The circle of a manual bookmark; stored separately from [authors]. */
    val circle: String? = null,
    val price: String? = null,
    val availability: String? = null,
    val matchType: MatchType? = null,
    val matchTypeVerified: Boolean = false,
    val cell: String? = null,
    val cosine: Float? = null,
    val percent: Float? = null,
    val thumbJpeg: ByteArray? = null,
) {
    val resolvedMatchType: MatchType
        get() = matchType ?: when (kind) {
            ItemKind.WORKBOOK_MATCH -> MatchType.WORKBOOK
            ItemKind.ONLINE_RESULT -> MatchType.TEXT_QUERY
            ItemKind.MANUAL_BOOKMARK -> MatchType.MANUAL
        }

    val fingerprint: String
        get() = ItemIdentity.fingerprintFor(providerKey = providerKey, url = url, workbookCell = cell, title = title)
}

/** Everything one "save a session" action needs, so the repository creates it in one write. */
class SessionDraft(
    val folderId: String,
    val kind: SessionKind,
    val title: String,
    val query: String? = null,
    val scanThumbJpeg: ByteArray? = null,
    val results: List<ItemDraft>,
)

/** What one add operation did, so the UI can report real numbers instead of guessing. */
class AddReport(
    val folderId: String,
    /** Set when the operation created a session; null for a direct folder add or a bookmark. */
    val sessionId: String? = null,
    /** Results/memberships that did not exist before. */
    val added: Int,
    /** Drafts the destination already contained (or that appeared twice in one request). */
    val alreadyThere: Int,
    /** Ids of every item the request touched, in input order. */
    val itemIds: List<String>,
)

