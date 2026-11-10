// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.data.googledrive

import com.bookcovermatcher.core.remote.*
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GoogleDriveTransportTest {
    private class Connection(url: URL, private val status: Int, private val body: String) : HttpURLConnection(url) {
        var disconnected = false
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun connect() {}
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
    }

    @Test fun noRequestIsMadeWithoutAuthorization() = runBlocking {
        val source = GoogleDriveSource { error("Must not connect without token") }
        assertEquals(RemoteFailure.AuthRequired, (source.listFolder(null) as RemoteResult.Err).failure)
    }

    @Test fun listingEncodesOpaqueTokensAndNeverFollowsRedirects() = runBlocking {
        lateinit var connection: Connection
        val source = GoogleDriveSource { url -> Connection(url, 200, "{\"files\":[],\"nextPageToken\":\"next\"}").also { connection = it } }
        source.token = "test-token"
        val result = source.listFolder("folder/id", "opaque+/=&token") as RemoteResult.Ok
        assertEquals("next", result.value.nextPageToken)
        assertTrue(connection.url.query.contains("opaque%2B%2F%3D%26token"))
        assertEquals("www.googleapis.com", connection.url.host)
        assertEquals("Bearer test-token", connection.getRequestProperty("Authorization"))
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.disconnected)
    }

    @Test fun expiredAccessIsClearedAndPermissionErrorsRemainDistinct() = runBlocking {
        for ((status, failure) in listOf(401 to RemoteFailure.AuthExpired, 403 to RemoteFailure.PermissionDenied, 404 to RemoteFailure.NotFound, 429 to RemoteFailure.Throttled())) {
            val source = GoogleDriveSource { url -> Connection(url, status, "") }
            source.token = "test-token"
            assertEquals(failure, (source.listFolder(null) as RemoteResult.Err).failure)
            if (status == 401) assertNull(source.token)
        }
    }

    @Test fun metadataIsRecheckedBeforeAnyMediaDownload() = runBlocking {
        val requests = mutableListOf<URL>()
        val source = GoogleDriveSource { url ->
            requests += url
            Connection(url, 200, "{\"id\":\"id\",\"name\":\"file.xlsx\",\"mimeType\":\"application/vnd.google-apps.spreadsheet\"}")
        }
        source.token = "test-token"
        val output = ByteArrayOutputStream()
        val result = source.download(RemoteItem("id", "file.xlsx", false), output)
        assertEquals(DownloadOutcome.Failed(RemoteFailure.InvalidWorkbook), result)
        assertEquals(1, requests.size)
        assertEquals(0, output.size())
    }
}
