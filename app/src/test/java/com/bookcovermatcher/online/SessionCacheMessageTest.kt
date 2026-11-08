// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.online

import com.bookcovermatcher.core.wishlist.BookmarkForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the two pieces of literal text the v2.5.0 corrections were about.
 *
 * The cache-clear toast reached users with mojibake instead of an em dash: the Kotlin file had been
 * read as Windows-1252 and written back as UTF-8, so U+2014 (UTF-8 `E2 80 94`) arrived as three
 * characters. The first test pins the exact sentence, the second refuses any character of that
 * damage (written as escapes, so this file does not carry the damage it looks for), and the third
 * scans the packaged source set so the same re-encoding mistake cannot quietly return elsewhere.
 */
class SessionCacheMessageTest {

    @Test
    fun theConfirmationIsTheExactSentenceWithAnEmDash() {
        assertEquals(
            "Session cache cleared \u2014 saved bookmarks and wishlist items are untouched.",
            SessionCacheMessages.CLEARED,
        )
        assertTrue("the em dash must be one character", SessionCacheMessages.CLEARED.contains('\u2014'))
        assertEquals(1, SessionCacheMessages.CLEARED.count { it == '\u2014' })
    }

    @Test
    fun noMojibakeSurvivesInTheConfirmation() {
        // Every character of the old damage, as escapes so this file stays free of it: U+00E2 `a` with
        // a circumflex, U+20AC the euro sign, U+00C3 `A` with a tilde, U+0153 the `oe` ligature, and
        // the replacement character a lossy round trip leaves behind.
        for (damaged in listOf('\u00e2', '\u20ac', '\u00c3', '\u0153', '\ufffd')) {
            assertFalse(
                "the message still contains U+%04X".format(damaged.code),
                SessionCacheMessages.CLEARED.contains(damaged),
            )
        }
        assertFalse(SessionCacheMessages.CLEARED.contains("\u00e2\u20ac"))
    }

    /**
     * Scans the packaged sources for the classic double-encoding signature. Gradle runs unit tests
     * with the module directory as the working directory; when it does not, the test says so instead
     * of passing silently.
     */
    @Test
    fun thePackagedSourcesCarryNoDoubleEncodedUtf8() {
        val root = File("src/main/java")
        if (!root.isDirectory) {
            println("skipped: ${root.absolutePath} is not a directory (unit tests did not run from the module dir)")
            return
        }
        val damaged = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { file ->
                val text = file.readText()
                if (text.contains('\u00e2') && text.contains('\u20ac')) file.path else null
            }
            .toList()
        assertEquals(
            "files still carrying the double encoding: $damaged",
            emptyList<String>(),
            damaged,
        )
    }

    @Test
    fun theFolderPlaceholderCarriesTheExamplePrefix() {
        assertEquals("e.g. ブルアカ", BookmarkForm.FOLDER_NAME_PLACEHOLDER)
        assertTrue(BookmarkForm.FOLDER_NAME_PLACEHOLDER.startsWith("e.g. "))
        // A placeholder, never a value: nothing but the sample text itself.
        assertFalse(BookmarkForm.FOLDER_NAME_PLACEHOLDER.contains("Doujinshi"))
    }

    /**
     * Both folder-creation entry points must render the same placeholder: Search → Add entire result
     * profile (`BookmarkDialogs`) and Wishlist → Create folder (`Overlays`). They share one constant, and
     * this checks that both call sites really use it — the Search dialog was the one that had drifted to
     * `e.g. Doujinshi`.
     */
    @Test
    fun bothFolderDialogsUseTheSharedPlaceholderConstant() {
        val root = File("src/main/java/com/bookcovermatcher/ui")
        if (!root.isDirectory) {
            println("skipped: ${root.absolutePath} is not a directory (unit tests did not run from the module dir)")
            return
        }
        for (name in listOf("BookmarkDialogs.kt", "Overlays.kt")) {
            val source = File(root, name).readText()
            assertTrue(
                "$name must use BookmarkForm.FOLDER_NAME_PLACEHOLDER",
                source.contains("BookmarkForm.FOLDER_NAME_PLACEHOLDER"),
            )
            assertFalse(
                "$name must not carry a placeholder of its own",
                source.contains("\"e.g. Doujinshi\""),
            )
        }
    }
}
