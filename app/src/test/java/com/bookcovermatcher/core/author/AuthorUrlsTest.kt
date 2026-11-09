// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.author

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one gate between the author's README and the network (v2.6.0 amendment).
 *
 * These cases are the reason the banner was invisible: its README URL is a GitHub **blob page**, which serves
 * HTML, so it has to be translated to the same file on `raw.githubusercontent.com` before an image decoder can see
 * it. Everything that is not one of the two allowed hosts stays out.
 */
class AuthorUrlsTest {

    @Test fun aGithubBlobPageBecomesTheRawFile() {
        assertEquals(
            "https://raw.githubusercontent.com/PoloTheFirst/PoloTheFirst/main/assets/2_PC-A.png",
            AuthorUrls.resolve("https://github.com/PoloTheFirst/PoloTheFirst/blob/main/assets/2_PC-A.png"),
        )
    }

    @Test fun aBlobPageQueryIsDroppedButApathyPathOfSlashesIsKept() {
        assertEquals(
            "https://raw.githubusercontent.com/o/r/main/a/b/c.png",
            AuthorUrls.resolve("https://github.com/o/r/blob/main/a/b/c.png?raw=true"),
        )
    }

    @Test fun rawFilesAreAcceptedAsTheyAre() {
        val pacman = "https://raw.githubusercontent.com/PoloTheFirst/PoloTheFirst/output/pacman-contribution-graph.svg"
        assertEquals(pacman, AuthorUrls.resolve(pacman))
    }

    @Test fun aBadgeKeepsItsQueryBecauseTheQueryIsTheBadge() {
        val badge = "https://img.shields.io/badge/Python-3670A0?&logo=python&logoColor=ffdd54"
        assertEquals(badge, AuthorUrls.resolve(badge))
    }

    @Test fun otherGithubPagesAreRefusedRatherThanGuessedAt() {
        assertNull(AuthorUrls.resolve("https://github.com/PoloTheFirst/PoloTheFirst"))
        assertNull(AuthorUrls.resolve("https://github.com/PoloTheFirst/PoloTheFirst/issues/1"))
        assertNull(AuthorUrls.resolve("https://github.com/PoloTheFirst/PoloTheFirst/blob/main"))
        assertNull(AuthorUrls.resolve("https://gist.github.com/PoloTheFirst/abc"))
    }

    @Test fun traversalAndOddSegmentsAreRefused() {
        assertNull(AuthorUrls.resolve("https://github.com/../r/blob/main/a.png"))
        assertNull(AuthorUrls.resolve("https://github.com/o/r/blob/..%2F/a.png"))
        assertNull(AuthorUrls.resolve("https://github.com/o/r/blob/main/../../etc/passwd"))
    }

    @Test fun onlyHttpsOnTheDefaultPortIsAllowed() {
        assertNull(AuthorUrls.resolve("http://raw.githubusercontent.com/o/r/main/a.png"))
        assertNull(AuthorUrls.resolve("https://raw.githubusercontent.com:8443/o/r/main/a.png"))
        assertNull(AuthorUrls.resolve("https://user:pass@raw.githubusercontent.com/o/r/main/a.png"))
        assertNull(AuthorUrls.resolve("file:///etc/passwd"))
        assertNull(AuthorUrls.resolve("javascript:alert(1)"))
        assertNull(AuthorUrls.resolve("intent://scan/#Intent;end"))
    }

    @Test fun unknownHostsAreRefused() {
        assertNull(AuthorUrls.resolve("https://example.org/a.png"))
        assertNull(AuthorUrls.resolve("https://raw.githubusercontent.com.evil.test/a.png"))
        assertNull(AuthorUrls.resolve(""))
        assertNull(AuthorUrls.resolve(null))
    }

    @Test fun thePredicateAgreesWithResolve() {
        assertTrue(AuthorUrls.isAllowed("https://img.shields.io/badge/x-y"))
        assertFalse(AuthorUrls.isAllowed("https://example.org/a.png"))
    }
}
