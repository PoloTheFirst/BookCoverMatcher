// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.remote

import com.bookcovermatcher.data.onedrive.GraphHttp
import com.bookcovermatcher.data.onedrive.GraphWorkbookSource
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Folder navigation and pagination as the source actually performs them (Phase 3): which URL is asked
 * for, and what is done with a page token.
 *
 * The transport is a recording subclass, so these tests exercise the real
 * [GraphWorkbookSource] logic — the URL choice, the token handling, the parsing and the filtering —
 * without a socket. What they cannot prove is HTTP behaviour itself: status codes, `Retry-After`
 * parsing and the 302 hop live in [GraphHttp] and are reported as not unit-tested (§20.8).
 */
class RemoteBrowsingTest {

    /** Records every URL and answers from a script. */
    private class RecordingHttp : GraphHttp() {
        val urls = mutableListOf<String>()
        var answer: JsonOutcome = JsonOutcome.Failed(RemoteFailure.NotFound)
        var downloadUrl: String? = null

        override fun getJson(url: String, token: AccessToken, stage: String): JsonOutcome {
            urls += url
            return answer
        }

        override suspend fun download(
            itemId: String,
            token: AccessToken,
            sink: OutputStream,
            onProgress: (DownloadProgress) -> Unit,
        ): DownloadOutcome {
            downloadUrl = itemId
            sink.write(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
            return DownloadOutcome.Completed(4)
        }
    }

    private fun source(): Pair<GraphWorkbookSource, RecordingHttp> {
        val http = RecordingHttp()
        val tokens = AccessTokenSource { TokenResult.Success(AccessToken.of("token-value")) }
        return GraphWorkbookSource(tokens = tokens, http = http) to http
    }

    private val page = """
        {"value":[
          {"id":"F1","name":"Catalogue","folder":{"childCount":1}},
          {"id":"A1","name":"covers.xlsx","file":{},"size":10},
          {"id":"A2","name":"notes.txt","file":{},"size":3}
        ],"@odata.nextLink":"https://graph.microsoft.com/v1.0/me/drive/root/children?${'$'}skiptoken=NEXT"}
    """.trimIndent()

    @Test fun theRootFolderAsksForTheRootChildren() = runBlocking {
        val (source, http) = source()
        http.answer = GraphHttp.JsonOutcome.Body(page)

        val listing = (source.listFolder(null) as RemoteResult.Ok).value

        assertEquals(1, http.urls.size)
        assertTrue(
            http.urls.single(),
            http.urls.single().startsWith("https://graph.microsoft.com/v1.0/me/drive/root/children?"),
        )
        assertTrue("the projection names the fields", http.urls.single().contains("\$select=id,name,folder"))
        assertEquals("folders are kept", listOf("F1"), listing.folders.map { it.id })
        assertEquals("unsupported files are filtered out", listOf("A1"), listing.files.map { it.id })
        assertEquals(
            "https://graph.microsoft.com/v1.0/me/drive/root/children?${'$'}skiptoken=NEXT",
            listing.nextPageToken,
        )
    }

    @Test fun navigatingIntoAFolderUsesTheItemsChildrenUrlWithAnEncodedId() = runBlocking {
        val (source, http) = source()
        http.answer = GraphHttp.JsonOutcome.Body("""{"value":[]}""")

        source.listFolder("F1!a/b")

        val url = http.urls.single()
        assertTrue(url, url.startsWith("https://graph.microsoft.com/v1.0/me/drive/items/F1%21a%2Fb/children?"))
        assertFalse("the raw id never reaches the path", url.contains("F1!a/b"))
    }

    @Test fun theNextPageIsRequestedWithTheServersOwnLink() = runBlocking {
        val (source, http) = source()
        http.answer = GraphHttp.JsonOutcome.Body("""{"value":[]}""")
        val next = "https://graph.microsoft.com/v1.0/me/drive/items/F1/children?${'$'}skiptoken=NEXT"

        source.listFolder("F1", pageToken = next)

        assertEquals(next, http.urls.single())
    }

    @Test fun aForeignNextLinkIsRefusedBeforeAnyRequest() = runBlocking {
        val (source, http) = source()

        val result = source.listFolder(null, pageToken = "https://evil.example/v1.0/me/drive/root/children")

        assertEquals(RemoteFailure.BadRequest, (result as RemoteResult.Err).failure)
        assertTrue("nothing may be requested with a token for a foreign URL", http.urls.isEmpty())
    }

    @Test fun aProbeReturnsTheItemWhenOneDriveStillHasIt() = runBlocking {
        val (source, http) = source()
        http.answer = GraphHttp.JsonOutcome.Body("""{"id":"A1","name":"covers.xlsx","file":{},"eTag":"E1","size":42}""")

        val state = (source.probe("A1") as RemoteResult.Ok).value

        assertEquals("A1", (state as RemoteState.Present).item.id)
        assertEquals("E1", state.item.eTag)
        assertTrue(
            http.urls.single(),
            http.urls.single().startsWith("https://graph.microsoft.com/v1.0/me/drive/items/A1?"),
        )
    }

    @Test fun aProbeForADeletedItemIsAMissingStateNotAnError() = runBlocking {
        val (source, http) = source()
        http.answer = GraphHttp.JsonOutcome.Failed(RemoteFailure.NotFound)

        val state = (source.probe("A1") as RemoteResult.Ok).value

        assertEquals(RemoteState.Missing, state)
    }

    @Test fun aProbeThatFailsForAnotherReasonStaysAnError() = runBlocking {
        val (source, http) = source()
        http.answer = GraphHttp.JsonOutcome.Failed(RemoteFailure.Throttled(12))

        val result = source.probe("A1")

        assertEquals(RemoteFailure.Throttled(12), (result as RemoteResult.Err).failure)
    }

    @Test fun theDownloadAsksForTheItemIdAndStreamsThroughTheTransport() = runBlocking {
        val (source, http) = source()
        val sink = java.io.ByteArrayOutputStream()

        val outcome = source.download(RemoteItem(id = "A1", name = "covers.xlsx", isFolder = false), sink)

        assertEquals("A1", http.downloadUrl)
        assertEquals(DownloadOutcome.Completed(4), outcome)
        assertEquals(4, sink.size())
    }
}
