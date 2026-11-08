// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.remote

import com.bookcovermatcher.data.onedrive.OneDriveBrowse
import com.bookcovermatcher.data.onedrive.OneDriveNavigation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OneDrive browser's navigation rules (v2.6.0-dev.1 Phase 4).
 *
 * Kept pure so they can be tested without a screen: an id decides where a tap goes, the path is a stack
 * rather than a re-derived string, and an appended page never duplicates a row.
 */
class OneDriveNavigationTest {

    private fun folder(id: String, name: String = "Folder $id") =
        RemoteItem(id = id, name = name, isFolder = true)

    private fun file(id: String, name: String = "book$id.xlsx", size: Long? = 10L) =
        RemoteItem(id = id, name = name, isFolder = false, sizeBytes = size)

    @Test fun aFreshBrowserStartsAtTheRoot() {
        val root = OneDriveBrowse.root()

        assertEquals("OneDrive", root.folderName)
        assertNull("the root has no driveItem id", root.folderId)
        assertTrue(root.atRoot)
        assertFalse(root.canGoUp)
        assertTrue(root.isEmpty)
        assertTrue(root.rows.isEmpty())
    }

    @Test fun enteringAFolderUsesItsIdAndPushesADisplayCrumb() {
        val root = OneDriveNavigation.withFirstPage(
            OneDriveBrowse.root(),
            folders = listOf(folder("F1", "Catalogue")),
            files = listOf(file("A1")),
            next = null,
        )

        val inside = OneDriveNavigation.into(root, root.folders.single())

        assertEquals("F1", inside.folderId)
        assertEquals("Catalogue", inside.folderName)
        assertEquals(listOf("OneDrive", "Catalogue"), inside.path.map { it.name })
        assertTrue(inside.canGoUp)
        assertTrue("the new folder starts empty and loads its own page", inside.isEmpty)
        assertNull(inside.nextPageToken)
    }

    @Test fun goingUpPopsThePathAndClearsThePage() {
        val root = OneDriveBrowse.root()
        val inside = OneDriveNavigation.into(OneDriveNavigation.into(root, folder("F1", "A")), folder("F2", "B"))
        assertEquals("F2", inside.folderId)

        val back = OneDriveNavigation.up(inside)

        assertEquals("F1", back.folderId)
        assertTrue(back.isEmpty)
        assertEquals(listOf("OneDrive", "A"), back.path.map { it.name })
    }

    @Test fun goingUpAtTheRootIsANoOpRatherThanAnError() {
        val root = OneDriveBrowse.root()

        assertEquals(root, OneDriveNavigation.up(root))
    }

    @Test fun foldersComeBeforeWorkbooksInTheRowOrder() {
        val page = OneDriveNavigation.withFirstPage(
            OneDriveBrowse.root(),
            folders = listOf(folder("F1"), folder("F2")),
            files = listOf(file("A1"), file("A2")),
            next = null,
        )

        assertEquals(listOf("F1", "F2", "A1", "A2"), page.rows.map { it.id })
    }

    @Test fun aFirstPageReplacesWhateverWasShown() {
        val browse = OneDriveNavigation.withFirstPage(
            OneDriveBrowse.root(),
            folders = emptyList(),
            files = listOf(file("OLD")),
            next = "https://graph.microsoft.com/v1.0/page2",
        )

        val replaced = OneDriveNavigation.withFirstPage(
            browse,
            folders = emptyList(),
            files = listOf(file("NEW")),
            next = null,
        )

        assertEquals(listOf("NEW"), replaced.files.map { it.id })
        assertNull(replaced.nextPageToken)
    }

    @Test fun anAppendedPageKeepsWhatIsAlreadyShown() {
        val first = OneDriveNavigation.withFirstPage(
            OneDriveBrowse.root(),
            folders = listOf(folder("F1")),
            files = listOf(file("A1")),
            next = "https://graph.microsoft.com/v1.0/page2",
        )

        val second = OneDriveNavigation.withNextPage(
            first,
            folders = listOf(folder("F2")),
            files = listOf(file("A2")),
            next = null,
        )

        assertEquals(listOf("F1", "F2"), second.folders.map { it.id })
        assertEquals(listOf("A1", "A2"), second.files.map { it.id })
        assertFalse(second.hasMorePages)
    }

    @Test fun aRepeatedRowOnALaterPageIsNotListedTwice() {
        // OneDrive can repeat an item across a page boundary when the folder changes mid-listing.
        val first = OneDriveNavigation.withFirstPage(
            OneDriveBrowse.root(),
            folders = emptyList(),
            files = listOf(file("A1"), file("A2")),
            next = "https://graph.microsoft.com/v1.0/page2",
        )

        val second = OneDriveNavigation.withNextPage(
            first,
            folders = emptyList(),
            files = listOf(file("A2"), file("A3")),
            next = null,
        )

        assertEquals(listOf("A1", "A2", "A3"), second.files.map { it.id })
    }

    @Test fun hasMorePagesFollowsTheTokenOnly() {
        val withToken = OneDriveNavigation.withFirstPage(
            OneDriveBrowse.root(),
            emptyList(),
            emptyList(),
            "https://graph.microsoft.com/v1.0/page2",
        )

        assertTrue(withToken.hasMorePages)
        assertTrue("a page with no rows can still have more pages", withToken.isEmpty)

        val finished = OneDriveNavigation.withNextPage(withToken, emptyList(), emptyList(), null)
        assertFalse(finished.hasMorePages)
    }

    @Test fun enteringTheSameFolderNameTwiceIsStillTwoPlaces() {
        // Two folders can share a name; the path holds ids, so they stay distinguishable.
        val root = OneDriveNavigation.withFirstPage(
            OneDriveBrowse.root(),
            folders = listOf(folder("F1", "Covers"), folder("F2", "Covers")),
            files = emptyList(),
            next = null,
        )

        val first = OneDriveNavigation.into(root, root.folders[0])

        assertEquals("F1", first.folderId)
        assertEquals(listOf("OneDrive", "Covers"), first.path.map { it.name })
        assertEquals("F1", first.path.last().id)
    }
}

