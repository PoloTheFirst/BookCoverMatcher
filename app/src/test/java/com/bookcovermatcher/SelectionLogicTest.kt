package com.bookcovermatcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The selection rules the Wishlist UI depends on: multiple rows stay selected, Select all only takes
 * visible rows, Clear empties the set, and a refresh reconciles away ids that no longer exist.
 */
class SelectionLogicTest {

    @Test fun selectingThreeBookmarksKeepsAllThree() {
        var ids = SelectionLogic.clear()
        listOf("a", "b", "c").forEach { ids = SelectionLogic.toggle(ids, it) }

        assertEquals(3, ids.size)
        assertEquals(setOf("a", "b", "c"), ids)
    }

    @Test fun selectingOneItemDoesNotClearTheOthers() {
        val afterTwo = SelectionLogic.toggle(SelectionLogic.toggle(SelectionLogic.clear(), "a"), "b")
        val afterThree = SelectionLogic.toggle(afterTwo, "c")

        assertTrue("a" in afterThree)
        assertTrue("b" in afterThree)
        assertTrue("c" in afterThree)
    }

    @Test fun tappingASelectedRowDeselectsOnlyThatRow() {
        val ids = SelectionLogic.toggle(setOf("a", "b", "c"), "b")

        assertEquals(setOf("a", "c"), ids)
    }

    @Test fun clearSelectionLeavesNothingSelected() {
        assertTrue(SelectionLogic.clear().isEmpty())
        assertTrue(SelectionLogic.clear().isEmpty())
    }

    @Test fun selectAllTakesEveryVisibleRowOnce() {
        val ids = SelectionLogic.selectAll(listOf("a", "b", "b", "c"))

        assertEquals(setOf("a", "b", "c"), ids)
    }

    @Test fun selectAllNeverSelectsABlankId() {
        assertEquals(setOf("a"), SelectionLogic.selectAll(listOf("a", "", "   ")))
    }

    @Test fun aSelectionIsReconciledAgainstTheVisibleList() {
        val reconciled = SelectionLogic.reconcile(setOf("a", "gone", "c"), listOf("a", "b", "c"))

        assertEquals(setOf("a", "c"), reconciled)
    }

    @Test fun reconciliationCanEmptyTheSelectionAfterADelete() {
        assertTrue(SelectionLogic.reconcile(setOf("a", "b"), emptyList()).isEmpty())
    }

    @Test fun eligibleIdsAreDeduplicatedAndTrimmedOfBlanks() {
        assertEquals(listOf("a", "b"), SelectionLogic.eligible(BatchKind.FOLDERS, listOf("a", "", "b", "a")))
    }

    @Test fun moveIsOnlyOfferedWhereTheModelHasADestination() {
        assertFalse("a folder has no parent in this model", SelectionLogic.canMove(BatchKind.FOLDERS))
        assertFalse("moving a bookmark would un-bookmark it", SelectionLogic.canMove(BatchKind.BOOKMARKS))
        assertTrue(SelectionLogic.canMove(BatchKind.SESSIONS))
        assertTrue(SelectionLogic.canMove(BatchKind.ITEMS))
        assertTrue(SelectionLogic.canMove(BatchKind.SESSION_ITEMS))
    }

    @Test fun copyAndDeleteAreOfferedForEverySelectableKind() {
        BatchKind.values().forEach { kind ->
            assertTrue("copy for $kind", SelectionLogic.canCopy(kind))
            assertTrue("delete for $kind", SelectionLogic.canDelete(kind))
        }
    }

    @Test fun everyKindHasANounAndAnExplanation() {
        BatchKind.values().forEach { kind ->
            assertTrue(SelectionLogic.noun(kind).isNotBlank())
            assertTrue(SelectionLogic.hint(kind).isNotBlank())
        }
        assertEquals("bookmark(s)", SelectionLogic.noun(BatchKind.BOOKMARKS))
        assertEquals("folder(s)", SelectionLogic.noun(BatchKind.FOLDERS))
        assertEquals("session(s)", SelectionLogic.noun(BatchKind.SESSIONS))
        assertEquals("item(s)", SelectionLogic.noun(BatchKind.ITEMS))
        assertEquals("result(s)", SelectionLogic.noun(BatchKind.SESSION_ITEMS))
    }

    @Test fun theBookmarkWorkflowKeepsEveryStepConsistent() {
        // Select three, copy them (selection survives until the batch clears it), then reconcile
        // after the destination list is reloaded.
        var ids = SelectionLogic.selectAll(listOf("bm1", "bm2", "bm3"))
        assertEquals(3, ids.size)
        ids = SelectionLogic.reconcile(ids, listOf("bm1", "bm2", "bm3"))
        assertEquals(3, ids.size)
        ids = SelectionLogic.clear()
        assertTrue(ids.isEmpty())
    }
}