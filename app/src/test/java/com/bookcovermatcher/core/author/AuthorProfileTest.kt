// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.author

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The Author section's constants and its link rule (v2.6.0).
 *
 * The identity values are asserted exactly because the page must show the GitHub username and the nickname as
 * two different things: substituting one for the other is the mistake this test exists to prevent.
 */
class AuthorProfileTest {

    @Test fun theUsernameAndNicknameAreDistinctAndExact() {
        assertEquals("PoloTheFirst", AuthorProfile.USERNAME)
        assertEquals("Polo01", AuthorProfile.NICKNAME)
        assertFalse(AuthorProfile.USERNAME == AuthorProfile.NICKNAME)
    }

    @Test fun theProfileUrlIsTheOwnersProfile() {
        assertEquals("https://github.com/PoloTheFirst", AuthorProfile.GITHUB_URL)
    }

    @Test fun onlyHttpsLinksLeaveTheApp() {
        assertTrue(AuthorProfile.isSafeLink("https://github.com/PoloTheFirst"))
        assertFalse(AuthorProfile.isSafeLink("http://github.com/PoloTheFirst"))
        assertFalse(AuthorProfile.isSafeLink("javascript:alert(1)"))
        assertFalse(AuthorProfile.isSafeLink("intent://scan/#Intent;end"))
        assertFalse(AuthorProfile.isSafeLink("file:///etc/passwd"))
        assertFalse(AuthorProfile.isSafeLink("https://"))
        assertFalse(AuthorProfile.isSafeLink(""))
    }

    @Test fun aLinkTargetIsTrimmedAndGated() {
        assertEquals("https://example.org/x", AuthorProfile.linkTarget("  https://example.org/x  "))
        assertNull(AuthorProfile.linkTarget("http://example.org/x"))
        assertNull(AuthorProfile.linkTarget(null))
    }

    @Test fun theAvatarIsAccessibleWithADescription() {
        assertTrue(AuthorProfile.avatarDescription().contains("PoloTheFirst"))
    }

    @Test fun theBundledAssetsAreRealFiles() {
        val root = listOf(File("src/main/assets"), File("app/src/main/assets")).firstOrNull { it.isDirectory }
        assumeTrue("app assets are not readable from the test working directory", root != null)

        val readme = File(root, AuthorProfile.README_ASSET)
        assertTrue("the bundled README is missing: $readme", readme.isFile)
        assertTrue("the bundled README looks empty", readme.length() > 1000)
        val readmeText = readme.readText()
        assertTrue("the snapshot must be the owner's README", readmeText.contains("Polo"))
        assertTrue("the snapshot must not be a placeholder", readmeText.contains("#"))

        val avatar = File(root, AuthorProfile.AVATAR_ASSET)
        assertTrue("the bundled avatar is missing: $avatar", avatar.isFile)
        assertTrue("the avatar is implausibly small: ${avatar.length()} bytes", avatar.length() > 2_000)
        val magic = avatar.readBytes().take(3)
        assertEquals(
            "the avatar must be a real JPEG or PNG, not a text body",
            true,
            (magic.size == 3 && magic[0] == 0xFF.toByte() && magic[1] == 0xD8.toByte() && magic[2] == 0xFF.toByte()) ||
                (magic.size == 3 && magic[0] == 0x89.toByte() && magic[1] == 'P'.code.toByte() && magic[2] == 'N'.code.toByte()),
        )
    }
}
