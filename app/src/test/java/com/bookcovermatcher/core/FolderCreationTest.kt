package com.bookcovermatcher.core

import com.bookcovermatcher.core.wishlist.FolderCreation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: `+ New folder` opens the dialog only. The folder is created exactly once, on Save, and
 * never by opening, cancelling, dismissing or repeating a tap.
 */
class FolderCreationTest {

    @Test fun openingTheDialogCreatesNothing() {
        val created = ArrayList<String>()
        val flow = FolderCreation { created.add(it) }

        flow.begin()

        assertTrue("the dialog is pending", flow.pending)
        assertEquals("no folder may exist before Save", 0, created.size)
    }

    @Test fun cancellingOrDismissingCreatesNothing() = runBlocking {
        val created = ArrayList<String>()
        val flow = FolderCreation { created.add(it) }
        flow.begin()

        flow.cancel()

        assertFalse(flow.pending)
        assertFalse("a cancelled dialog has nothing to confirm", flow.confirm("Books"))
        assertEquals(0, created.size)
    }

    @Test fun savingCreatesExactlyOneFolderWithTheTrimmedName() = runBlocking {
        val created = ArrayList<String>()
        val flow = FolderCreation { created.add(it) }
        flow.begin()

        assertTrue(flow.confirm("  Books  "))

        assertEquals(listOf("Books"), created)
        assertFalse("the dialog closes after a successful save", flow.pending)
    }

    @Test fun aBlankNameIsRejectedAndTheDialogStaysOpen() = runBlocking {
        val created = ArrayList<String>()
        val flow = FolderCreation { created.add(it) }
        flow.begin()

        assertFalse(flow.confirm("   "))
        assertEquals(0, created.size)
        assertTrue("the user must be able to correct the name", flow.pending)
    }

    @Test fun repeatedSaveTapsCreateOnlyOneFolder() = runBlocking {
        val created = ArrayList<String>()
        val flow = FolderCreation { created.add(it) }
        flow.begin()

        assertTrue(flow.confirm("Books"))
        assertFalse("a second tap is not a second folder", flow.confirm("Books"))
        assertFalse(flow.confirm("Other"))

        assertEquals(listOf("Books"), created)
    }

    @Test fun confirmWithoutOpeningTheDialogDoesNothing() = runBlocking {
        val created = ArrayList<String>()
        val flow = FolderCreation { created.add(it) }

        assertFalse(flow.confirm("Books"))
        assertEquals(0, created.size)
    }

    @Test fun everyNewFolderNeedsItsOwnDialog() = runBlocking {
        val created = ArrayList<String>()
        val flow = FolderCreation { created.add(it) }

        flow.begin()
        flow.confirm("First")
        flow.begin()
        flow.confirm("Second")

        assertEquals(listOf("First", "Second"), created)
    }

    @Test fun aStorageFailureCanBeRetriedWithoutADuplicate() = runBlocking {
        var attempts = 0
        val flow = FolderCreation {
            attempts++
            if (attempts == 1) throw IllegalStateException("disk full")
        }
        flow.begin()

        runCatching { flow.confirm("Books") }
        assertEquals(1, attempts)
        assertTrue("the dialog stays open after a failure", flow.pending)

        assertTrue(flow.confirm("Books"))
        assertEquals(2, attempts)
    }
}
