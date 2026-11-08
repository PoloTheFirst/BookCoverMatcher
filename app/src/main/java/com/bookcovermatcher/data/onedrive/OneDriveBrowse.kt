// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.data.onedrive

import com.bookcovermatcher.core.remote.RemoteItem

/** One step of the OneDrive path. [id] is `null` for the root, which has no driveItem of its own. */
data class OneDriveCrumb(val id: String?, val name: String)

/**
 * What the browser is currently showing: where it is, and the page(s) it has already loaded.
 *
 * The lists hold [RemoteItem]s, so every row carries the **id** the Graph calls use and the **name** the
 * user reads, and nothing ever builds a request from a name. [nextPageToken] is the server's opaque
 * `@odata.nextLink`, passed back untouched — never assembled from a folder name.
 */
data class OneDriveBrowse(
    val path: List<OneDriveCrumb>,
    val folders: List<RemoteItem>,
    val files: List<RemoteItem>,
    val nextPageToken: String? = null,
) {
    /** The folder being listed; `null` is the drive root. */
    val folderId: String? get() = path.lastOrNull()?.id

    /** What the breadcrumb shows for the current folder. */
    val folderName: String get() = path.lastOrNull()?.name ?: ROOT_NAME

    val atRoot: Boolean get() = path.size <= 1
    val canGoUp: Boolean get() = !atRoot
    val hasMorePages: Boolean get() = nextPageToken != null
    val isEmpty: Boolean get() = folders.isEmpty() && files.isEmpty()

    /** Rows in display order: folders first, then workbooks, each in the order OneDrive sent them. */
    val rows: List<RemoteItem> get() = folders + files

    companion object {
        const val ROOT_NAME = "OneDrive"

        fun root(): OneDriveBrowse = OneDriveBrowse(listOf(OneDriveCrumb(null, ROOT_NAME)), emptyList(), emptyList())
    }
}

/**
 * The browser's navigation rules, kept pure so they can be tested without a screen or a network.
 *
 * Two rules matter:
 *
 *  * **identity, never names.** Going into a folder uses the folder's `id`; going up pops the path
 *    rather than re-deriving a parent from a path string (a name can contain `/`, a tenant's drive can
 *    be named anything).
 *  * **pages are appended, never replaced by accident.** OneDrive can repeat an item across page
 *    boundaries when the folder changes mid-listing, so an appended page is de-duplicated by id.
 */
object OneDriveNavigation {

    /** Enter [folder] (a row that must be a folder). */
    fun into(browse: OneDriveBrowse, folder: RemoteItem): OneDriveBrowse {
        require(folder.isFolder) { "only a folder can be entered" }
        return browse.copy(
            path = browse.path + OneDriveCrumb(folder.id, folder.name),
            folders = emptyList(),
            files = emptyList(),
            nextPageToken = null,
        )
    }

    /** Go back one folder. At the root this is a no-op rather than an error. */
    fun up(browse: OneDriveBrowse): OneDriveBrowse = if (browse.atRoot) {
        browse
    } else {
        browse.copy(
            path = browse.path.dropLast(1),
            folders = emptyList(),
            files = emptyList(),
            nextPageToken = null,
        )
    }

    /** The first page of the current folder: replaces whatever was shown. */
    fun withFirstPage(browse: OneDriveBrowse, folders: List<RemoteItem>, files: List<RemoteItem>, next: String?) =
        browse.copy(folders = folders, files = files, nextPageToken = next)

    /** A later page: appended to what is already shown, de-duplicated by id. */
    fun withNextPage(browse: OneDriveBrowse, folders: List<RemoteItem>, files: List<RemoteItem>, next: String?) =
        browse.copy(
            folders = appendDistinct(browse.folders, folders),
            files = appendDistinct(browse.files, files),
            nextPageToken = next,
        )

    private fun appendDistinct(existing: List<RemoteItem>, added: List<RemoteItem>): List<RemoteItem> {
        val seen = existing.mapTo(HashSet()) { it.id }
        return existing + added.filter { seen.add(it.id) }
    }
}
