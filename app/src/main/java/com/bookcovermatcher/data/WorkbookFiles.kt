package com.bookcovermatcher.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest

/** A spreadsheet copied into the app's cache so it can be read as a random-access zip. */
class StagedWorkbook(val file: File, val displayName: String, val sizeBytes: Long, val sha256: String)

object WorkbookFiles {

    fun displayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) c.getString(i)?.let { if (it.isNotBlank()) return it }
                }
            }
        } catch (e: Exception) {
            // fall through to the URI's last segment
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "workbook"
    }

    /**
     * Copies [uri] to `cache/workbook/current.xlsx` and hashes it on the way (the hash is the
     * identity of the workbook for the on-disk index cache). Only one workbook is kept at a time.
     */
    fun stage(context: Context, uri: Uri): StagedWorkbook {
        val dir = File(context.cacheDir, "workbook").also { it.mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "current.xlsx")
        val digest = MessageDigest.getInstance("SHA-256")

        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the selected file.")
        var total = 0L
        DigestInputStream(input, digest).use { src ->
            target.outputStream().use { dst ->
                val buf = ByteArray(128 * 1024)
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    dst.write(buf, 0, n)
                    total += n
                }
            }
        }
        if (total == 0L) {
            target.delete()
            throw IOException("The selected file is empty.")
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        return StagedWorkbook(target, displayName(context, uri), total, sha)
    }
}
