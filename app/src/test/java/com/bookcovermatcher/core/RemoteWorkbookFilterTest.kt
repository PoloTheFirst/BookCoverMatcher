// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core

import com.bookcovermatcher.data.onedrive.RemoteWorkbookFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which OneDrive files may be offered as importable workbooks (Phase 3). */
class RemoteWorkbookFilterTest {

    @Test fun bothSupportedWorkbookFormatsAreAccepted() {
        assertTrue(RemoteWorkbookFilter.isSupported("covers.xlsx"))
        assertTrue(RemoteWorkbookFilter.isSupported("covers.xlsm"))
        assertTrue("case does not matter", RemoteWorkbookFilter.isSupported("COVERS.XLSX"))
        assertTrue(RemoteWorkbookFilter.isSupported("同人誌 2026.xlsx"))
    }

    @Test fun theExtensionIsWhatFollowsTheLastDot() {
        assertFalse("a partial file is not a workbook", RemoteWorkbookFilter.isSupported("covers.xlsx.part"))
        assertFalse(RemoteWorkbookFilter.isSupported("covers.xlsx.tmp"))
        assertFalse(RemoteWorkbookFilter.isSupported("covers.txt"))
        assertFalse(RemoteWorkbookFilter.isSupported("covers"))
        assertFalse(RemoteWorkbookFilter.isSupported("covers."))
        assertFalse(RemoteWorkbookFilter.isSupported(""))
        assertFalse(RemoteWorkbookFilter.isSupported("   "))
    }

    @Test fun theOlderBinaryFormatIsNotSupported() {
        // .xls is not a ZIP package and the library has never accepted it.
        assertFalse(RemoteWorkbookFilter.isSupported("covers.xls"))
    }

    @Test fun foldersAreAlwaysOfferedAndFilesOnlyWhenSupported() {
        assertTrue(RemoteWorkbookFilter.accept("Catalogue", isFolder = true))
        assertTrue(RemoteWorkbookFilter.accept("covers.xlsx", isFolder = false))
        assertFalse(RemoteWorkbookFilter.accept("readme.md", isFolder = false))
        assertTrue("a folder named like a text file is still a folder", RemoteWorkbookFilter.accept("notes.txt", isFolder = true))
    }
}
