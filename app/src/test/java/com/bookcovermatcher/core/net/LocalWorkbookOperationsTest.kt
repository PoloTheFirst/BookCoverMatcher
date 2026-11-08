// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.net

import com.bookcovermatcher.data.ImportResult
import com.bookcovermatcher.data.WorkbookLibrary
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule from `ConnectivityMonitor`'s contract, asserted instead of asserted-in-a-comment: **no local
 * workbook operation may depend on connectivity.**
 *
 * A finished OneDrive feature makes it very easy to gate the wrong thing — to ask "are we online?" in
 * the shared open/import path and quietly break opening a saved workbook on a plane. The structural
 * guard below (the library cannot even hold a monitor) plus the round trip below (a full import and
 * removal while the monitor is offline) are what keep that honest until the Android call sites exist.
 *
 * The Android-side call sites live in `MainViewModel` and cannot run on the desktop JVM; they are
 * covered by inspection here and by the instrumented tests in Phase 7.
 */
class LocalWorkbookOperationsTest {

    private val dir: File = Files.createTempDirectory("offline-workbook-library").toFile()

    @After fun cleanUp() {
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

    /** Bytes that pass the import validation (ZIP signature) with distinguishable content. */
    private fun workbookBytes(tag: String): ByteArray =
        byteArrayOf(0x50, 0x4B, 0x03, 0x04) + "[Content_Types].xml $tag".toByteArray()

    @Test fun theLibraryCannotHoldAConnectivityMonitor() {
        val monitorType = ConnectivityMonitor::class.java

        val fields = WorkbookLibrary::class.java.declaredFields.map { it.type }
        assertTrue(
            "WorkbookLibrary must not hold connectivity state, but it has: $fields",
            fields.none { monitorType.isAssignableFrom(it) },
        )

        val parameters = WorkbookLibrary::class.java.declaredConstructors.flatMap { it.parameterTypes.toList() }
        assertTrue(
            "WorkbookLibrary must not be constructed with connectivity state, but it takes: $parameters",
            parameters.none { monitorType.isAssignableFrom(it) },
        )
    }

    @Test fun aSavedWorkbookIsImportedAndRemovedWhileOffline() {
        val monitor = FakeConnectivityMonitor(online = false)
        assertFalse("the fixture must start offline", monitor.isUsableNow())

        val library = WorkbookLibrary(dir)
        val bytes = workbookBytes("offline")
        val added = library.import(ByteArrayInputStream(bytes), "offline.xlsx", now = 1_000L)

        assertTrue(added is ImportResult.Added)
        val entry = (added as ImportResult.Added).workbook
        assertArrayEquals("the saved copy must be byte-identical", bytes, library.fileOf(entry).readBytes())
        assertEquals(1, library.list().size)
        assertEquals(bytes.size.toLong(), library.bytes())

        val (removed, fileDeleted) = library.remove(entry.id)

        assertTrue("removal is a local operation and must not be blocked", removed)
        assertTrue(fileDeleted)
        assertTrue(library.list().isEmpty())
    }

    @Test fun anOfflineMonitorDoesNotPreventReReadingWhatIsAlreadySaved() {
        val monitor = FakeConnectivityMonitor(online = false)
        val library = WorkbookLibrary(dir)
        val bytes = workbookBytes("again")
        val entry = (library.import(ByteArrayInputStream(bytes), "again.xlsx", now = 2_000L)
            as ImportResult.Added).workbook

        // Exactly what "open a saved workbook" does: resolve the entry, read the app's own file, then
        // record that it was opened. None of it may depend on the network.
        val byId = library.byId(entry.id)
        assertEquals(entry.id, byId?.id)
        assertArrayEquals(bytes, library.fileOf(byId!!).readBytes())
        assertEquals(3_000L, library.touch(entry.id, now = 3_000L)?.lastOpenedAt)

        assertFalse("the preflight state must be irrelevant here", monitor.isUsableNow())
    }
}
