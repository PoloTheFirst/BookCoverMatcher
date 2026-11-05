package com.bookcovermatcher.online

import com.bookcovermatcher.core.online.reverse.SafeImageUrl
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/** The one failure type this fetcher reports; the caller turns it into "no image". */
class ItemUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The only fetcher used for reverse-image result **pages and pictures**.
 *
 * It exists because `HttpJsonClient` is not safe for this job: that client lets the platform follow redirects,
 * which means a `302` to `http://192.168.0.1/` would be honoured without the destination ever being validated. Here
 * every hop is checked with [SafeImageUrl] before it is contacted:
 *
 *  * **no automatic redirects** — each `Location` is validated and re-issued, at most [MAX_REDIRECTS] times;
 *  * **https only**, no credentials, no odd ports (inherited from [SafeImageUrl]);
 *  * **hard caps** — a page or image over the limit is abandoned instead of buffered;
 *  * **timeouts** on connect and read, so a slow host cannot hold a search open;
 *  * **the content type is checked**, not assumed: an "image" that arrives as `text/html` is refused.
 *
 * Nothing here retries, spoofs a browser identity, or tries to pass a challenge: a refusal is reported as
 * "no image", which is a normal state for a result card. Synchronous; callers run it off the main thread.
 */
class DiscoveryWebFetcher(
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 10_000,
) {

    class Fetched(val url: String, val contentType: String, val bytes: ByteArray)

    /** GET a result page. Only markup is accepted, and the body is read up to [maxBytes] — see [fetch]. */
    fun getPage(url: String, maxBytes: Int = MAX_PAGE_BYTES): Fetched = fetch(url, maxBytes, truncate = true) { type ->
        type.startsWith("text/html") || type.startsWith("application/xhtml") || type.startsWith("text/plain")
    }

    /** GET an image. The declared type must be one this app can decode; a truncated image is useless, so it fails. */
    fun getImage(url: String, maxBytes: Int = MAX_IMAGE_BYTES): Fetched = fetch(url, maxBytes, truncate = false) { type ->
        type in SUPPORTED_IMAGE_TYPES
    }

    /**
     * One GET with manual redirect handling.
     *
     * [truncate] decides what an oversized body means. For a **page** the body is simply cut off at the cap: the
     * metadata this feature reads lives in the document head, and a 1 MB shop page must not cost 1 MB of heap. For an
     * **image** the body is refused instead, because half a JPEG is not an image.
     */
    private fun fetch(url: String, maxBytes: Int, truncate: Boolean, accept: (String) -> Boolean): Fetched {
        var current = url
        var hops = 0
        while (true) {
            if (!SafeImageUrl.isFetchable(current)) throw ItemUnavailable("Not an address this app may fetch.")
            val connection = open(current)
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    val next = connection.getHeaderField("Location")
                        ?: throw ItemUnavailable("The page redirected without a destination.")
                    val resolved = resolveRedirect(next, current)
                    if (!SafeImageUrl.isFetchable(resolved)) {
                        throw ItemUnavailable("The page redirected to an address this app may not fetch.")
                    }
                    hops++
                    if (hops > MAX_REDIRECTS) throw ItemUnavailable("Too many redirects.")
                    current = resolved
                    continue
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    throw ItemUnavailable("HTTP $status from ${SafeImageUrl.host(current) ?: "the site"}.")
                }
                val type = (connection.contentType ?: "").substringBefore(';').trim().lowercase()
                if (!accept(type)) {
                    throw ItemUnavailable("Unsupported content type '${if (type.isEmpty()) "unknown" else type}'.")
                }
                val bytes = connection.inputStream.use { readLimited(it, maxBytes, truncate) }
                if (bytes.isEmpty()) throw ItemUnavailable("The site sent an empty response.")
                return Fetched(current, type, bytes)
            } catch (e: ItemUnavailable) {
                throw e
            } catch (e: SocketTimeoutException) {
                throw ItemUnavailable("The site did not answer in time.", e)
            } catch (e: UnknownHostException) {
                throw ItemUnavailable("The site could not be reached.", e)
            } catch (e: IOException) {
                throw ItemUnavailable("The connection failed: ${e.message ?: e.javaClass.simpleName}", e)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = this@DiscoveryWebFetcher.connectTimeoutMs
            readTimeout = this@DiscoveryWebFetcher.readTimeoutMs
            // The whole point: this fetcher validates every hop itself.
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "text/html,image/*;q=0.8")
        }

    private fun readLimited(input: InputStream, limit: Int, truncate: Boolean): ByteArray {
        val out = ByteArrayOutputStream(8 * 1024)
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) {
                if (!truncate) throw ItemUnavailable("The response exceeded ${limit / 1024} KB.")
                // Keep everything that fits and stop reading: the connection is closed by the caller, so an endless
                // stream cannot hold the app open or fill the heap.
                out.write(buffer, 0, n - (total - limit))
                break
            }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        const val MAX_REDIRECTS = 3
        const val MAX_PAGE_BYTES = 512 * 1024
        const val MAX_IMAGE_BYTES = 400 * 1024

        /** Only formats `BitmapFactory` can decode on every supported API level. */
        val SUPPORTED_IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")

        /** Descriptive and honest; never a fake browser identity. */
        const val USER_AGENT = "BookCoverMatcher/2.5 (Android; on-device cover matcher)"

        /**
         * A relative `Location` header is resolved against the hop that sent it.
         *
         * Returning an empty string for an unparseable value is deliberate: the caller then refuses the hop through
         * [SafeImageUrl] instead of guessing where the page wanted to go.
         */
        fun resolveRedirect(location: String, from: String): String {
            val target = location.trim()
            if (target.isEmpty()) return ""
            return try {
                java.net.URI(from).resolve(target).toString()
            } catch (e: Exception) {
                ""
            }
        }
    }
}

