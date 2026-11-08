// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core

import com.bookcovermatcher.core.remote.RemoteFailure
import com.bookcovermatcher.data.onedrive.WorkbookDownloadValidator
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate between "bytes arrived" and "bytes may enter the library" (Phase 3).
 *
 * The rules are the library's own — the same extension set, the same ZIP signature, the same
 * `requireWorkbook` check `MainViewModel` runs on a picked file — so a download is never trusted more
 * than a local file.
 */
class WorkbookDownloadValidatorTest {

    private val dir: File = Files.createTempDirectory("download-validator").toFile()

    @After fun cleanUp() {
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

    private fun write(name: String, bytes: ByteArray): File = File(dir, name).apply { writeBytes(bytes) }

    /** A minimal but genuine OOXML package: the parts `requireWorkbook` insists on. */
    private fun workbookBytes(sheets: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            put("[Content_Types].xml", "<Types/>")
            if (sheets) {
                put("xl/workbook.xml", TestWorkbooks.workbook("Sheet1" to 1))
                put("xl/_rels/workbook.xml.rels", TestWorkbooks.workbookRels(1))
                put("xl/worksheets/sheet1.xml", "<worksheet/>")
            }
        }
        return out.toByteArray()
    }

    @Test fun aRealWorkbookPasses() {
        val file = write("covers.xlsx", workbookBytes())

        assertNull(WorkbookDownloadValidator.validate(file, "covers.xlsx"))
    }

    @Test fun aZipThatIsNotAWorkbookIsRejected() {
        val file = write("covers.xlsx", workbookBytes(sheets = false))

        assertEquals(RemoteFailure.InvalidWorkbook, WorkbookDownloadValidator.validate(file, "covers.xlsx"))
    }

    @Test fun bytesWithoutAZipSignatureAreRejected() {
        val file = write("covers.xlsx", "<html>Sign in to OneDrive</html>".toByteArray())

        assertEquals(RemoteFailure.InvalidWorkbook, WorkbookDownloadValidator.validate(file, "covers.xlsx"))
    }

    @Test fun anEmptyFileIsRejected() {
        val file = write("covers.xlsx", ByteArray(0))

        assertEquals(RemoteFailure.InvalidWorkbook, WorkbookDownloadValidator.validate(file, "covers.xlsx"))
    }

    @Test fun theWrongExtensionIsRejectedEvenWhenTheBytesAreAWorkbook() {
        // A destination naming problem must be caught here, not by the library later.
        val file = write("covers.xlsx", workbookBytes())

        assertEquals(RemoteFailure.InvalidWorkbook, WorkbookDownloadValidator.validate(file, "covers.zip"))
        assertEquals(RemoteFailure.InvalidWorkbook, WorkbookDownloadValidator.validate(file, "covers.xls"))
    }

    @Test fun aMissingFileIsRejectedInsteadOfThrowing() {
        assertEquals(
            RemoteFailure.InvalidWorkbook,
            WorkbookDownloadValidator.validate(File(dir, "not-there.xlsx"), "not-there.xlsx"),
        )
    }

    @Test fun aTruncatedPackageIsRejected() {
        val bytes = workbookBytes()
        val truncated = write("covers.xlsx", bytes.copyOfRange(0, bytes.size / 2))

        assertTrue(
            "a half-written zip must not pass",
            WorkbookDownloadValidator.validate(truncated, "covers.xlsx") != null,
        )
    }
}
