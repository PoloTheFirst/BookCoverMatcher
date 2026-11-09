// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.remote

import java.net.URI

/**
 * The minimum safe trace of a OneDrive operation (v2.6.0-dev.1 crash-fix-5).
 *
 * Why this exists: every earlier attempt to diagnose "OneDrive folders do not open" ended in inference,
 * because the app deliberately logged nothing at all. The stage, the HTTP status and Graph's own error
 * code are enough to name the failing request, and none of them is a secret. What is never recorded:
 * headers, bearer tokens, authorization codes, callback URLs, `.env` values, response bodies, account
 * identifiers, folder or workbook names.
 *
 * The sink is a plain lambda so tests can capture lines without an Android runtime, and the app installs
 * `android.util.Log` in `MainViewModel`. [describe] is pure, which is what makes "this line contains no
 * secret" an assertion rather than a promise.
 */
object RemoteDiagnostics {

    /** Installed by the app; a no-op by default so JVM tests and previews stay silent. */
    @Volatile
    var sink: (String) -> Unit = {}

    /** One line per operation stage. Callers pass only values from [safe] or from Graph itself. */
    fun log(
        stage: String,
        method: String = "GET",
        url: String? = null,
        status: Int? = null,
        graphErrorCode: String? = null,
        requestId: String? = null,
        failure: RemoteFailure? = null,
        detail: String? = null,
    ) {
        val line = describe(stage, method, url, status, graphErrorCode, requestId, failure, detail)
        runCatching { sink(line) }
    }

    /** The line itself: stable shape, no query string, no item id, no body. */
    fun describe(
        stage: String,
        method: String = "GET",
        url: String? = null,
        status: Int? = null,
        graphErrorCode: String? = null,
        requestId: String? = null,
        failure: RemoteFailure? = null,
        detail: String? = null,
    ): String = buildString {
        append("stage=").append(stage)
        append(" method=").append(method)
        url?.let { append(" path=").append(sanitizePath(it)) }
        status?.let { append(" status=").append(it) }
        graphErrorCode?.takeIf { it.isNotBlank() }?.let { append(" code=").append(clean(it, 64)) }
        requestId?.takeIf { it.isNotBlank() }?.let { append(" requestId=").append(clean(it, 64)) }
        failure?.let { append(" failure=").append(it.javaClass.simpleName) }
        // A type name only (e.g. `MsalClientException`): never a message, which can contain account data.
        detail?.takeIf { it.isNotBlank() }?.let { append(" detail=").append(clean(it, 240)) }
    }

    /**
     * `https://graph.microsoft.com/v1.0/me/drive/items/<id>/children?$select=…` becomes
     * `https://graph.microsoft.com/v1.0/me/drive/items/{id}/children`.
     *
     * The query is dropped entirely: it carries `$select`, page tokens and, on other endpoints, the
     * pre-authenticated storage URL. A driveItem id is opaque, but it is still an identifier that belongs
     * to the user's drive, so it is replaced by a placeholder rather than logged.
     */
    fun sanitizePath(url: String?): String {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return "-"
        val uri = runCatching { URI(raw) }.getOrNull() ?: return "-"
        val host = uri.host ?: return "-"
        val scheme = uri.scheme?.lowercase() ?: "https"
        val path = uri.path.orEmpty().ifEmpty { "/" }
        return "$scheme://$host${path.replace(ITEM_ID_SEGMENT, "/items/{id}/")}"
    }

    /** `/items/<anything>/` → `/items/{id}/`. Anything else is left exactly as it is. */
    private val ITEM_ID_SEGMENT = Regex("/items/[^/]+/")

    /**
     * A single Graph-supplied token (an error code or a request id), bounded and stripped of anything that
     * is not printable, so a hostile or odd response cannot inject newlines or long text into the log.
     */
    private fun clean(value: String, max: Int): String =
        value.filter { it.code in 0x20..0x7E }.take(max)
}
