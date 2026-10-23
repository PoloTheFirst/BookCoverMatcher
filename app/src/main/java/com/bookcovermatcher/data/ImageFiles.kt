package com.bookcovermatcher.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Display names for images chosen from the device.
 *
 * The picker hands back an opaque `content://` URI; the human-readable name (with its extension) has
 * to come from the provider through `OpenableColumns.DISPLAY_NAME`. [labelFor] is the pure part and
 * is unit-tested; [displayName] is the one Android call and returns null when the provider has no
 * name, so the caller can fall back instead of inventing something.
 */
object ImageFiles {

    /** The provider's own display name, or null when it has none (or the query fails). */
    fun displayName(context: Context, uri: Uri): String? {
        val name = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val index = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) c.getString(index) else null
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
        return name?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * The label the status line shows.
     *
     * Order: the provider's display name, then the URI's last path segment, then a MIME-based name.
     * An extension is added only when the chosen name has none and the MIME type tells us which one
     * it is — the point is to show `book_cover.jpg`, never a bare opaque id.
     */
    fun labelFor(resolvedName: String?, lastPathSegment: String?, mimeType: String?): String {
        val extension = extensionFor(mimeType)
        val candidate = listOfNotNull(
            resolvedName?.trim()?.takeIf { it.isNotEmpty() },
            lastPathSegment?.trim()?.substringAfterLast('/')?.takeIf { it.isNotEmpty() },
        ).firstOrNull()

        if (candidate == null) return if (extension != null) "image.$extension" else "selected image"
        if (extension == null || candidate.contains('.')) return candidate
        return "$candidate.$extension"
    }

    /** `image/jpeg` → `jpg`, `image/png` → `png`, `image/webp` → `webp`; null for anything else. */
    fun extensionFor(mimeType: String?): String? = when (mimeType?.trim()?.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/heic", "image/heif" -> "heic"
        else -> null
    }
}
