package com.bookcovermatcher.core.wishlist

import java.io.File
import java.io.IOException
import java.util.UUID

/** One v1 record, kept only so an old `records.json` can still be read and migrated verbatim. */
class WishlistMatch(
    val cell: String,
    val cosine: Float,
    val percent: Float,
    val source: String,
    val thumbName: String?,
)

/** One v1 record: a scan plus its ranked workbook matches. It becomes one [SearchSession]. */
class WishlistRecord(
    val id: String,
    val name: String,
    val createdAtMillis: Long,
    val scanThumbName: String?,
    val matches: List<WishlistMatch>,
)

/**
 * The persistent store: one `records.json` plus one JPEG per thumbnail, inside [root].
 *
 * **Version 3** is the folder → session → results model:
 * ```
 * folders[]      the user's folders plus the reserved Bookmarks folder
 * sessions[]     one per matching/search operation, belonging to a folder (or unfiled)
 * items[]        every saved thing, shared by fingerprint
 * entries[]      which results a session holds, in order
 * memberships[]  which single items are directly inside a folder (bookmarks, individual adds)
 * legacy[]       the v1 records, read once and never dropped
 * ```
 * A scan writes one session inside the folder the user picked. It never creates a folder and never
 * turns workbook matches into individually removable rows.
 *
 * v1 and v2 files are migrated on read and rewritten as v3 after the old payload was parsed
 * successfully; a corrupt or future-version file is renamed `records.json.corrupt`, never
 * overwritten. Threading: not thread-safe by itself; the app calls it from a single-threaded
 * dispatcher. Every write is a temp file plus a rename.
 */
class WishlistRepository(
    private val root: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val newItemId: () -> String = { "itm_" + uuid(16) },
    private val newFolderId: () -> String = { "col_" + uuid(12) },
    private val newSessionId: () -> String = { "ses_" + uuid(16) },
) {
    private val recordsFile = File(root, "records.json")
    private val thumbDir = File(root, "thumbs")

    fun thumbFile(name: String): File = File(thumbDir, File(name).name)

    // =========================================================================================
    // Reading
    // =========================================================================================

    /** Everything the UI shows, in one consistent read. Guarantees the reserved Bookmarks folder. */
    fun library(): Library {
        val state = readState()
        val before = state.folders.any { it.reserved == ReservedCollection.BOOKMARKS }
        ensureReservedIn(state)
        if (!before) save(state)
        return Library(
            folders = state.folders.toList(),
            sessions = state.sessions.toList(),
            items = state.items.toList(),
            entries = state.entries.toList(),
            memberships = state.memberships.toList(),
        )
    }

    /** The v1 view: records kept verbatim in `legacy`, oldest first. Used by migration tests. */
    fun load(): List<WishlistRecord> = readState().legacy.toList()

    /** Creates `Bookmarks` when it is missing, never twice, and persists it. Idempotent. */
    fun ensureReserved(): WishlistFolder {
        val state = readState()
        val existing = state.folders.firstOrNull { it.reserved == ReservedCollection.BOOKMARKS }
        if (existing != null) return existing
        val created = ensureReservedIn(state)
        save(state)
        return created
    }

    // =========================================================================================
    // Folders
    // =========================================================================================

    /** A new user-named folder. A blank name keeps the `#N` spirit of the v1 default naming. */
    fun createFolder(name: String): WishlistFolder {
        val state = readState()
        ensureReservedIn(state)
        val id = uniqueFolderId(state)
        val default = "#" + (state.folders.count { it.kind == CollectionKind.PROFILE } + 1)
        val folder = WishlistFolder(id, name.trim().ifEmpty { default }, CollectionKind.PROFILE, clock())
        state.folders.add(folder)
        save(state)
        return folder
    }

    /** Only ordinary folders can be renamed; the reserved collection is not the user's to rename. */
    fun renameFolder(id: String, name: String): Boolean {
        val state = readState()
        val folder = state.folders.firstOrNull { it.id == id } ?: return false
        if (folder.isReserved) return false
        val trimmed = name.trim().ifEmpty { folder.name }
        replaceFolder(state, WishlistFolder(folder.id, trimmed, folder.kind, folder.createdAtMillis, folder.reserved))
        save(state)
        return true
    }

    /**
     * Deletes a folder, its sessions and their entries. Items that are bookmarked or in another
     * folder remain there; nothing else is deleted.
     */
    fun deleteFolder(id: String): Boolean {
        val state = readState()
        val folder = state.folders.firstOrNull { it.id == id } ?: return false
        if (folder.isReserved) return false
        val doomed = state.sessions.filter { it.folderId == id }
        state.folders.removeAll { it.id == id }
        state.memberships.removeAll { it.collectionId == id }
        state.sessions.removeAll { it.folderId == id }
        state.entries.removeAll { entry -> doomed.any { it.id == entry.sessionId } }
        prune(state)
        save(state)
        doomed.forEach { removeScanThumb(it) }
        return true
    }

    // =========================================================================================
    // Sessions — the record of one matching/search operation
    // =========================================================================================

    /**
     * Creates one session inside [SessionDraft.folderId] and fills it with [SessionDraft.results].
     *
     * Workbook results stay comparison output: they exist as session entries, not as independently
     * removable rows. The same item may appear in several sessions (the same cell scanned twice);
     * each session keeps its own entry and order.
     */
    fun saveSession(draft: SessionDraft): AddReport {
        val state = readState()
        val folder = state.folders.firstOrNull { it.id == draft.folderId }
            ?: throw InvalidItemException("Choose a wishlist folder first.")
        if (folder.isReserved) {
            throw InvalidItemException("Bookmarks cannot hold a session. Choose an ordinary folder.")
        }
        if (draft.results.isEmpty()) throw InvalidItemException("There is nothing to save yet.")

        val now = clock()
        val sessionId = uniqueSessionId(state)
        val session = SearchSession(
            id = sessionId,
            folderId = folder.id,
            kind = draft.kind,
            title = draft.title.trim().ifEmpty { defaultSessionTitle(state, folder.id, draft.kind) },
            query = draft.query?.trim()?.takeIf { it.isNotEmpty() },
            createdAtMillis = now,
            scanThumbName = draft.scanThumbJpeg?.let { writeThumb("${sessionId}_scan.jpg", it) },
        )
        state.sessions.add(session)

        var added = 0
        var alreadyThere = 0
        val ids = ArrayList<String>(draft.results.size)
        val seen = HashSet<String>()
        draft.results.forEachIndexed { index, raw ->
            val title = validateDraft(raw)
            if (!seen.add(raw.fingerprint)) {
                alreadyThere++
                return@forEachIndexed
            }
            val item = state.items.firstOrNull { it.fingerprint == raw.fingerprint }
                ?: materialise(raw, title).also { state.items.add(it) }
            state.entries.add(SessionEntry(session.id, item.id, index, now))
            ids.add(item.id)
            added++
        }
        if (added == 0) {
            // every draft was a duplicate of another in the same request: do not keep an empty session
            state.sessions.removeAll { it.id == session.id }
            session.scanThumbName?.let { thumbFile(it).delete() }
            throw InvalidItemException("Those results are already in this session.")
        }
        save(state)
        return AddReport(folder.id, session.id, added, alreadyThere, ids)
    }

    /** Deletes one session and its entries; items used elsewhere survive. */
    fun deleteSession(id: String): Boolean {
        val state = readState()
        val session = state.sessions.firstOrNull { it.id == id } ?: return false
        state.sessions.removeAll { it.id == id }
        state.entries.removeAll { it.sessionId == id }
        prune(state)
        save(state)
        removeScanThumb(session)
        return true
    }

    /** Moves a session to another ordinary folder. */
    fun moveSession(sessionId: String, folderId: String): Boolean {
        val state = readState()
        val index = state.sessions.indexOfFirst { it.id == sessionId }
        if (index < 0) return false
        val folder = state.folders.firstOrNull { it.id == folderId } ?: return false
        if (folder.isReserved) return false
        val session = state.sessions[index]
        state.sessions[index] = SearchSession(
            session.id, folder.id, session.kind, session.title, session.query,
            session.createdAtMillis, session.scanThumbName,
        )
        save(state)
        return true
    }

    fun renameSession(id: String, name: String): Boolean {
        val state = readState()
        val index = state.sessions.indexOfFirst { it.id == id }
        if (index < 0) return false
        val session = state.sessions[index]
        val trimmed = name.trim().ifEmpty { session.title }
        state.sessions[index] = SearchSession(
            session.id, session.folderId, session.kind, trimmed, session.query,
            session.createdAtMillis, session.scanThumbName,
        )
        save(state)
        return true
    }


    // =========================================================================================
    // Direct item adds (individual results, saved bookmarks joining a folder)
    // =========================================================================================

    /** Adds every draft directly to [folderId]; duplicates are skipped, never added twice. */
    fun addDrafts(drafts: List<ItemDraft>, folderId: String): AddReport {
        val state = readState()
        val folder = state.folders.firstOrNull { it.id == folderId }
            ?: throw InvalidItemException("Unknown wishlist folder.")
        if (folder.isReserved) throw InvalidItemException("Bookmarks are not a wishlist folder.")
        val report = addToState(state, drafts, folder)
        save(state)
        return report
    }

    /** Adds an item that already exists (e.g. a saved bookmark) to another folder. */
    fun addExistingItemToFolder(itemId: String, folderId: String): Boolean {
        val state = readState()
        val item = state.items.firstOrNull { it.id == itemId } ?: return false
        // Workbook comparison rows only ever exist inside their session; never promote one into a
        // direct folder item.
        if (item.kind == ItemKind.WORKBOOK_MATCH) return false
        val folder = state.folders.firstOrNull { it.id == folderId } ?: return false
        if (folder.isReserved) return false
        if (state.memberships.any { it.itemId == itemId && it.collectionId == folderId }) return false
        state.memberships.add(Membership(itemId, folderId, clock()))
        save(state)
        return true
    }

    /** Removes one direct membership; the item survives while any other reference remains. */
    fun removeMembership(itemId: String, folderId: String): Boolean {
        val state = readState()
        val gone = state.memberships.firstOrNull { it.itemId == itemId && it.collectionId == folderId } ?: return false
        state.memberships.remove(gone)
        prune(state)
        save(state)
        return true
    }

    // =========================================================================================
    // Bookmarks — the reserved folder, independent of every wishlist folder
    // =========================================================================================

    /** Saves one item into the reserved `Bookmarks` folder only. Repeat calls are idempotent. */
    fun bookmark(draft: ItemDraft): AddReport = bookmarkAll(listOf(draft))

    /**
     * `Bookmark all results`: every draft into `Bookmarks`, in **one** write.
     *
     * Idempotent, creates no folder, touches no wishlist membership, and refuses workbook matches:
     * offline comparison rows have no bookmark action by design.
     */
    fun bookmarkAll(drafts: List<ItemDraft>): AddReport {
        val state = readState()
        val reserved = ensureReservedIn(state)
        val report = addToState(state, drafts, reserved)
        save(state)
        return report
    }

    /** Removing a bookmark never touches wishlist membership: it only leaves `Bookmarks`. */
    fun removeBookmark(itemId: String): Boolean {
        val state = readState()
        val reserved = state.folders.firstOrNull { it.reserved == ReservedCollection.BOOKMARKS } ?: return false
        val gone = state.memberships.firstOrNull { it.itemId == itemId && it.collectionId == reserved.id } ?: return false
        state.memberships.remove(gone)
        prune(state)
        save(state)
        return true
    }

    /**
     * Bookmarks an **existing** saved item: the item keeps its id, thumbnail, provider metadata and
     * fingerprint, and simply gains the `Bookmarks` membership.
     *
     * This is what a result inside a result profile needs. The profile entry is a `SessionEntry` and the
     * bookmark is a `Membership`; both point at the same item, so bookmarking never duplicates the work,
     * never removes it from the profile, and un-bookmarking never deletes the profile entry.
     *
     * Workbook comparison rows are refused, exactly as in [addDrafts]: they are comparison output, not
     * independently bookmarkable records.
     *
     * @return true when a membership was added; false when the item is unknown, a comparison row, or
     * already bookmarked (no duplicate membership is ever created).
     */
    fun bookmarkExisting(itemId: String): Boolean {
        val state = readState()
        val item = state.items.firstOrNull { it.id == itemId } ?: return false
        if (item.kind == ItemKind.WORKBOOK_MATCH) return false
        val reserved = ensureReservedIn(state)
        if (state.memberships.any { it.itemId == itemId && it.collectionId == reserved.id }) return false
        state.memberships.add(Membership(itemId, reserved.id, clock()))
        save(state)
        return true
    }


    // =========================================================================================
    // Batch operations (multi-selection)
    //
    // Every operation below reads the store once, applies all changes in memory and writes **once**,
    // so a batch is all-or-nothing at the file level. Bookmark membership and wishlist membership
    // stay independent: a copy adds a membership, a move removes only the membership in scope, and a
    // delete removes only the membership the screen owns. Nothing here can delete an item that is
    // still referenced somewhere else - `prune` drops an item only when its last reference is gone.
    // =========================================================================================

    /** Copies [itemIds] into [targetFolderId] as direct items; every other membership is untouched. */
    fun copyItemsToFolder(itemIds: List<String>, targetFolderId: String): BatchReport {
        val state = readState()
        val target = state.folders.firstOrNull { it.id == targetFolderId }
            ?: return failed(itemIds, "That folder no longer exists.")
        if (target.isReserved) return failed(itemIds, "Bookmarks cannot take a copy.")
        var changed = 0
        var skipped = 0
        for (id in itemIds.distinct()) {
            val item = state.items.firstOrNull { it.id == id }
            when {
                item == null -> skipped++
                // Comparison output lives inside its session; it is never promoted to a folder item.
                item.kind == ItemKind.WORKBOOK_MATCH -> skipped++
                state.memberships.any { it.itemId == id && it.collectionId == target.id } -> skipped++
                else -> {
                    state.memberships.add(Membership(id, target.id, clock()))
                    changed++
                }
            }
        }
        return commit(state, BatchReport(itemIds.size, changed, skipped, 0))
    }

    /**
     * Moves [itemIds] out of [sourceFolderId] and into [targetFolderId].
     *
     * Only the membership of the source folder moves; a bookmark or a membership of a third folder is
     * preserved. A session inside the source folder is not edited: those results belong to that
     * session's record, so an item reachable only through a session is skipped (the UI says so).
     */
    fun moveItemsToFolder(itemIds: List<String>, sourceFolderId: String, targetFolderId: String): BatchReport {
        val state = readState()
        val source = state.folders.firstOrNull { it.id == sourceFolderId }
            ?: return failed(itemIds, "That folder no longer exists.")
        val target = state.folders.firstOrNull { it.id == targetFolderId }
            ?: return failed(itemIds, "That folder no longer exists.")
        if (target.isReserved) return failed(itemIds, "Bookmarks cannot receive a move.")
        if (source.id == target.id) return failed(itemIds, "The items are already in that folder.")
        var changed = 0
        var skipped = 0
        for (id in itemIds.distinct()) {
            val membership = state.memberships.firstOrNull { it.itemId == id && it.collectionId == source.id }
            if (membership == null) {
                skipped++
                continue
            }
            state.memberships.remove(membership)
            if (state.memberships.none { it.itemId == id && it.collectionId == target.id }) {
                state.memberships.add(Membership(id, target.id, clock()))
            }
            changed++
        }
        prune(state)
        return commit(state, BatchReport(itemIds.size, changed, skipped, 0))
    }

    /** Removes [itemIds] from one folder only: their bookmark and other folder memberships survive. */
    fun removeItemsFromFolder(itemIds: List<String>, folderId: String): BatchReport {
        val state = readState()
        val gone = state.memberships.filter { it.collectionId == folderId && it.itemId in itemIds }.toSet()
        if (gone.isEmpty()) return BatchReport(itemIds.size, 0, itemIds.size, 0)
        state.memberships.removeAll(gone)
        prune(state)
        return commit(state, BatchReport(itemIds.size, gone.size, itemIds.size - gone.size, 0))
    }

    /** Removes these items from `Bookmarks` only; wishlist memberships and sessions are untouched. */
    fun deleteBookmarks(itemIds: List<String>): BatchReport {
        val state = readState()
        val bookmarks = state.folders.firstOrNull { it.reserved == ReservedCollection.BOOKMARKS }
            ?: return failed(itemIds, "There is no Bookmarks collection.")
        val gone = state.memberships.filter { it.collectionId == bookmarks.id && it.itemId in itemIds }.toSet()
        if (gone.isEmpty()) return BatchReport(itemIds.size, 0, itemIds.size, 0)
        state.memberships.removeAll(gone)
        prune(state)
        return commit(state, BatchReport(itemIds.size, gone.size, itemIds.size - gone.size, 0))
    }

    /** Copies the selected results of [sourceSessionId] into [targetFolderId] as direct items. */
    fun copySessionEntries(
        itemIds: List<String>,
        sourceSessionId: String,
        targetFolderId: String,
    ): BatchReport = transferSessionEntries(itemIds, sourceSessionId, targetFolderId, move = false)

    /**
     * Moves the selected results **out** of [sourceSessionId] into [targetFolderId].
     *
     * The session keeps its other results and its order; the moved result becomes a direct item of
     * the target folder, so it is still reachable and still bookmarked if it was.
     */
    fun moveSessionEntries(
        itemIds: List<String>,
        sourceSessionId: String,
        targetFolderId: String,
    ): BatchReport = transferSessionEntries(itemIds, sourceSessionId, targetFolderId, move = true)

    private fun transferSessionEntries(
        itemIds: List<String>,
        sourceSessionId: String,
        targetFolderId: String,
        move: Boolean,
    ): BatchReport {
        val state = readState()
        val session = state.sessions.firstOrNull { it.id == sourceSessionId }
            ?: return failed(itemIds, "That session no longer exists.")
        val target = state.folders.firstOrNull { it.id == targetFolderId }
            ?: return failed(itemIds, "That folder no longer exists.")
        if (target.isReserved) return failed(itemIds, "Bookmarks cannot receive results.")
        val wanted = itemIds.toSet()
        val entries = state.entries.filter { it.sessionId == session.id && it.itemId in wanted }
        if (entries.isEmpty()) return BatchReport(itemIds.size, 0, itemIds.size, 0)
        var changed = 0
        var skipped = itemIds.size - entries.map { it.itemId }.toSet().size
        for (entry in entries.toList()) {
            val item = state.items.firstOrNull { it.id == entry.itemId }
            if (item == null || item.kind == ItemKind.WORKBOOK_MATCH) {
                // Comparison rows exist only inside their session and are never promoted.
                skipped++
                continue
            }
            if (state.memberships.none { it.itemId == item.id && it.collectionId == target.id }) {
                state.memberships.add(Membership(item.id, target.id, clock()))
            }
            if (move) state.entries.remove(entry)
            changed++
        }
        prune(state)
        return commit(state, BatchReport(itemIds.size, changed, skipped, 0))
    }

    /** Removes selected results from one session only; bookmarks and folder memberships survive. */
    fun removeSessionEntries(itemIds: List<String>, sessionId: String): BatchReport {
        val state = readState()
        val session = state.sessions.firstOrNull { it.id == sessionId }
            ?: return failed(itemIds, "That session no longer exists.")
        val wanted = itemIds.toSet()
        val entries = state.entries.filter { it.sessionId == session.id && it.itemId in wanted }
        if (entries.isEmpty()) return BatchReport(itemIds.size, 0, itemIds.size, 0)
        var skipped = itemIds.size - entries.map { it.itemId }.toSet().size
        var removed = 0
        for (entry in entries.toList()) {
            val item = state.items.firstOrNull { it.id == entry.itemId }
            // A comparison row is part of the session's own record: never removed one by one.
            if (item != null && item.kind == ItemKind.WORKBOOK_MATCH) {
                skipped++
                continue
            }
            state.entries.remove(entry)
            removed++
        }
        prune(state)
        return commit(state, BatchReport(itemIds.size, removed, skipped, 0))
    }

    /** Moves whole sessions (profiles) to another ordinary folder; their results move with them. */
    fun moveSessionsToFolder(sessionIds: List<String>, targetFolderId: String): BatchReport {
        val state = readState()
        val target = state.folders.firstOrNull { it.id == targetFolderId }
            ?: return failed(sessionIds, "That folder no longer exists.")
        if (target.isReserved) return failed(sessionIds, "Bookmarks cannot hold a session.")
        var changed = 0
        var skipped = 0
        for (id in sessionIds.distinct()) {
            val index = state.sessions.indexOfFirst { it.id == id }
            if (index < 0) {
                skipped++
                continue
            }
            val session = state.sessions[index]
            if (session.folderId == target.id) {
                skipped++
                continue
            }
            state.sessions[index] = withFolder(session, target.id)
            changed++
        }
        return commit(state, BatchReport(sessionIds.size, changed, skipped, 0))
    }

    /**
     * Copies whole sessions into [targetFolderId].
     *
     * The copy is a new session with its own id, holding the **same items** in the same order - no
     * item is duplicated, so a bookmark or another folder's membership stays shared. The captured
     * scan thumbnail is not copied: that file belongs to the original session and would be deleted
     * with it.
     */
    fun copySessionsToFolder(sessionIds: List<String>, targetFolderId: String): BatchReport {
        val state = readState()
        val target = state.folders.firstOrNull { it.id == targetFolderId }
            ?: return failed(sessionIds, "That folder no longer exists.")
        if (target.isReserved) return failed(sessionIds, "Bookmarks cannot hold a session.")
        var changed = 0
        var skipped = 0
        for (id in sessionIds.distinct()) {
            val session = state.sessions.firstOrNull { it.id == id }
            if (session == null) {
                skipped++
                continue
            }
            val now = clock()
            val copy = SearchSession(
                id = uniqueSessionId(state),
                folderId = target.id,
                kind = session.kind,
                title = copiedTitle(session.title),
                query = session.query,
                createdAtMillis = now,
                scanThumbName = null,
            )
            state.sessions.add(copy)
            state.entries
                .filter { it.sessionId == session.id }
                .sortedBy { it.position }
                .forEach { entry -> state.entries.add(SessionEntry(copy.id, entry.itemId, entry.position, now)) }
            changed++
        }
        return commit(state, BatchReport(sessionIds.size, changed, skipped, 0))
    }

    /** Deletes whole sessions and their entries; items used elsewhere survive. */
    fun deleteSessions(sessionIds: List<String>): BatchReport {
        val state = readState()
        val doomed = state.sessions.filter { it.id in sessionIds }
        if (doomed.isEmpty()) return BatchReport(sessionIds.size, 0, sessionIds.size, 0)
        val ids = doomed.map { it.id }.toSet()
        state.sessions.removeAll(doomed)
        state.entries.removeAll { it.sessionId in ids }
        prune(state)
        return commit(state, BatchReport(sessionIds.size, doomed.size, sessionIds.size - doomed.size, 0), doomed)
    }

    /**
     * Copies whole folders: a new folder per source, holding copies of its sessions (same items) and
     * its direct memberships. The reserved collection is never copied.
     */
    fun copyFolders(folderIds: List<String>): BatchReport {
        val state = readState()
        var changed = 0
        var skipped = 0
        for (id in folderIds.distinct()) {
            val source = state.folders.firstOrNull { it.id == id }
            if (source == null || source.isReserved) {
                skipped++
                continue
            }
            val now = clock()
            val copy = WishlistFolder(
                id = uniqueFolderId(state),
                name = uniqueFolderName(state, copiedTitle(source.name)),
                kind = source.kind,
                createdAtMillis = now,
            )
            state.folders.add(copy)
            state.memberships
                .filter { it.collectionId == source.id }
                .forEach { m -> state.memberships.add(Membership(m.itemId, copy.id, now)) }
            state.sessions
                .filter { it.folderId == source.id }
                .sortedBy { it.createdAtMillis }
                .forEach { session ->
                    val sessionCopy = SearchSession(
                        id = uniqueSessionId(state),
                        folderId = copy.id,
                        kind = session.kind,
                        title = session.title,
                        query = session.query,
                        createdAtMillis = session.createdAtMillis,
                        scanThumbName = null,
                    )
                    state.sessions.add(sessionCopy)
                    state.entries
                        .filter { it.sessionId == session.id }
                        .sortedBy { it.position }
                        .forEach { entry ->
                            state.entries.add(SessionEntry(sessionCopy.id, entry.itemId, entry.position, now))
                        }
                }
            changed++
        }
        return commit(state, BatchReport(folderIds.size, changed, skipped, 0))
    }

    /**
     * Deletes whole folders, their sessions and their direct memberships, exactly like the
     * single-folder action. Bookmarked items and items in other folders survive; so does the
     * reserved collection itself.
     */
    fun deleteFolders(folderIds: List<String>): BatchReport {
        val state = readState()
        val doomed = state.folders.filter { it.id in folderIds && !it.isReserved }
        if (doomed.isEmpty()) return BatchReport(folderIds.size, 0, folderIds.size, 0)
        val ids = doomed.map { it.id }.toSet()
        val sessions = state.sessions.filter { it.folderId in ids }
        val sessionIds = sessions.map { it.id }.toSet()
        state.folders.removeAll(doomed)
        state.memberships.removeAll { it.collectionId in ids }
        state.sessions.removeAll(sessions)
        state.entries.removeAll { it.sessionId in sessionIds }
        prune(state)
        val skipped = folderIds.size - doomed.size
        return commit(state, BatchReport(folderIds.size, doomed.size, skipped, 0), sessions)
    }

    private fun withFolder(session: SearchSession, folderId: String): SearchSession = SearchSession(
        session.id, folderId, session.kind, session.title, session.query,
        session.createdAtMillis, session.scanThumbName,
    )

    /** `"Name"` -> `"Name (copy)"`; copied names stay readable instead of stacking suffixes. */
    private fun copiedTitle(name: String): String = name.trim().ifEmpty { "Untitled" } + " (copy)"

    private fun uniqueFolderName(state: State, wanted: String): String {
        if (state.folders.none { it.name.equals(wanted, ignoreCase = true) }) return wanted
        var n = 2
        while (state.folders.any { it.name.equals("$wanted $n", ignoreCase = true) } && n < 100) n++
        return "$wanted $n"
    }

    private fun failed(ids: List<String>, message: String): BatchReport =
        BatchReport(ids.size, 0, 0, ids.size, message)

    /** Writes a batch once. A failed write reports failure and leaves the stored file untouched. */
    private fun commit(
        state: State,
        report: BatchReport,
        removedSessions: List<SearchSession> = emptyList(),
    ): BatchReport {
        if (report.changed == 0) return report
        return try {
            save(state)
            // Only after the new file is safely in place may the old scan thumbnails go.
            removedSessions.forEach { removeScanThumb(it) }
            report
        } catch (e: Exception) {
            BatchReport(
                requested = report.requested,
                changed = 0,
                skipped = report.skipped,
                failed = report.requested,
                message = "Could not save the change: " + (e.message ?: e.toString()),
            )
        }
    }

    // =========================================================================================
    // Internals
    // =========================================================================================

    private class State(
        val folders: MutableList<WishlistFolder> = ArrayList(),
        val sessions: MutableList<SearchSession> = ArrayList(),
        val items: MutableList<SavedItem> = ArrayList(),
        val entries: MutableList<SessionEntry> = ArrayList(),
        val memberships: MutableList<Membership> = ArrayList(),
        val legacy: MutableList<WishlistRecord> = ArrayList(),
    )

    private class Decoded(val state: State, val migrated: Boolean)

    private fun readState(): State {
        if (!recordsFile.isFile) return State()
        val text = try {
            recordsFile.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            quarantine()
            return State()
        }
        val decoded = try {
            decodeState(text)
        } catch (e: Exception) {
            // unreadable, or written by a future version of the app: keep it, never overwrite it
            quarantine()
            return State()
        }
        // a v1/v2 file is rewritten only after its payload was read successfully
        if (decoded.migrated) save(decoded.state)
        return decoded.state
    }

    private fun quarantine() {
        val target = File(root, "records.json.corrupt")
        val destination = if (target.exists()) File(root, "records.json.corrupt." + System.currentTimeMillis()) else target
        recordsFile.renameTo(destination)
    }

    private fun save(state: State) {
        root.mkdirs()
        val tmp = File(root, "records.json.tmp")
        tmp.writeText(encodeState(state), Charsets.UTF_8)
        if (recordsFile.exists() && !recordsFile.delete()) throw IOException("cannot replace ${recordsFile.name}")
        if (!tmp.renameTo(recordsFile)) throw IOException("cannot write ${recordsFile.name}")
    }

    private fun writeThumb(name: String, bytes: ByteArray): String {
        thumbDir.mkdirs()
        thumbFile(name).writeBytes(bytes)
        return name
    }

    private fun removeScanThumb(session: SearchSession) {
        session.scanThumbName?.let { thumbFile(it).delete() }
    }

    private fun ensureReservedIn(state: State): WishlistFolder {
        state.folders.firstOrNull { it.reserved == ReservedCollection.BOOKMARKS }?.let { return it }
        val id = if (state.folders.none { it.id == RESERVED_ID }) RESERVED_ID else uniqueFolderId(state)
        val folder = WishlistFolder(id, RESERVED_NAME, CollectionKind.RESERVED, clock(), ReservedCollection.BOOKMARKS)
        state.folders.add(0, folder)
        return folder
    }

    private fun uniqueFolderId(state: State): String {
        var id = newFolderId()
        var guard = 0
        while (state.folders.any { it.id == id } && guard++ < 100) id = newFolderId()
        return id
    }

    private fun uniqueSessionId(state: State): String {
        var id = newSessionId()
        var guard = 0
        while (state.sessions.any { it.id == id } && guard++ < 100) id = newSessionId()
        return id
    }

    private fun replaceFolder(state: State, folder: WishlistFolder) {
        val index = state.folders.indexOfFirst { it.id == folder.id }
        if (index >= 0) state.folders[index] = folder else state.folders.add(folder)
    }

    private fun defaultSessionTitle(state: State, folderId: String, kind: SessionKind): String {
        val n = state.sessions.count { it.folderId == folderId } + 1
        return when (kind) {
            SessionKind.OFFLINE_MATCH -> "Scan #$n"
            SessionKind.ONLINE_SEARCH -> "Search #$n"
            SessionKind.RESULT_PROFILE -> "Result profile #$n"
        }
    }

    /** An item exists only while something references it; its thumbnail goes with it. */
    private fun prune(state: State) {
        val referenced = HashSet<String>(state.memberships.size + state.entries.size)
        state.memberships.forEach { referenced.add(it.itemId) }
        state.entries.forEach { referenced.add(it.itemId) }
        val gone = state.items.filter { it.id !in referenced }
        if (gone.isEmpty()) return
        state.items.removeAll(gone)
        gone.forEach { item -> item.thumbName?.let { thumbFile(it).delete() } }
    }


    // =========================================================================================
    // Draft handling
    // =========================================================================================

    private fun validateDraft(draft: ItemDraft): String {
        val title = draft.title.trim()
        if (title.isEmpty()) throw InvalidItemException("An item without a title cannot be saved.")
        if (draft.kind == ItemKind.MANUAL_BOOKMARK) {
            BookmarkRules.validate(title, draft.url.orEmpty())?.let { throw InvalidItemException(it) }
        }
        return title
    }

    private fun addToState(
        state: State,
        drafts: List<ItemDraft>,
        folder: WishlistFolder,
    ): AddReport {
        var added = 0
        var alreadyThere = 0
        val ids = ArrayList<String>(drafts.size)
        val seen = HashSet<String>()
        for (raw in drafts) {
            val title = validateDraft(raw)
            // Workbook matches only ever exist inside their session; they are not bookmarkable and
            // not independently removable.
            if (raw.kind == ItemKind.WORKBOOK_MATCH) {
                throw InvalidItemException("Workbook matches are saved as part of a session, not as individual items.")
            }
            if (!seen.add(raw.fingerprint)) {
                alreadyThere++
                continue
            }
            val item = state.items.firstOrNull { it.fingerprint == raw.fingerprint }
                ?: materialise(raw, title).also { state.items.add(it) }
            ids.add(item.id)
            val exists = state.memberships.any { it.itemId == item.id && it.collectionId == folder.id }
            if (exists) {
                alreadyThere++
            } else {
                state.memberships.add(Membership(item.id, folder.id, clock()))
                added++
            }
        }
        return AddReport(folder.id, null, added, alreadyThere, ids)
    }

    private fun materialise(draft: ItemDraft, title: String): SavedItem {
        val id = newItemId()
        val link = draft.url?.trim()?.takeIf { it.isNotEmpty() }
        return SavedItem(
            id = id,
            kind = draft.kind,
            title = title,
            url = link,
            sourceDomain = BookmarkRules.domainOf(link.orEmpty()),
            provider = draft.provider?.trim()?.takeIf { it.isNotEmpty() },
            description = draft.description?.trim()?.takeIf { it.isNotEmpty() },
            authors = draft.authors.map { it.trim() }.filter { it.isNotEmpty() },
            identifier = draft.identifier?.trim()?.takeIf { it.isNotEmpty() },
            price = draft.price?.trim()?.takeIf { it.isNotEmpty() },
            availability = draft.availability?.trim()?.takeIf { it.isNotEmpty() },
            matchType = draft.resolvedMatchType,
            // a typed field is not a verified one; only a real provider response may set this
            matchTypeVerified = draft.matchTypeVerified && draft.resolvedMatchType != MatchType.MANUAL,
            cell = draft.cell?.trim()?.takeIf { it.isNotEmpty() },
            cosine = draft.cosine,
            percent = draft.percent,
            thumbName = draft.thumbJpeg?.let { bytes -> writeThumb("$id.jpg", bytes) },
            createdAtMillis = clock(),
            fingerprint = draft.fingerprint,
        )
    }


    companion object {
        /** 1 = flat records, 2 = collections/items/memberships (no sessions), 3 = folders/sessions. */
        const val VERSION = 3
        const val RESERVED_ID = "col_bookmarks"
        const val RESERVED_NAME = "Bookmarks"

        private fun uuid(length: Int): String = UUID.randomUUID().toString().replace("-", "").take(length)

        /**
         * The v1 payload, kept because it is what an old file and the `legacy` array look like.
         * New data is never written in this shape.
         */
        fun encode(list: List<WishlistRecord>): String = MiniJson.write(
            mapOf("version" to 1, "records" to list.map { recordMap(it) }),
        )

        /** Reads either shape and returns the v1 view (the `legacy` array). */
        @Suppress("UNCHECKED_CAST")
        fun decode(text: String): List<WishlistRecord> {
            val root = MiniJson.parse(text) as? Map<String, Any?> ?: throw MiniJson.JsonException("not an object")
            val records = (root["legacy"] as? List<Any?>) ?: (root["records"] as? List<Any?>) ?: return emptyList()
            return records.mapNotNull { decodeRecord(it) }
        }

        private fun recordMap(r: WishlistRecord): Map<String, Any?> = mapOf(
            "id" to r.id,
            "name" to r.name,
            "createdAt" to r.createdAtMillis,
            "scanThumb" to r.scanThumbName,
            "matches" to r.matches.map { m ->
                mapOf(
                    "cell" to m.cell,
                    "cosine" to m.cosine.toDouble(),
                    "percent" to m.percent.toDouble(),
                    "source" to m.source,
                    "thumb" to m.thumbName,
                )
            },
        )

        private fun encodeState(state: State): String = MiniJson.write(
            mapOf(
                "version" to VERSION,
                "folders" to state.folders.map { f ->
                    mapOf(
                        "id" to f.id,
                        "name" to f.name,
                        "kind" to f.kind.name,
                        "reserved" to f.reserved?.name,
                        "createdAt" to f.createdAtMillis,
                    )
                },
                "sessions" to state.sessions.map { s ->
                    mapOf(
                        "id" to s.id,
                        "folderId" to s.folderId,
                        "kind" to s.kind.name,
                        "title" to s.title,
                        "query" to s.query,
                        "createdAt" to s.createdAtMillis,
                        "scanThumb" to s.scanThumbName,
                    )
                },
                "items" to state.items.map { i ->
                    mapOf(
                        "id" to i.id,
                        "kind" to i.kind.name,
                        "title" to i.title,
                        "url" to i.url,
                        "domain" to i.sourceDomain,
                        "provider" to i.provider,
                        "description" to i.description,
                        "authors" to i.authors,
                        "identifier" to i.identifier,
                        "price" to i.price,
                        "availability" to i.availability,
                        "matchType" to i.matchType.name,
                        "matchTypeVerified" to i.matchTypeVerified,
                        "cell" to i.cell,
                        "cosine" to i.cosine?.toDouble(),
                        "percent" to i.percent?.toDouble(),
                        "thumb" to i.thumbName,
                        "createdAt" to i.createdAtMillis,
                        "fingerprint" to i.fingerprint,
                    )
                },
                "entries" to state.entries.map { e ->
                    mapOf(
                        "sessionId" to e.sessionId,
                        "itemId" to e.itemId,
                        "position" to e.position,
                        "addedAt" to e.addedAtMillis,
                    )
                },
                "memberships" to state.memberships.map { m ->
                    mapOf(
                        "itemId" to m.itemId,
                        "folderId" to m.collectionId,
                        "addedAt" to m.addedAtMillis,
                    )
                },
                "legacy" to state.legacy.takeIf { it.isNotEmpty() }?.map { recordMap(it) },
            ),
        )


        @Suppress("UNCHECKED_CAST")
        private fun decodeState(text: String): Decoded {
            val root = MiniJson.parse(text) as? Map<String, Any?> ?: throw MiniJson.JsonException("not an object")
            val version = when (val raw = root["version"]) {
                is Double -> raw.toInt()
                is Int -> raw
                null -> 1
                else -> throw MiniJson.JsonException("unknown version")
            }
            return when {
                version <= 1 -> Decoded(migrateV1(root), true)
                version == 2 -> Decoded(migrateV2(root), true)
                version == VERSION -> Decoded(decodeV3(root), false)
                else -> throw MiniJson.JsonException("future version $version")
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun decodeV3(root: Map<String, Any?>): State {
            val state = State()
            (root["legacy"] as? List<Any?>).orEmpty().forEach { decodeRecord(it)?.let(state.legacy::add) }
            (root["folders"] as? List<Any?>).orEmpty().forEach { raw ->
                decodeFolder(raw)?.let(state.folders::add)
            }
            (root["sessions"] as? List<Any?>).orEmpty().forEach { raw ->
                decodeSession(raw)?.let(state.sessions::add)
            }
            (root["items"] as? List<Any?>).orEmpty().forEach { raw ->
                decodeItem(raw)?.let(state.items::add)
            }
            (root["entries"] as? List<Any?>).orEmpty().forEach { raw ->
                val map = raw as? Map<String, Any?> ?: return@forEach
                val sessionId = map["sessionId"] as? String ?: return@forEach
                val itemId = map["itemId"] as? String ?: return@forEach
                if (state.sessions.none { it.id == sessionId } || state.items.none { it.id == itemId }) return@forEach
                state.entries.add(
                    SessionEntry(sessionId, itemId, longOf(map["position"]).toInt(), longOf(map["addedAt"])),
                )
            }
            (root["memberships"] as? List<Any?>).orEmpty().forEach { raw ->
                val map = raw as? Map<String, Any?> ?: return@forEach
                val itemId = map["itemId"] as? String ?: return@forEach
                val folderId = (map["folderId"] as? String) ?: (map["collectionId"] as? String) ?: return@forEach
                if (state.folders.none { it.id == folderId }) return@forEach
                state.memberships.add(Membership(itemId, folderId, longOf(map["addedAt"])))
            }
            return state
        }


        @Suppress("UNCHECKED_CAST")
        private fun decodeFolder(raw: Any?): WishlistFolder? {
            val map = raw as? Map<String, Any?> ?: return null
            val id = map["id"] as? String ?: return null
            val reserved = enumOr<ReservedCollection>(map["reserved"], null)
            val kind = enumOr(map["kind"], if (reserved != null) CollectionKind.RESERVED else CollectionKind.PROFILE)
                ?: CollectionKind.PROFILE
            return WishlistFolder(
                id = id,
                name = map["name"] as? String ?: "",
                kind = kind,
                createdAtMillis = longOf(map["createdAt"]),
                reserved = reserved,
            )
        }

        private fun decodeSession(raw: Any?): SearchSession? {
            val map = raw as? Map<String, Any?> ?: return null
            val id = map["id"] as? String ?: return null
            return SearchSession(
                id = id,
                folderId = map["folderId"] as? String,
                kind = enumOr(map["kind"], SessionKind.OFFLINE_MATCH) ?: SessionKind.OFFLINE_MATCH,
                title = map["title"] as? String ?: "Session",
                query = map["query"] as? String,
                createdAtMillis = longOf(map["createdAt"]),
                scanThumbName = map["scanThumb"] as? String,
            )
        }

        @Suppress("UNCHECKED_CAST")
        private fun decodeItem(raw: Any?): SavedItem? {
            val map = raw as? Map<String, Any?> ?: return null
            val id = map["id"] as? String ?: return null
            val title = map["title"] as? String ?: return null
            val authors = (map["authors"] as? List<Any?>).orEmpty().mapNotNull { it as? String }
            return SavedItem(
                id = id,
                kind = enumOr(map["kind"], ItemKind.ONLINE_RESULT) ?: ItemKind.ONLINE_RESULT,
                title = title,
                url = map["url"] as? String,
                sourceDomain = map["domain"] as? String,
                provider = map["provider"] as? String,
                description = map["description"] as? String,
                authors = authors,
                identifier = map["identifier"] as? String,
                price = map["price"] as? String,
                availability = map["availability"] as? String,
                matchType = enumOr(map["matchType"], MatchType.TEXT_QUERY) ?: MatchType.TEXT_QUERY,
                matchTypeVerified = map["matchTypeVerified"] as? Boolean ?: false,
                cell = map["cell"] as? String,
                cosine = floatOf(map["cosine"]),
                percent = floatOf(map["percent"]),
                thumbName = map["thumb"] as? String,
                createdAtMillis = longOf(map["createdAt"]),
                fingerprint = map["fingerprint"] as? String ?: "",
            )
        }

        @Suppress("UNCHECKED_CAST")
        private fun decodeRecord(raw: Any?): WishlistRecord? {
            val r = raw as? Map<String, Any?> ?: return null
            val id = r["id"] as? String ?: return null
            val matches = (r["matches"] as? List<Any?>).orEmpty().mapNotNull { mr ->
                val m = mr as? Map<String, Any?> ?: return@mapNotNull null
                WishlistMatch(
                    cell = m["cell"] as? String ?: return@mapNotNull null,
                    cosine = floatOf(m["cosine"]) ?: 0f,
                    percent = floatOf(m["percent"]) ?: 0f,
                    source = m["source"] as? String ?: "",
                    thumbName = m["thumb"] as? String,
                )
            }
            return WishlistRecord(
                id = id,
                name = r["name"] as? String ?: "",
                createdAtMillis = longOf(r["createdAt"]),
                scanThumbName = r["scanThumb"] as? String,
                matches = matches,
            )
        }


        // =====================================================================================
        // Migrations — old data is read, converted, then kept; it is never discarded
        // =====================================================================================

        /**
         * v1: a flat list of "records", each a scan with its matches. A record is a session, not a
         * folder, so it is migrated to an **unfiled** session. No folder is invented.
         */
        @Suppress("UNCHECKED_CAST")
        private fun migrateV1(root: Map<String, Any?>): State {
            val state = State()
            (root["records"] as? List<Any?>).orEmpty().forEach { raw ->
                decodeRecord(raw)?.let(state.legacy::add)
            }
            state.legacy.forEachIndexed { recordIndex, record ->
                if (state.sessions.any { it.id == record.id }) return@forEachIndexed
                val sessionId = record.id
                state.sessions.add(
                    SearchSession(
                        id = sessionId,
                        folderId = null,
                        kind = SessionKind.OFFLINE_MATCH,
                        title = record.name.ifBlank { "#${recordIndex + 1}" },
                        query = null,
                        createdAtMillis = record.createdAtMillis,
                        scanThumbName = record.scanThumbName,
                    ),
                )
                record.matches.forEachIndexed { index, match ->
                    val itemId = "itm_${record.id}_$index"
                    if (state.items.none { it.id == itemId }) {
                        state.items.add(
                            SavedItem(
                                id = itemId,
                                kind = ItemKind.WORKBOOK_MATCH,
                                title = match.cell,
                                url = null,
                                sourceDomain = null,
                                provider = null,
                                description = match.source,
                                authors = emptyList(),
                                identifier = null,
                                price = null,
                                availability = null,
                                matchType = MatchType.WORKBOOK,
                                matchTypeVerified = true,
                                cell = match.cell,
                                cosine = match.cosine,
                                percent = match.percent,
                                thumbName = match.thumbName,
                                createdAtMillis = record.createdAtMillis,
                                fingerprint = "wb:" + record.id + ":" + match.cell.trim().uppercase() + ":" + index,
                            ),
                        )
                    }
                    state.entries.add(SessionEntry(sessionId, itemId, index, record.createdAtMillis))
                }
            }
            return state
        }


        /**
         * v2: folders + items + memberships, no sessions. Two shapes need repair:
         *
         *  1. The v2 "save this scan" compat path created one profile per scan whose items were the
         *     five workbook matches — a folder pretending to be a session. That folder becomes a
         *     real folder holding one session; its workbook memberships move into that session.
         *  2. Any remaining workbook items that were added directly to a folder are grouped into an
         *     `Imported scan` session, so they regain comparison-only semantics.
         *
         * Online and manual items keep their direct folder memberships.
         */
        @Suppress("UNCHECKED_CAST")
        private fun migrateV2(root: Map<String, Any?>): State {
            val state = State()
            (root["collections"] as? List<Any?>).orEmpty().forEach { decodeFolder(it)?.let(state.folders::add) }
            (root["items"] as? List<Any?>).orEmpty().forEach { decodeItem(it)?.let(state.items::add) }
            (root["memberships"] as? List<Any?>).orEmpty().forEach { raw ->
                val map = raw as? Map<String, Any?> ?: return@forEach
                val itemId = map["itemId"] as? String ?: return@forEach
                val folderId = (map["collectionId"] as? String) ?: (map["folderId"] as? String) ?: return@forEach
                state.memberships.add(Membership(itemId, folderId, longOf(map["addedAt"])))
            }
            (root["legacy"] as? List<Any?>).orEmpty().forEach { decodeRecord(it)?.let(state.legacy::add) }

            // 1. every legacy record becomes one session in the folder v2 created for it
            state.legacy.forEach { record ->
                if (state.sessions.any { it.id == record.id }) return@forEach
                val folder = state.folders.firstOrNull { it.id == record.id }
                val sessionId = record.id
                state.sessions.add(
                    SearchSession(
                        id = sessionId,
                        folderId = folder?.id,
                        kind = SessionKind.OFFLINE_MATCH,
                        title = record.name.ifBlank { "Scan" },
                        query = null,
                        createdAtMillis = record.createdAtMillis,
                        scanThumbName = record.scanThumbName,
                    ),
                )
                val moved = state.memberships
                    .filter { m -> m.collectionId == folder?.id }
                    .mapNotNull { m -> state.items.firstOrNull { it.id == m.itemId }?.let { m to it } }
                    .filter { (_, item) -> item.kind == ItemKind.WORKBOOK_MATCH }
                    .sortedBy { (m, _) -> m.addedAtMillis }
                moved.forEachIndexed { index, (membership, item) ->
                    state.entries.add(SessionEntry(sessionId, item.id, index, record.createdAtMillis))
                    state.memberships.remove(membership)
                }
            }

            // 2. any workbook items still stored as direct folder items become one session per folder
            val leftovers = state.memberships.mapNotNull { m ->
                state.items.firstOrNull { it.id == m.itemId && it.kind == ItemKind.WORKBOOK_MATCH }?.let { m to it }
            }
            leftovers.groupBy { (m, _) -> m.collectionId }.forEach { (folderId, list) ->
                val folder = state.folders.firstOrNull { it.id == folderId } ?: return@forEach
                var sessionId = "ses_" + uuid(16)
                while (state.sessions.any { it.id == sessionId }) sessionId = "ses_" + uuid(16)
                val createdAt = list.minOf { (m, _) -> m.addedAtMillis }
                state.sessions.add(
                    SearchSession(
                        sessionId, folder.id, SessionKind.OFFLINE_MATCH, "Imported scan", null, createdAt, null,
                    ),
                )
                list.sortedBy { (m, _) -> m.addedAtMillis }.forEachIndexed { index, (membership, item) ->
                    state.entries.add(SessionEntry(sessionId, item.id, index, membership.addedAtMillis))
                    state.memberships.remove(membership)
                }
            }
            return state
        }


        private fun longOf(value: Any?): Long = when (value) {
            is Double -> if (value.isFinite()) value.toLong() else 0L
            is Int -> value.toLong()
            is Long -> value
            else -> 0L
        }

        private fun floatOf(value: Any?): Float? = when (value) {
            is Double -> if (value.isFinite()) value.toFloat() else null
            is Int -> value.toFloat()
            is Float -> value.takeIf { it.isFinite() }
            else -> null
        }

        private inline fun <reified T : Enum<T>> enumOr(raw: Any?, fallback: T?): T? {
            val name = raw as? String ?: return fallback
            return enumValues<T>().firstOrNull { it.name == name } ?: fallback
        }
    }
}
