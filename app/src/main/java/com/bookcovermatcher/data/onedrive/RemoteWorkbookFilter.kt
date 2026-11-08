// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.data.onedrive

import com.bookcovermatcher.data.WorkbookLibrary

/**
 * Which remote files the OneDrive browser may offer as importable.
 *
 * The rule is the library's own set (`WorkbookLibrary.SUPPORTED`), not a second list, so a file the
 * picker shows is a file `WorkbookLibrary.import` will accept. Everything else stays out of the list
 * rather than being shown and then rejected.
 *
 * `book.xlsx.tmp`, `book.txt` and a bare `book` are all rejected: the extension is what is after the
 * **last** dot, and it must match a supported format exactly (case-insensitively).
 */
object RemoteWorkbookFilter {

    fun isSupported(fileName: String): Boolean {
        val trimmed = fileName.trim()
        if (trimmed.isEmpty() || trimmed.endsWith(".")) return false
        val extension = trimmed.substringAfterLast('.', "").lowercase()
        return extension.isNotEmpty() && extension in WorkbookLibrary.SUPPORTED
    }

    /** Folder picker entry: every folder is listed, workbook or not. */
    fun accept(name: String, isFolder: Boolean): Boolean = isFolder || isSupported(name)
}
