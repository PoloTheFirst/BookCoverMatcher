// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.data.onedrive

import com.bookcovermatcher.core.excel.JavaZipSource
import com.bookcovermatcher.core.excel.WorkbookImageExtractor
import com.bookcovermatcher.core.remote.RemoteFailure
import java.io.File
import java.util.zip.ZipFile

/**
 * The checks a downloaded file must pass before it may touch the library (v2.6.0-dev.1 Phase 3).
 *
 * These are **the library's own rules**, not a second opinion: the extension set is
 * `WorkbookLibrary.SUPPORTED` (through [RemoteWorkbookFilter]), the signature is the ZIP magic the
 * import path checks, and the package is opened with the same `JavaZipSource` +
 * [WorkbookImageExtractor.requireWorkbook] pair that `MainViewModel` uses to fail early on a picked
 * file. A workbook that arrives from OneDrive is therefore no more trusted than one picked from disk.
 *
 * Returns `null` when the file is usable, or the failure to report. It never throws: a corrupt
 * download is a message, not a crash.
 */
object WorkbookDownloadValidator {

    fun validate(file: File, displayName: String): RemoteFailure? {
        if (!RemoteWorkbookFilter.isSupported(displayName)) return RemoteFailure.InvalidWorkbook
        if (!file.isFile) return RemoteFailure.InvalidWorkbook
        if (file.length() <= 0L) return RemoteFailure.InvalidWorkbook
        if (!hasZipSignature(file)) return RemoteFailure.InvalidWorkbook
        val zip = try {
            ZipFile(file)
        } catch (e: Exception) {
            return RemoteFailure.InvalidWorkbook
        }
        return try {
            WorkbookImageExtractor.requireWorkbook(JavaZipSource(zip))
            null
        } catch (e: Exception) {
            // Not an OOXML package: `requireWorkbook` throws for a missing [Content_Types].xml or a
            // missing workbook part, which is exactly what a truncated or HTML error page looks like.
            RemoteFailure.InvalidWorkbook
        } finally {
            runCatching { zip.close() }
        }
    }

    private fun hasZipSignature(file: File): Boolean = try {
        file.inputStream().use { input ->
            val head = ByteArray(2)
            input.read(head) == 2 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()
        }
    } catch (e: Exception) {
        false
    }
}
