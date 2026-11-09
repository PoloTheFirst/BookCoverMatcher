// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.remote

import java.net.URI
import java.net.URLEncoder

/**
 * Every Graph URL this app will build or accept, and the rules for trusting one.
 *
 * Two of the values here are **server-supplied input**, not configuration: an `@odata.nextLink` and a
 * `Location` header. A bearer token is attached to the first kind only after [isTrustedApi] has
 * accepted it, and the second kind is never given a token at all ([GraphRedirect]). Ports and userinfo
 * are rejected too: `https://graph.microsoft.com@evil.example/` has the host `evil.example`, which the
 * host check already catches, but keeping the explicit rules means a future change cannot quietly
 * loosen them.
 *
 * Plain Kotlin/JVM (`java.net` only), so all of it is unit-tested on the desktop.
 */
object GraphUrls {

    const val API_HOST = "graph.microsoft.com"
    private const val BASE = "https://graph.microsoft.com/v1.0"

    /** The API version path every accepted URL must stay inside. */
    private const val API_PATH_PREFIX = "/v1.0/"

    /** Fields asked for on every listing and probe: exactly what the library records, nothing more. */
    const val SELECT_FIELDS = "id,name,folder,file,size,eTag,cTag,lastModifiedDateTime,parentReference"

    /** One page. Graph caps this at 200 for children; asking for the cap reduces round trips. */
    const val PAGE_SIZE = 200

    private const val SELECT_QUERY = "\$select=$SELECT_FIELDS"
    private const val TOP_QUERY = "\$top=$PAGE_SIZE"

    /** Root children, or the next page of them. */
    fun rootChildren(pageToken: String? = null): String =
        withPage("$BASE/me/drive/root/children?$SELECT_QUERY&$TOP_QUERY", pageToken)

    /** Children of one folder, or the next page of them. */
    fun children(folderId: String, pageToken: String? = null): String =
        withPage("$BASE/me/drive/items/${encodeItemId(folderId)}/children?$SELECT_QUERY&$TOP_QUERY", pageToken)

    /** Metadata of one item. */
    fun item(itemId: String): String = "$BASE/me/drive/items/${encodeItemId(itemId)}?$SELECT_QUERY"

    /** The content endpoint; it answers with a redirect to a pre-authenticated URL. */
    fun content(itemId: String): String = "$BASE/me/drive/items/${encodeItemId(itemId)}/content"

    /**
     * A page token **is** a complete URL (`@odata.nextLink`), so it replaces the URL we would build
     * rather than being appended to it. Appending it produced `…?$top=200&https://graph…`, which is
     * exactly the kind of malformed request that looks plausible in a log.
     */
    private fun withPage(base: String, pageToken: String?): String =
        pageToken?.takeIf { it.isNotBlank() }?.trim() ?: base

    /**
     * Percent-encodes a driveItem id for use as a **single path segment**.
     *
     * A Graph id is opaque: it can contain `!`, `=`, spaces and other characters that are significant in
     * a URL path, so it is always encoded and never interpolated raw. `+` is re-encoded because
     * `URLEncoder` emits it for a space, which a path segment would read literally.
     */
    fun encodeItemId(id: String): String {
        require(id.isNotBlank()) { "a driveItem id is never blank" }
        return URLEncoder.encode(id, "UTF-8").replace("+", "%20")
    }

    /**
     * True only for `https` URLs on [API_HOST] inside `/v1.0/`, with no port and no userinfo.
     *
     * This is the gate in front of every place a bearer token is attached, including a pagination URL
     * the server sent us.
     */
    fun isTrustedApi(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.rawUserInfo != null) return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (!uri.host.equals(API_HOST, ignoreCase = true)) return false
        if (uri.port != -1 && uri.port != 443) return false
        return uri.path.orEmpty().startsWith(API_PATH_PREFIX)
    }

    /**
     * The URL to continue with for [pageToken], or `null` when it is not a trusted API URL.
     *
     * A rejected token is reported by the caller as a failure: silently starting from the first page
     * again would show the user a listing that quietly loops.
     */
    fun resolvePageToken(pageToken: String): String? {
        val candidate = pageToken.removePrefix("@odata.nextLink=").trim()
        return if (isTrustedApi(candidate)) candidate else null
    }
}

/**
 * What to do with a redirect the content endpoint answered with.
 *
 * `/content` replies with a 302 to a short-lived, pre-authenticated URL on a storage host. That URL
 * needs no credentials — and must not receive ours: forwarding the bearer token would hand the user's
 * Graph token to a second host, which is exactly the sort of leak that survives in a `Referer` or a
 * proxy log. The plan is therefore data, not a comment: [Plan.FollowWithoutCredentials] is the only
 * "follow" there is, and it carries no token by construction.
 */
object GraphRedirect {

    sealed interface Plan {
        data class FollowWithoutCredentials(val url: String) : Plan
        data class Reject(val failure: RemoteFailure) : Plan
    }

    fun plan(location: String?): Plan {
        val raw = location?.trim().orEmpty()
        // A 3xx with no `Location` is the server breaking its own protocol, not a lost connection
        // (v2.6.0-dev.1 crash-fix-4: this used to be reported as "the connection failed").
        if (raw.isEmpty()) return Plan.Reject(RemoteFailure.UnexpectedResponse)
        val uri = runCatching { URI(raw) }.getOrNull() ?: return Plan.Reject(RemoteFailure.BadRequest)
        if (uri.rawUserInfo != null) return Plan.Reject(RemoteFailure.BadRequest)
        if (!uri.scheme.equals("https", ignoreCase = true)) return Plan.Reject(RemoteFailure.BadRequest)
        if (uri.host.isNullOrBlank()) return Plan.Reject(RemoteFailure.BadRequest)
        // A "redirect" back to the API host is not a download target; following it with no credentials
        // would just loop, and following it *with* credentials is what this object exists to prevent.
        if (uri.host.equals(GraphUrls.API_HOST, ignoreCase = true)) return Plan.Reject(RemoteFailure.UnexpectedResponse)
        return Plan.FollowWithoutCredentials(raw)
    }
}
