// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.remote

import com.bookcovermatcher.data.onedrive.GraphWorkbookSource
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the remote source does when sign-in cannot produce a token (Phase 3).
 *
 * This is the real [GraphWorkbookSource] with a fake [OneDriveAuth]: when authentication fails, no HTTP
 * request is attempted at all and the caller gets the *specific* reason, because "sign in" and "sign in
 * again" are different sentences and different user actions.
 */
class GraphWorkbookSourceAuthTest {

    private fun sourceWith(token: TokenResult): Pair<GraphWorkbookSource, FakeOneDriveAuth> {
        val auth = FakeOneDriveAuth().apply { tokenResult = token }
        return GraphWorkbookSource(tokens = auth) to auth
    }

    @Test fun aMissingAccountIsReportedAsSignInRequired() = runBlocking {
        val (source, auth) = sourceWith(TokenResult.Failure(AuthFailure.CANCELLED))

        val result = source.listFolder(null)

        assertEquals(RemoteFailure.AuthRequired, (result as RemoteResult.Err).failure)
        assertEquals("the token was actually asked for", 1, auth.tokenRequests)
    }

    @Test fun anExpiredAccountIsReportedAsExpiredNotMissing() = runBlocking {
        val (source, _) = sourceWith(TokenResult.Failure(AuthFailure.INTERACTION_REQUIRED))

        val result = source.probe("A1")

        assertEquals(RemoteFailure.AuthExpired, (result as RemoteResult.Err).failure)
    }

    @Test fun anUnusableConfigurationIsReportedAsNotConfigured() = runBlocking {
        val (source, _) = sourceWith(TokenResult.Failure(AuthFailure.CONFIGURATION))

        val result = source.listFolder("F1")

        assertEquals(RemoteFailure.NotConfigured, (result as RemoteResult.Err).failure)
    }

    @Test fun aDownloadWithoutATokenWritesNothingAtAll() = runBlocking {
        val (source, _) = sourceWith(TokenResult.Failure(AuthFailure.CANCELLED))
        val sink = ByteArrayOutputStream()
        val item = RemoteItem(id = "A1", name = "covers.xlsx", isFolder = false)

        val outcome = source.download(item, sink)

        assertEquals(RemoteFailure.AuthRequired, (outcome as DownloadOutcome.Failed).failure)
        assertEquals("no bytes must be produced without a token", 0, sink.size())
    }

    @Test fun theSourceIsReadOnlyByDesign() {
        // A write operation would be a scope error in this release; the interface has none, and this
        // guards against one being added by accident later.
        val forbidden = listOf("upload", "put", "post", "delete", "move", "copy", "rename", "update")
        val members = RemoteWorkbookSource::class.java.methods.map { it.name.lowercase() }

        for (name in forbidden) {
            assertTrue(
                "RemoteWorkbookSource must stay read-only, but declares $name",
                members.none { it == name || it.startsWith(name) },
            )
        }
        assertTrue(members.any { it == "listfolder" })
        assertTrue(members.any { it == "probe" })
        assertTrue(members.any { it == "download" })
    }
}
