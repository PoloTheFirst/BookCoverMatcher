// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.remote

import com.bookcovermatcher.core.wishlist.MiniJson
import java.time.OffsetDateTime

/**
 * The Graph response shapes this app reads, parsed with the app's existing [MiniJson] so no JSON
 * library is added for four documents.
 *
 * Everything is defensive on purpose: a missing `size`, `eTag` or `lastModifiedDateTime` becomes
 * `null` rather than `0` or an empty string, because "OneDrive did not say" must stay distinguishable
 * from a real value. An entry with no id or no name is dropped instead of being shown as a blank row.
 *
 * Plain Kotlin/JVM, so the parsing is unit-tested against recorded payloads without a network.
 */
object GraphJson {

    /** Parses a `…/children` response. [accept] decides which *files* are kept (folders are always kept). */
    fun listing(folderId: String?, body: String, accept: (String) -> Boolean): RemoteResult<RemoteListing> {
        val root = parseObject(body) ?: return RemoteResult.Err(RemoteFailure.Unstable)
        if (root["error"] != null) return RemoteResult.Err(RemoteFailure.BadRequest)
        val values = root["value"] as? List<*> ?: return RemoteResult.Err(RemoteFailure.Unstable)
        val folders = mutableListOf<RemoteItem>()
        val files = mutableListOf<RemoteItem>()
        for (raw in values) {
            val item = parseItem(raw) ?: continue
            if (item.isFolder) folders += item else if (accept(item.name)) files += item
        }
        return RemoteResult.Ok(
            RemoteListing(
                folderId = folderId,
                folders = folders,
                files = files,
                nextPageToken = nextPageToken(root),
            ),
        )
    }

    /** Parses one driveItem document (a probe). */
    fun item(body: String): RemoteResult<RemoteItem> {
        val root = parseObject(body) ?: return RemoteResult.Err(RemoteFailure.Unstable)
        if (root["error"] != null) return RemoteResult.Err(RemoteFailure.NotFound)
        return parseItem(root)?.let { RemoteResult.Ok(it) }
            ?: RemoteResult.Err(RemoteFailure.Unstable)
    }

    /** The `@odata.nextLink` of a page, or null at the end of the listing. */
    fun nextPageToken(root: Map<*, *>): String? =
        (root["@odata.nextLink"] as? String)?.takeIf { it.isNotBlank() }

    private fun parseObject(body: String): Map<*, *>? = try {
        MiniJson.parse(body) as? Map<*, *>
    } catch (e: Exception) {
        null
    }

    private fun parseItem(raw: Any?): RemoteItem? {
        val map = raw as? Map<*, *> ?: return null
        val id = map["id"] as? String ?: return null
        val name = map["name"] as? String ?: return null
        val parent = map["parentReference"] as? Map<*, *>
        return RemoteItem(
            id = id,
            name = name,
            // A driveItem is a folder when it carries a `folder` facet, a file when it carries `file`.
            isFolder = map["folder"] is Map<*, *>,
            sizeBytes = (map["size"] as? Double)?.toLong(),
            eTag = map["eTag"] as? String,
            cTag = map["cTag"] as? String,
            lastModified = parseTime(map["lastModifiedDateTime"] as? String),
            path = (parent?.get("path") as? String).orEmpty(),
        )
    }

    /** `2026-11-08T12:34:56Z` → epoch millis; anything unparsable stays `null`. */
    private fun parseTime(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
    }
}
