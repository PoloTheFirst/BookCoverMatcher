// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Graph request construction and the trust rules in front of the bearer token (Phase 3).
 *
 * These tests are the app's defence against the two ways a token leaks: a server-supplied URL pointing
 * somewhere else, and an id interpolated raw into a path.
 */
class GraphUrlsTest {

    // ---- what we build ----------------------------------------------------------------------

    @Test fun theRootListingAsksForTheFieldsTheLibraryRecords() {
        val url = GraphUrls.rootChildren()

        assertTrue(url, url.startsWith("https://graph.microsoft.com/v1.0/me/drive/root/children?"))
        assertTrue("the projection is explicit, not 'everything'", url.contains("\$select=id,name,folder"))
        assertTrue("one full page is requested", url.contains("\$top=200"))
    }

    @Test fun aFolderListingCarriesTheNextPageTokenVerbatim() {
        val token = "https://graph.microsoft.com/v1.0/me/drive/items/01ABC/children?\$skiptoken=NEXT"

        val url = GraphUrls.children("01ABC", token)

        assertEquals("a page token is a whole URL and replaces the built one", token, url)
    }

    @Test fun withoutAPageTokenTheBuiltUrlIsUsed() {
        val url = GraphUrls.children("01ABC")

        assertTrue(url, url.startsWith("https://graph.microsoft.com/v1.0/me/drive/items/01ABC/children?"))
        assertFalse("no stray token is appended", url.contains("skiptoken"))
    }

    @Test fun anItemIdIsAlwaysPercentEncoded() {
        // A driveItem id is opaque and can contain characters that are significant in a path.
        assertEquals("01ABC%21def", GraphUrls.encodeItemId("01ABC!def"))
        assertEquals("a%20b", GraphUrls.encodeItemId("a b"))
        assertEquals("a%2Fb", GraphUrls.encodeItemId("a/b"))
        assertEquals("plain-Id_1", GraphUrls.encodeItemId("plain-Id_1"))
    }

    @Test fun theContentUrlUsesTheEncodedId() {
        val url = GraphUrls.content("01ABC!def")

        assertEquals("https://graph.microsoft.com/v1.0/me/drive/items/01ABC%21def/content", url)
    }

    // ---- what we accept ---------------------------------------------------------------------

    @Test fun aGraphApiUrlIsTrusted() {
        assertTrue(GraphUrls.isTrustedApi("https://graph.microsoft.com/v1.0/me/drive/root/children"))
        assertTrue(GraphUrls.isTrustedApi("https://GRAPH.microsoft.com/v1.0/me/drive/items/x?%24top=200"))
        assertTrue(GraphUrls.isTrustedApi("https://graph.microsoft.com:443/v1.0/me/drive/root"))
    }

    @Test fun aUrlOnAnotherHostIsNeverTrusted() {
        // The classic redirections: a foreign host, a lookalike suffix, and host-in-userinfo.
        assertFalse(GraphUrls.isTrustedApi("https://evil.example/v1.0/me/drive/root"))
        assertFalse(GraphUrls.isTrustedApi("https://graph.microsoft.com.evil.example/v1.0/me/drive"))
        assertFalse(GraphUrls.isTrustedApi("https://graph.microsoft.com@evil.example/v1.0/me/drive"))
        assertFalse(GraphUrls.isTrustedApi("https://evil.example@graph.microsoft.com/v1.0/me/drive"))
    }

    @Test fun aNonHttpsOrNonApiUrlIsNeverTrusted() {
        assertFalse(GraphUrls.isTrustedApi("http://graph.microsoft.com/v1.0/me/drive/root"))
        assertFalse(GraphUrls.isTrustedApi("https://graph.microsoft.com/beta/me/drive/root"))
        assertFalse(GraphUrls.isTrustedApi("https://graph.microsoft.com/v1.0-x/me/drive/root"))
        assertFalse(GraphUrls.isTrustedApi("https://graph.microsoft.com:8443/v1.0/me/drive/root"))
        assertFalse(GraphUrls.isTrustedApi("not a url"))
        assertFalse(GraphUrls.isTrustedApi(""))
    }

    // ---- pagination tokens -------------------------------------------------------------------

    @Test fun aTrustedNextLinkIsAcceptedAsTheNextPage() {
        val next = "https://graph.microsoft.com/v1.0/me/drive/root/children?\$skiptoken=abc"

        assertEquals(next, GraphUrls.resolvePageToken(next))
    }

    @Test fun aNextLinkFromAnotherHostIsRejectedInsteadOfFollowed() {
        // Following this would send the user's token to a stranger; the caller reports a failure.
        assertNull(GraphUrls.resolvePageToken("https://evil.example/v1.0/me/drive/root/children?\$skiptoken=x"))
        assertNull(GraphUrls.resolvePageToken("http://graph.microsoft.com/v1.0/me/drive/root/children"))
        assertNull(GraphUrls.resolvePageToken(""))
    }
}
