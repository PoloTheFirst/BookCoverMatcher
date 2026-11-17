// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.core.remote

import com.bookcovermatcher.data.onedrive.GraphHttp
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GraphDownloadRecoveryTest {
    @Test fun metadataDiagnosticsRedactTheTerminalItemIdAndQuery() {
        assertEquals("https://graph.microsoft.com/v1.0/me/drive/items/{id}",
            RemoteDiagnostics.sanitizePath(GraphUrls.downloadMetadata("private-item!1")))
    }
    private class Reply(val status: Int, val body: String = "", val location: String? = null)
    private class Connection(url: URL, val reply: Reply) : HttpURLConnection(url) {
        var closed = false
        override fun connect() {}
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = reply.status
        override fun getHeaderField(name: String): String? = if (name == "Location") reply.location else null
        override fun getInputStream() = reply.body.byteInputStream()
        override fun getErrorStream() = "{}".byteInputStream()
        override fun getContentLengthLong() = reply.body.toByteArray().size.toLong()
    }
    private fun exercise(vararg replies: Reply): Triple<DownloadOutcome, String, List<Connection>> = runBlocking {
        val connections = mutableListOf<Connection>()
        val http = GraphHttp(connectionFactory = { url ->
            Connection(url, replies[connections.size]).also { connections.add(it) }
        })
        val sink = ByteArrayOutputStream()
        val outcome = http.download("item!1", AccessToken.of("synthetic-token"), sink) {}
        assertTrue(connections.all { it.closed })
        for (connection in connections) {
            if (connection.url.host == "my.microsoftpersonalcontent.com" && connection.getRequestProperty("Authorization") != null) {
                assertEquals("Bearer scoped+capability", connection.getRequestProperty("Authorization"))
                assertFalse(connection.instanceFollowRedirects)
                continue
            }
            assertEquals(if (connection.url.host == "graph.microsoft.com") "Bearer synthetic-token" else null,
                connection.getRequestProperty("Authorization"))
        }
        Triple(outcome, sink.toString("UTF-8"), connections)
    }
    private val metadata = Reply(200, """{"@microsoft.graph.downloadUrl":"https://storage.example/fresh?tempauth=synthetic"}""")
    private val personalUrl = "https://my.microsoftpersonalcontent.com/personal/test/download?tempauth=scoped%2Bcapability"

    @Test fun personal401RetriesWithOnlyTheUrlsCapability() {
        val (outcome, bytes, calls) = exercise(Reply(302, location = personalUrl), Reply(401), Reply(200, "workbook"))
        assertTrue(outcome is DownloadOutcome.Completed)
        assertEquals("workbook", bytes)
        assertNull(calls[1].getRequestProperty("Authorization"))
        assertEquals(personalUrl, calls[2].url.toString())
        assertEquals(3, calls.size)
    }
    @Test fun temporaryCapabilityIsNotForwardedOnRedirect() {
        val (_, bytes, calls) = exercise(Reply(302, location = personalUrl), Reply(401),
            Reply(302, location = "https://cdn.example/file"), Reply(200, "bytes"))
        assertEquals("bytes", bytes)
        assertNull(calls.last().getRequestProperty("Authorization"))
    }
    @Test fun successfulPersonalDownloadDoesNotAddAuthorization() {
        val (_, _, calls) = exercise(Reply(302, location = personalUrl), Reply(200, "bytes"))
        assertNull(calls.last().getRequestProperty("Authorization"))
        assertEquals(2, calls.size)
    }
    @Test fun noCapabilityRetryForOtherHosts() {
        val (_, _, calls) = exercise(Reply(302, location = "https://my.microsoftpersonalcontent.com.evil.example/file?tempauth=scoped%2Bcapability"),
            Reply(401), metadata, Reply(200, "bytes"))
        assertEquals("graph.microsoft.com", calls[2].url.host)
    }
    @Test fun downgradeRedirectIsNeverFetched() {
        val (_, _, calls) = exercise(Reply(302, location = personalUrl), Reply(401), Reply(302, location = "http://cdn.example/file"))
        assertEquals(3, calls.size)
    }
    @Test fun storageRedirectLoopIsBounded() {
        val replies = listOf(Reply(302, location = "https://storage.example/loop")) +
            List(6) { Reply(302, location = "https://storage.example/loop") }
        val (_, bytes, calls) = exercise(*replies.toTypedArray())
        assertEquals("", bytes)
        assertEquals(7, calls.size)
    }

    @Test fun storageRejectionRecoversUsingFreshMetadataWithoutAppendingErrorBytes() {
        val (outcome, bytes, calls) = exercise(Reply(302, location = "https://storage.example/old"),
            Reply(401, "unauthorized"), metadata, Reply(200, "workbook"))
        assertTrue(outcome is DownloadOutcome.Completed)
        assertEquals("workbook", bytes)
        assertEquals(4, calls.size)
        assertEquals("*/*", calls.last().getRequestProperty("Accept"))
        assertTrue(calls[2].url.query.contains("@microsoft.graph.downloadUrl"))
    }
    @Test fun rejectedContentEndpointCanRecoverWhileMetadataStillAcceptsSession() {
        assertTrue(exercise(Reply(401), metadata, Reply(200, "bytes")).first is DownloadOutcome.Completed)
    }
    @Test fun persistentStorageRejectionIsNotExpiredLoginAndIsBounded() {
        val (outcome, bytes, calls) = exercise(Reply(302, location = "https://storage.example/old"),
            Reply(403), metadata, Reply(401))
        assertEquals(DownloadOutcome.Failed(RemoteFailure.DownloadUnavailable), outcome)
        assertEquals("", bytes)
        assertEquals(4, calls.size)
    }
    @Test fun metadataAuthenticationFailureStillRequestsSignIn() {
        assertEquals(DownloadOutcome.Failed(RemoteFailure.AuthExpired), exercise(Reply(401), Reply(401)).first)
    }
    @Test fun unsafeMetadataUrlIsNotFetched() {
        val (outcome, _, calls) = exercise(Reply(401), Reply(200,
            """{"@microsoft.graph.downloadUrl":"http://storage.example/plaintext"}"""))
        assertEquals(DownloadOutcome.Failed(RemoteFailure.DownloadUnavailable), outcome)
        assertEquals(2, calls.size)
    }
    @Test fun directContentSuccessNeedsNoRecovery() {
        assertEquals("workbook", exercise(Reply(200, "workbook")).second)
    }
    @Test fun absentDownloadUrlDoesNotLoopOrExpireSession() {
        assertEquals(DownloadOutcome.Failed(RemoteFailure.DownloadUnavailable), exercise(Reply(401), Reply(200, "{}")).first)
    }
}
