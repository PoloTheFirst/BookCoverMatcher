// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.data.onedrive

import com.bookcovermatcher.core.remote.RemoteFailure
import java.net.HttpURLConnection

/**
 * How an HTTP answer becomes a [RemoteFailure] (v2.6.0-dev.1 Phase 7).
 *
 * This mapping used to be three private functions inside [GraphHttp], which meant the most consequential
 * decision in the OneDrive stack — *which* failure the user is told about — could only be verified by
 * reading it. It is pure and takes primitives here, so every status code, every `Retry-After` shape
 * (present, absent, malformed, negative) and the storage-versus-transport distinction are asserted
 * directly in `GraphFailuresTest`. [GraphHttp] has no logic of its own left in this area: it reads the
 * header and calls these.
 *
 * Nothing here performs I/O, and nothing here sees a token.
 */
object GraphFailures {

    /**
     * `Retry-After` in seconds, or `null` when the header is absent, empty or not a non-negative integer.
     *
     * Graph sends the delay-seconds form; the HTTP-date form is legal but not used by this API, and
     * treating it as "no delay" is the safe reading: the user is told OneDrive is busy either way, and
     * nothing is retried automatically.
     */
    fun retryAfterSeconds(rawHeader: String?): Long? =
        rawHeader?.trim()?.takeIf { it.isNotEmpty() }?.toLongOrNull()?.takeIf { it >= 0L }

    /**
     * The failure for an unsuccessful status.
     *
     * `503` is the interesting one: Graph uses it both for "busy, come back in n seconds" (with a
     * `Retry-After`) and for "something broke" (without one), and the two deserve different sentences.
     */
    fun forStatus(status: Int, retryAfterSeconds: Long?): RemoteFailure = when {
        status == HttpURLConnection.HTTP_UNAUTHORIZED -> RemoteFailure.AuthExpired
        status == HttpURLConnection.HTTP_FORBIDDEN -> RemoteFailure.AuthRequired
        // 410 Gone is the same situation as 404 for a driveItem: the id no longer resolves.
        status == HttpURLConnection.HTTP_NOT_FOUND || status == 410 -> RemoteFailure.NotFound
        status == 429 -> RemoteFailure.Throttled(retryAfterSeconds)
        status == 503 -> retryAfterSeconds?.let { RemoteFailure.Throttled(it) } ?: RemoteFailure.ServerError(503)
        status in 500..599 -> RemoteFailure.ServerError(status)
        else -> RemoteFailure.BadRequest
    }

    /**
     * Distinguishes "the device is full" from "the network broke", because the two need different
     * sentences and different user action: `ENOSPC` reaches us as a plain `IOException`.
     */
    fun forIoFailure(message: String?): RemoteFailure {
        val text = message.orEmpty()
        val full = text.contains("ENOSPC", ignoreCase = true) ||
            text.contains("No space left", ignoreCase = true) ||
            text.contains("There is not enough space", ignoreCase = true)
        return if (full) RemoteFailure.NoStorage else RemoteFailure.Unstable
    }
}
