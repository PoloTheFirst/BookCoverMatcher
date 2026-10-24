package com.bookcovermatcher.core

import com.bookcovermatcher.core.wishlist.ItemDraft
import com.bookcovermatcher.core.wishlist.ItemKind
import com.bookcovermatcher.core.wishlist.MatchType
import com.bookcovermatcher.core.wishlist.SessionDraft
import com.bookcovermatcher.core.wishlist.SessionKind
import com.bookcovermatcher.core.wishlist.WishlistRepository
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private fun tmpRename(): File = Files.createTempDirectory("rename").toFile()

private fun onlineDraftForRename(id: String) = ItemDraft(
    kind = ItemKind.ONLINE_RESULT,
    title = "Book $id",
    url = "https://openlibrary.org/works/$id",
    provider = "Open Library",
    providerKey = "ol:$id",
    identifier = id,
    matchType = MatchType.TEXT_QUERY,
    matchTypeVerified = true,
)

/**
 * Renaming a result profile, against the real repository and its real JSON file. The behaviour must
 * match the folder rename: same trim, same blank-name rule, duplicate names allowed, identity and
 * contents untouched.
 */
class ProfileRenameTest {

    private lateinit var root: File
    private lateinit var repo: WishlistRepository
    private lateinit var folder: String
    private lateinit var profile: String

    @Before fun setUp() {
        root = tmpRename()
        repo = WishlistRepository(root)
        repo.ensureReserved()
        folder = repo.createFolder("Shelf").id
        profile = newProfile("Search #1")
    }

    private fun newProfile(title: String): String = requireNotNull(
        repo.saveSession(
            SessionDraft(
                folderId = folder,
                kind = SessionKind.RESULT_PROFILE,
                title = title,
                query = "blue archive",
                results = listOf(onlineDraftForRename("p1"), onlineDraftForRename("p2")),
            ),
        ).sessionId,
    )

    @Test fun aResultProfileCanBeRenamed() {
        assertTrue(repo.renameSession(profile, "My profile"))

        val session = repo.library().session(profile)
        assertNotNull(session)
        assertEquals("My profile", session!!.title)
        assertEquals(folder, session.folderId)
        assertEquals(SessionKind.RESULT_PROFILE, session.kind)
        assertEquals("blue archive", session.query)
    }

    @Test fun renamingKeepsTheIdAndEveryContainedResult() {
        val before = repo.library()
        val itemIds = before.sessionItems(profile).map { it.id }
        val entries = before.entriesIn(profile).map { it.itemId to it.position }

        assertTrue(repo.renameSession(profile, "Renamed"))

        val after = repo.library()
        assertEquals(profile, after.session(profile)!!.id)
        assertEquals(itemIds, after.sessionItems(profile).map { it.id })
        assertEquals(entries, after.entriesIn(profile).map { it.itemId to it.position })
        assertEquals(2, after.items.size)
    }

    @Test fun aBlankNameKeepsTheCurrentOneLikeAFolder() {
        assertTrue(repo.renameSession(profile, "   "))
        assertEquals("Search #1", repo.library().session(profile)!!.title)
    }

    @Test fun theNameIsTrimmed() {
        assertTrue(repo.renameSession(profile, "  Trimmed  "))
        assertEquals("Trimmed", repo.library().session(profile)!!.title)
    }

    @Test fun duplicateNamesAreAllowedAndBothProfilesStayDistinct() {
        val second = newProfile("Second")

        assertTrue(repo.renameSession(profile, "Same"))
        assertTrue(repo.renameSession(second, "Same"))

        val sessions = repo.library().sessionsIn(folder)
        assertEquals(2, sessions.count { it.title == "Same" })
        assertEquals(setOf(profile, second), sessions.map { it.id }.toSet())
        assertEquals(2, repo.library().sessionItems(second).size)
    }

    @Test fun theNewNameSurvivesAReload() {
        assertTrue(repo.renameSession(profile, "Persisted"))

        val reloaded = WishlistRepository(root).library()
        assertEquals("Persisted", reloaded.session(profile)!!.title)
        assertEquals(2, reloaded.sessionItems(profile).size)
    }

    @Test fun renamingAnUnknownProfileChangesNothing() {
        val before = repo.library().sessions.map { it.id to it.title }

        assertFalse(repo.renameSession("ses_does_not_exist", "x"))

        assertEquals(before, repo.library().sessions.map { it.id to it.title })
    }

    @Test fun renamingAProfileLeavesBookmarksAndOtherProfilesAlone() {
        val other = newProfile("Other")
        repo.bookmarkAll(listOf(onlineDraftForRename("p1")))

        assertTrue(repo.renameSession(profile, "Renamed"))

        val library = repo.library()
        assertEquals(1, library.bookmarkedItems().size)
        assertEquals("Other", library.session(other)!!.title)
        assertEquals(2, library.sessionItems(other).size)
    }
}