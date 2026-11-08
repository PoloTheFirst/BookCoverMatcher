// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Metadata comparison (Phase 3, extended in Phase 5): does the OneDrive copy still match the saved one?
 *
 * The interesting cases are the *uncertain* ones — the rule must refuse to claim "unchanged" when it
 * cannot prove it, because Phase 5 turns that refusal into a statement ("could not tell") instead of a
 * silent pass. The second family of cases is the evidence *precedence*: an ETag or a cTag is proof, a
 * timestamp is good evidence, and a size can only ever prove that something changed.
 */
class RemoteChangeTest {

    private fun item(
        eTag: String? = null,
        cTag: String? = null,
        modified: Long? = null,
        size: Long? = null,
    ) = RemoteItem(
        id = "A1",
        name = "covers.xlsx",
        isFolder = false,
        sizeBytes = size,
        eTag = eTag,
        cTag = cTag,
        lastModified = modified,
    )

    @Test fun anEqualETagMeansUnchanged() {
        assertEquals(
            RemoteChange.UNCHANGED,
            RemoteChangeDetector.compare("\"{AAA},3\"", null, null, null, item(eTag = "\"{AAA},3\"")),
        )
    }

    @Test fun aDifferentETagMeansChanged() {
        assertEquals(
            RemoteChange.CHANGED,
            RemoteChangeDetector.compare("\"{AAA},3\"", null, null, null, item(eTag = "\"{AAA},4\"")),
        )
    }

    @Test fun theETagWinsOverTimeAndSize() {
        // Time and size agree, but the ETag does not: the content changed.
        assertEquals(
            RemoteChange.CHANGED,
            RemoteChangeDetector.compare(
                "\"{AAA},3\"", null, 1_000L, 10L,
                item(eTag = "\"{AAA},9\"", modified = 1_000L, size = 10L),
            ),
        )
    }

    @Test fun anETagIsNeverComparedWithACTag() {
        // A cTag and an ETag are different kinds of number. Treating "no ETag on one side" as a
        // comparison would report a change on every probe of a file that only has a cTag.
        assertEquals(
            "falls through to the evidence that does exist",
            RemoteChange.UNCHANGED,
            RemoteChangeDetector.compare("\"{AAA},3\"", null, 5_000L, 10L, item(cTag = "C1", modified = 5_000L)),
        )
        assertEquals(
            RemoteChange.UNKNOWN,
            RemoteChangeDetector.compare("\"{AAA},3\"", null, null, null, item(cTag = "C1")),
        )
    }

    @Test fun cTagsAreComparedWithEachOther() {
        assertEquals(
            RemoteChange.UNCHANGED,
            RemoteChangeDetector.compare(null, "C2", null, null, item(cTag = "C2")),
        )
        assertEquals(
            RemoteChange.CHANGED,
            RemoteChangeDetector.compare(null, "C1", null, null, item(cTag = "C2")),
        )
    }

    @Test fun anETagPairIsStrongerThanACTagPair() {
        assertEquals(
            RemoteChange.UNCHANGED,
            RemoteChangeDetector.compare("\"{AAA},3\"", "C1", null, null, item(eTag = "\"{AAA},3\"", cTag = "C9")),
        )
    }

    @Test fun withoutTagsTheModifiedTimeIsCompared() {
        assertEquals(RemoteChange.UNCHANGED, RemoteChangeDetector.compare(null, null, 5_000L, 10L, item(modified = 5_000L, size = 10L)))
        assertEquals(RemoteChange.CHANGED, RemoteChangeDetector.compare(null, null, 5_000L, 10L, item(modified = 6_000L, size = 10L)))
    }

    @Test fun anEqualTimeWithADifferentSizeStillMeansChanged() {
        // Two different sizes cannot be the same bytes, whatever the clock says.
        assertEquals(
            RemoteChange.CHANGED,
            RemoteChangeDetector.compare(null, null, 5_000L, 10L, item(modified = 5_000L, size = 99L)),
        )
    }

    @Test fun aSizeAloneCanOnlyProveAChange() {
        // Different sizes are evidence; equal sizes are not, so the answer is "cannot tell" rather than
        // "unchanged" — a re-save can keep the size.
        assertEquals(
            RemoteChange.CHANGED,
            RemoteChangeDetector.compare(null, null, null, 10L, item(size = 11L)),
        )
        assertEquals(
            RemoteChange.UNKNOWN,
            RemoteChangeDetector.compare(null, null, null, 10L, item(size = 10L)),
        )
    }

    @Test fun nothingComparableIsUnknownNeverUnchanged() {
        assertEquals(RemoteChange.UNKNOWN, RemoteChangeDetector.compare(null, null, null, null, item()))
        assertEquals(
            "a recorded ETag but a probe without one falls through, it does not guess",
            RemoteChange.UNKNOWN,
            RemoteChangeDetector.compare("\"{AAA},3\"", null, null, null, item()),
        )
        assertEquals(
            "a blank tag counts as absent",
            RemoteChange.UNKNOWN,
            RemoteChangeDetector.compare("   ", "  ", null, null, item(eTag = "   ", cTag = "  ")),
        )
    }

    @Test fun everyAnswerIsOneOfTheThreeStates() {
        val answers = listOf(
            RemoteChangeDetector.compare("a", null, null, null, item(eTag = "a")),
            RemoteChangeDetector.compare("a", null, null, null, item(eTag = "b")),
            RemoteChangeDetector.compare(null, null, null, null, item()),
        )

        assertTrue(answers.all { it in RemoteChange.entries })
    }
}
