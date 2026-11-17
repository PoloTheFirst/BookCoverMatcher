// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.data

import com.bookcovermatcher.data.onedrive.WorkbookDownloadValidator
import java.io.File
import java.io.InputStream

/** Stages an untrusted document before admitting it to Saved Workbooks. */
object DocumentWorkbookImport {
    const val MAX_BYTES = 256L * 1024 * 1024

    fun import(input: InputStream, name: String, cache: File, library: WorkbookLibrary): ImportResult =
        input.use { source ->
            if (name.substringAfterLast('.', "").lowercase() !in setOf("xlsx", "xlsm")) {
                return ImportResult.Rejected("Choose an .xlsx or .xlsm workbook.")
            }
            val staged = File.createTempFile("picked-workbook-", ".part", cache)
            try {
                staged.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_BYTES) return ImportResult.Rejected("Choose a workbook smaller than 256 MB.")
                        output.write(buffer, 0, count)
                    }
                }
                if (WorkbookDownloadValidator.validate(staged, name) != null) {
                    ImportResult.Rejected("The selected file is not a valid Excel workbook.")
                } else {
                    staged.inputStream().use { library.import(it, name) }
                }
            } finally {
                staged.delete()
            }
        }
}
