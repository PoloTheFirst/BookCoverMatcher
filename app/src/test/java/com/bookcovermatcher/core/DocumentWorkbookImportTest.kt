// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.core

import com.bookcovermatcher.data.DocumentWorkbookImport
import com.bookcovermatcher.data.ImportResult
import com.bookcovermatcher.data.WorkbookLibrary
import java.io.ByteArrayInputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DocumentWorkbookImportTest {
    @Test fun rejectsDisguisedZipAndRemovesStagingFile() {
        val root = Files.createTempDirectory("picker-test").toFile()
        try {
            val cache = root.resolve("cache").apply { mkdir() }
            val library = WorkbookLibrary(root.resolve("library"))
            val result = DocumentWorkbookImport.import(ByteArrayInputStream(byteArrayOf(80, 75, 3, 4)), "bad.xlsx", cache, library)
            assertTrue(result is ImportResult.Rejected)
            assertEquals(0, cache.listFiles()!!.size)
            assertTrue(library.list().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun unsupportedExtensionClosesProviderStream() {
        val root = Files.createTempDirectory("picker-test").toFile()
        var closed = false
        val input = object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() { closed = true; super.close() }
        }
        try {
            assertTrue(DocumentWorkbookImport.import(input, "not-a-workbook.pdf", root, WorkbookLibrary(root.resolve("library"))) is ImportResult.Rejected)
            assertTrue(closed)
        } finally { root.deleteRecursively() }
    }
}
