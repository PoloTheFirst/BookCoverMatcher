// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/** Original-document provenance only. The saved copy remains independent. Call off the UI thread. */
class LocalWorkbookSource(private val context: Context) {
    private val resolver get() = context.contentResolver

    fun capture(uri: Uri, name: String): LocalOrigin {
        val retained = try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        } catch (_: SecurityException) { false }
        val documentPath = runCatching {
            if (uri.authority == "com.android.externalstorage.documents")
                DocumentsContract.getDocumentId(uri).replaceFirst("primary:", "Internal storage/")
            else null
        }.getOrNull()
        val provider = runCatching {
            context.packageManager.resolveContentProvider(uri.authority ?: "", 0)
                ?.loadLabel(context.packageManager)?.toString()
        }.getOrNull() ?: "Document provider"
        return LocalOrigin(uri.toString(), documentPath ?: "$provider / $name (full path unavailable)", retained)
    }

    fun check(source: LocalOrigin): SourceObservation {
        val uri = Uri.parse(source.uri)
        val retained = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        if (!retained) return SourceObservation(if (source.retained) "Original access revoked · re-link" else "Original access not retained · re-link")
        val text = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) "Linked · original available" else "Original moved/deleted or unavailable"
            } ?: "Unable to check original"
        } catch (_: SecurityException) { "Original access revoked · re-link" }
        catch (_: FileNotFoundException) { "Original moved/deleted or unavailable" }
        catch (_: Exception) { "Unable to check original" }
        return SourceObservation(text, System.currentTimeMillis())
    }

    fun releaseIfUnused(uri: String, entries: List<SavedWorkbook>) {
        if (entries.any { it.local?.uri == uri }) return
        runCatching { resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
}
