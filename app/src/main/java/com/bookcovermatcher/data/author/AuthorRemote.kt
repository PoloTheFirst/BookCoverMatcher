// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.data.author

import com.bookcovermatcher.core.author.AuthorUrls
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/** The answer to one conditional GET. */
sealed interface FetchResult {
    data class Ok(
        val bytes: ByteArray,
        val etag: String?,
        val lastModified: String?,
        val contentType: String?,
    ) : FetchResult

    /** The server confirmed the cached copy is current, so there is nothing to download. */
    data object NotModified : FetchResult

    /** No usable body; [reason] is a short safe label, never a response body. */
    data class Failed(val reason: String) : FetchResult
}

/** Something that can perform a conditional HTTPS GET. Faked in tests. */
interface AuthorRemote {
    suspend fun get(url: String, etag: String?, lastModified: String?, maxBytes: Int): FetchResult
}

/**
 * Fetches the author's public files over HTTPS (v2.6.0 amendment).
 *
 * Deliberately narrow: the host must be one this app accepts, redirects are followed by hand (at most three) and
 * re-validated at every hop, no `Authorization` header is ever sent, responses are bounded by [maxBytes], and the
 * body is never logged. Conditional headers are supplied so an unchanged file costs one 304 instead of a download.
 *
 * Plain `HttpURLConnection` on purpose: the app already talks to Microsoft Graph this way, so no HTTP client
 * dependency is added for four files.
 */
class HttpAuthorRemote(
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 20_000,
    private val allowed: (String) -> Boolean = { AuthorUrls.resolve(it) != null },
) : AuthorRemote {

    override suspend fun get(
        url: String,
        etag: String?,
        lastModified: String?,
        maxBytes: Int,
    ): FetchResult {
        if (!allowed(url)) return FetchResult.Failed("refused-host")
        var current = url
        var hop = 0
        while (true) {
            val connection = try {
                (URL(current).openConnection() as HttpURLConnection).apply {
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    setRequestProperty("Accept", "*/*")
                    if (!etag.isNullOrBlank()) setRequestProperty("If-None-Match", etag)
                    if (!lastModified.isNullOrBlank()) setRequestProperty("If-Modified-Since", lastModified)
                }
            } catch (e: IOException) {
                return FetchResult.Failed("io")
            }

            try {
                val status = connection.responseCode
                if (status == HttpURLConnection.HTTP_NOT_MODIFIED) return FetchResult.NotModified
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: return FetchResult.Failed("redirect-without-location")
                    hop++
                    if (hop > MAX_REDIRECTS) return FetchResult.Failed("too-many-redirects")
                    current = java.net.URI(current).resolve(location).toString()
                    if (!allowed(current)) return FetchResult.Failed("refused-redirect")
                    continue
                }
                if (status != HttpURLConnection.HTTP_OK) return FetchResult.Failed("http-$status")
                val bytes = connection.inputStream.use { stream ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    var total = 0
                    while (true) {
                        val read = stream.read(chunk)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) return FetchResult.Failed("too-large")
                        buffer.write(chunk, 0, read)
                    }
                    buffer.toByteArray()
                }
                if (bytes.isEmpty()) return FetchResult.Failed("empty-body")
                return FetchResult.Ok(
                    bytes = bytes,
                    etag = connection.getHeaderField("ETag"),
                    lastModified = connection.getHeaderField("Last-Modified"),
                    contentType = connection.contentType,
                )
            } catch (e: SocketTimeoutException) {
                return FetchResult.Failed("timeout")
            } catch (e: UnknownHostException) {
                return FetchResult.Failed("no-host")
            } catch (e: IOException) {
                return FetchResult.Failed("io")
            } finally {
                connection.disconnect()
            }
        }
    }

    private companion object {
        /** Enough for GitHub's blob → raw hop, and nothing more. */
        const val MAX_REDIRECTS = 3
    }
}
