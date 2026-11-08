// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.data.onedrive

import com.bookcovermatcher.core.remote.AccessToken
import com.bookcovermatcher.core.remote.DownloadOutcome
import com.bookcovermatcher.core.remote.DownloadProgress
import com.bookcovermatcher.core.remote.GraphRedirect
import com.bookcovermatcher.core.remote.GraphUrls
import com.bookcovermatcher.core.remote.RemoteFailure
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * The only place in the app that opens a socket to Microsoft Graph (v2.6.0-dev.1 Phase 3).
 *
 * `java.net.HttpURLConnection` again, for the same reason `HttpJsonClient` uses it for the catalogues:
 * it is in the platform, so this feature adds no HTTP dependency. What is *not* shared is the policy —
 * this class is deliberately stricter:
 *
 *  * every API URL is checked with [GraphUrls.isTrustedApi] **before** the bearer token is attached;
 *  * the download follows the content redirect **without** the token ([GraphRedirect]), so a Graph
 *    token can never be replayed to a storage host;
 *  * `Retry-After` is read and surfaced instead of being ignored, and nothing is retried automatically:
 *    retrying into a throttle is how a client earns a longer ban;
 *  * failures are mapped to [RemoteFailure] so the UI can say what happened;
 *  * no token and no response body is ever logged.
 *
 * Downloads are buffered at [BUFFER_BYTES] and the workbook is never held in memory: bytes go straight
 * to the caller's sink, and the only counters kept are "how many" and "of how many".
 *
 * The class and its two members are `open` for one reason: the *navigation* logic above it (which URL,
 * which page token, which item) is then unit-tested through a recording subclass with no socket
 * involved. The status/`Retry-After`/`IOException` mapping lives in [GraphFailures] and is unit-tested
 * directly; the socket code around it (connect, read, the 302 hop) is exercised only by the manual
 * verification recorded in `historical_prompt/v2.6.0-dev.1-ag.md` §20.8 and §24.
 */
open class GraphHttp(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
) {

    /** A JSON response, or the reason there is none. */
    sealed interface JsonOutcome {
        data class Body(val text: String) : JsonOutcome
        data class Failed(val failure: RemoteFailure) : JsonOutcome
    }

    /** GET a Graph JSON document with the bearer token. */
    open fun getJson(url: String, token: AccessToken): JsonOutcome {
        if (!GraphUrls.isTrustedApi(url)) return JsonOutcome.Failed(RemoteFailure.BadRequest)
        var connection: HttpURLConnection? = null
        return try {
            connection = open(url, token, followRedirects = true)
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                JsonOutcome.Failed(failureForStatus(status, connection))
            } else {
                JsonOutcome.Body(connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            }
        } catch (e: SocketTimeoutException) {
            JsonOutcome.Failed(RemoteFailure.Timeout)
        } catch (e: UnknownHostException) {
            JsonOutcome.Failed(RemoteFailure.Offline)
        } catch (e: SSLException) {
            JsonOutcome.Failed(RemoteFailure.Unstable)
        } catch (e: IOException) {
            JsonOutcome.Failed(RemoteFailure.Unstable)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Stream the item's bytes into [sink], following the content redirect without credentials.
     *
     * Progress is reported at most every [PROGRESS_BYTES] or [PROGRESS_INTERVAL_MS], whichever comes
     * later, so the UI stays smooth without the transfer being throttled by state updates.
     */
    open suspend fun download(
        itemId: String,
        token: AccessToken,
        sink: OutputStream,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadOutcome {
        val contentUrl = GraphUrls.content(itemId)
        if (!GraphUrls.isTrustedApi(contentUrl)) return DownloadOutcome.Failed(RemoteFailure.BadRequest)

        var connection: HttpURLConnection? = null
        try {
            connection = open(contentUrl, token, followRedirects = false)
            var status = connection.responseCode

            // One credential-free hop: the API answers 302 to a short-lived storage URL.
            if (status in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                when (val plan = GraphRedirect.plan(location)) {
                    is GraphRedirect.Plan.Reject -> return DownloadOutcome.Failed(plan.failure)
                    is GraphRedirect.Plan.FollowWithoutCredentials -> {
                        connection = open(plan.url, null, followRedirects = true)
                        status = connection.responseCode
                    }
                }
            }

            if (status != HttpURLConnection.HTTP_OK) {
                return DownloadOutcome.Failed(failureForStatus(status, connection))
            }

            val total = connection.contentLengthLong.takeIf { it > 0L }
            val buffer = ByteArray(BUFFER_BYTES)
            var written = 0L
            var lastReported = 0L
            var lastReportAt = System.currentTimeMillis()
            connection.inputStream.use { input ->
                while (true) {
                    // Cooperative cancellation: the caller's coroutine ends, the transfer stops, and the
                    // caller deletes the partial file.
                    if (!currentCoroutineContext().isActive) {
                        connection.disconnect()
                        return DownloadOutcome.Cancelled
                    }
                    val read = input.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    written += read
                    val now = System.currentTimeMillis()
                    if (written - lastReported >= PROGRESS_BYTES || now - lastReportAt >= PROGRESS_INTERVAL_MS) {
                        lastReported = written
                        lastReportAt = now
                        onProgress(DownloadProgress(written, total))
                    }
                }
            }
            sink.flush()
            onProgress(DownloadProgress(written, total))
            return DownloadOutcome.Completed(written)
        } catch (e: SocketTimeoutException) {
            return DownloadOutcome.Failed(RemoteFailure.Timeout)
        } catch (e: UnknownHostException) {
            return DownloadOutcome.Failed(RemoteFailure.Offline)
        } catch (e: SSLException) {
            return DownloadOutcome.Failed(RemoteFailure.Unstable)
        } catch (e: IOException) {
            return DownloadOutcome.Failed(storageOrTransport(e))
        } finally {
            connection?.disconnect()
        }
    }


    private fun open(url: String, token: AccessToken?, followRedirects: Boolean): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = followRedirects
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        // The token goes on the request and nowhere else: not into the URL, not into a log.
        if (token != null) connection.setRequestProperty("Authorization", "Bearer ${token.reveal()}")
        return connection
    }

    /** `Retry-After` in seconds, when the server sent one. */
    private fun retryAfterSeconds(connection: HttpURLConnection): Long? =
        GraphFailures.retryAfterSeconds(connection.getHeaderField("Retry-After"))

    /** The status mapping itself lives in [GraphFailures], so it can be tested without a socket. */
    private fun failureForStatus(status: Int, connection: HttpURLConnection): RemoteFailure =
        GraphFailures.forStatus(status, retryAfterSeconds(connection))

    /**
     * Distinguishes "the device is full" from "the network broke", because the two need different
     * sentences and different user action: `ENOSPC` reaches us as a plain [IOException].
     */
    private fun storageOrTransport(e: IOException): RemoteFailure = GraphFailures.forIoFailure(e.message)

    companion object {
        /** 128 KiB, the same buffer the local staging and library import paths use. */
        const val BUFFER_BYTES = 128 * 1024
        private const val PROGRESS_BYTES = 64L * 1024
        private const val PROGRESS_INTERVAL_MS = 100L
    }
}

