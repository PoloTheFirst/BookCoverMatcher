package com.bookcovermatcher.online

import com.bookcovermatcher.core.online.OnlineFailure
import com.bookcovermatcher.core.online.ProductSourceException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * The only place in the app that opens a socket.
 *
 * `java.net.HttpURLConnection` is used on purpose: it is in the platform, so the online feature
 * adds no networking dependency and no new native code. Every failure is translated into an
 * [OnlineFailure] so the caller can tell the user what actually happened instead of showing
 * "something went wrong".
 *
 * Only `https` is accepted. The app talks to two hosts, both hard-coded by the callers below.
 */
class HttpJsonClient(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
    private val maxBytes: Int = 512 * 1024,
) {
    class Response(val status: Int, val body: String)

    /** GET a JSON document. Throws [ProductSourceException] on any transport or status problem. */
    fun getJson(url: String, acceptLanguage: String? = null): Response {
        val fetched = get(url, "application/json", acceptLanguage, maxBytes)
        return Response(fetched.status, String(fetched.bytes, Charsets.UTF_8))
    }

    /**
     * GET an HTML page (a provider's public search page).
     *
     * [HTML_MAX_BYTES] is larger than the JSON cap on purpose: a shop's search page carries its own
     * markup and inline templates (DLsite's is ~275 KB for 30 works). The body is decoded as UTF-8,
     * which is what the providers this app reads declare.
     */
    fun getHtml(url: String, acceptLanguage: String? = null): Response {
        val fetched = get(url, "text/html,application/xhtml+xml", acceptLanguage, HTML_MAX_BYTES)
        return Response(fetched.status, String(fetched.bytes, Charsets.UTF_8))
    }

    /** GET raw bytes (a cover thumbnail). */
    fun getBytes(url: String, byteLimit: Int = maxBytes): ByteArray =
        get(url, "image/*", null, byteLimit).bytes

    private class Fetched(val status: Int, val bytes: ByteArray)

    private fun get(url: String, accept: String, acceptLanguage: String?, byteLimit: Int): Fetched {
        val parsed = try {
            URL(url)
        } catch (e: IOException) {
            throw ProductSourceException(OnlineFailure.BAD_RESPONSE, "The catalogue URL is not valid.", e)
        }
        if (parsed.protocol != "https") {
            throw ProductSourceException(OnlineFailure.BAD_RESPONSE, "Refusing to contact a non-HTTPS address.")
        }

        var connection: HttpURLConnection? = null
        try {
            connection = (parsed.openConnection() as HttpURLConnection).apply {
                connectTimeout = this@HttpJsonClient.connectTimeoutMs
                readTimeout = this@HttpJsonClient.readTimeoutMs
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", accept)
                acceptLanguage?.let { setRequestProperty("Accept-Language", it) }
            }

            val status = connection.responseCode
            if (status in 300..399) {
                throw ProductSourceException(OnlineFailure.BAD_RESPONSE, "The catalogue redirected the request ($status).")
            }
            if (status != HttpURLConnection.HTTP_OK) throw failureForStatus(status)

            val body = connection.inputStream.use { readLimited(it, byteLimit) }
            return Fetched(status, body)
        } catch (e: ProductSourceException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw ProductSourceException(OnlineFailure.TIMEOUT, "The catalogue did not answer in time.", e)
        } catch (e: UnknownHostException) {
            throw ProductSourceException(OnlineFailure.OFFLINE, "No connection to the catalogue.", e)
        } catch (e: SSLException) {
            throw ProductSourceException(OnlineFailure.OFFLINE, "The secure connection failed.", e)
        } catch (e: IOException) {
            throw ProductSourceException(OnlineFailure.OFFLINE, "The connection failed: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            connection?.disconnect()
        }
    }

    private fun failureForStatus(status: Int): ProductSourceException = when (status) {
        401, 403, 451 -> ProductSourceException(
            OnlineFailure.BLOCKED,
            "The site refused an automated request (HTTP $status). Open it in your browser instead.",
        )
        429 -> ProductSourceException(OnlineFailure.RATE_LIMITED, "The catalogue is throttling requests (429). Try again shortly.")
        503 -> ProductSourceException(OnlineFailure.RATE_LIMITED, "The catalogue is busy (503). Try again shortly.")
        in 500..599 -> ProductSourceException(OnlineFailure.SERVER_ERROR, "The catalogue failed (HTTP $status).")
        in 400..499 -> ProductSourceException(OnlineFailure.BAD_RESPONSE, "The catalogue rejected the request (HTTP $status).")
        else -> ProductSourceException(OnlineFailure.BAD_RESPONSE, "Unexpected answer from the catalogue (HTTP $status).")
    }

    /** Stops a hostile or broken endpoint from making the app allocate without limit. */
    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(8 * 1024)
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) throw ProductSourceException(OnlineFailure.BAD_RESPONSE, "The catalogue sent more data than expected.")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        const val HTTP_OK = 200

        /** A shop search page is markup, not a JSON document; it needs more room. */
        const val HTML_MAX_BYTES = 2 * 1024 * 1024

        /** A descriptive UA: every provider asks automated clients to identify themselves. */
        const val USER_AGENT = "BookCoverMatcher/2.2 (Android; on-device cover matcher)"
    }
}
