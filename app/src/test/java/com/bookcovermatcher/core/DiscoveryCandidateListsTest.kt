// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core

import com.bookcovermatcher.CandidateLists
import com.bookcovermatcher.ReverseCandidateUi
import com.bookcovermatcher.core.online.CandidateDecision
import com.bookcovermatcher.core.online.reverse.DiscoveryImageSource
import com.bookcovermatcher.core.online.reverse.DiscoveryImageState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.5.0-dev.4 — SauceNAO result cards are dismissible, and only the card the user pointed at goes away.
 *
 * The interesting cases are the asynchronous ones: a picture that is still being fetched must not put a dismissed
 * card back, and a dismissal must not touch another provider's card.
 */
class DiscoveryCandidateListsTest {

    private fun candidate(
        key: String,
        vararg providers: String,
        decision: CandidateDecision = CandidateDecision.SUGGESTED,
        imageState: DiscoveryImageState = DiscoveryImageState.NOT_REQUESTED,
    ) = ReverseCandidateUi(
        key = key,
        title = "Work $key",
        circle = "",
        author = "",
        productId = "",
        evidence = "SauceNAO 95%",
        sourceUrl = "https://www.dlsite.com/maniax/work/=/product_id/$key.html",
        imageUrl = null,
        providerCount = providers.size,
        decision = decision,
        providerIds = providers.toList(),
        imageState = imageState,
    )

    @Test
    fun `dismissing one SauceNAO card leaves every other card alone`() {
        val lists = CandidateLists(
            searched = listOf(
                candidate("saucenao-1", "saucenao"),
                candidate("saucenao-2", "saucenao"),
                candidate("iqdb-1", "iqdb"),
            ),
        )
        val after = lists.dismiss("saucenao-1")
        assertNotNull(after)
        assertEquals(listOf("saucenao-2", "iqdb-1"), after!!.visible.map { it.key })
    }

    @Test
    fun `dismissing an unknown key changes nothing`() {
        val lists = CandidateLists(searched = listOf(candidate("a", "saucenao")))
        assertNull(lists.dismiss("missing"))
        assertNull(lists.dismiss(""))
    }

    @Test
    fun `dismissal also removes a card that only exists as a pasted row`() {
        val lists = CandidateLists(pasted = listOf(candidate("row-1", "iqdb")), searched = emptyList())
        assertEquals(emptyList<String>(), lists.dismiss("row-1")!!.visible.map { it.key })
    }

    @Test
    fun `a late picture cannot bring a dismissed card back`() {
        val lists = CandidateLists(searched = listOf(candidate("gone", "saucenao")))
        val afterDismiss = lists.dismiss("gone")!!
        assertNull(
            "patching a key that is no longer present must be refused",
            afterDismiss.patch("gone") { it.copy(imageState = DiscoveryImageState.AVAILABLE) },
        )
        assertEquals(0, afterDismiss.size)
    }

    @Test
    fun `patching rewrites only the addressed card`() {
        val lists = CandidateLists(
            pasted = listOf(candidate("p1", "iqdb")),
            searched = listOf(candidate("s1", "saucenao"), candidate("s2", "saucenao")),
        )
        val bytes = byteArrayOf(1, 2, 3)
        val after = lists.patch("s1") {
            it.copy(imageBytes = bytes, imageSource = DiscoveryImageSource.PROVIDER, imageState = DiscoveryImageState.AVAILABLE)
        }!!
        val patched = after.find("s1")!!
        assertEquals(DiscoveryImageState.AVAILABLE, patched.imageState)
        assertEquals(DiscoveryImageSource.PROVIDER, patched.imageSource)
        assertEquals(DiscoveryImageState.NOT_REQUESTED, after.find("s2")!!.imageState)
        assertEquals(DiscoveryImageState.NOT_REQUESTED, after.find("p1")!!.imageState)
    }

    @Test
    fun `a new search can show a dismissed result again`() {
        val first = CandidateLists(searched = listOf(candidate("k", "saucenao")))
        assertEquals(0, first.dismiss("k")!!.size)
        // Dismissal is in-memory only: nothing was recorded, so the next search publishes the card again.
        val second = CandidateLists(searched = first.dismiss("k")!!.visible + candidate("k", "saucenao"))
        assertEquals(listOf("k"), second.visible.map { it.key })
    }

    @Test
    fun `pasted rows stay ahead of this search's rows`() {
        val lists = CandidateLists(pasted = listOf(candidate("p", "iqdb")), searched = listOf(candidate("s", "saucenao")))
        assertEquals(listOf("p", "s"), lists.visible.map { it.key })
    }

    @Test
    fun `every provider card is dismissible with the right label`() {
        val sauceNaoOnly = candidate("s", "saucenao")
        val iqdbOnly = candidate("i", "iqdb")
        val both = candidate("b", "saucenao", "iqdb")
        val neither = candidate("n")

        assertTrue(sauceNaoOnly.isDismissible)
        assertEquals("Dismiss SauceNAO result", sauceNaoOnly.dismissLabel)
        assertTrue(iqdbOnly.isDismissible)
        assertEquals("Dismiss IQDB result", iqdbOnly.dismissLabel)
        assertTrue(both.isDismissible)
        assertEquals("Dismiss discovery result", both.dismissLabel)
        assertFalse(neither.isDismissible)
    }

    @Test
    fun `rejected and suggested cards are distinguishable for bulk actions`() {
        val lists = CandidateLists(
            searched = listOf(
                candidate("a", "saucenao", decision = CandidateDecision.SUGGESTED),
                candidate("b", "saucenao", decision = CandidateDecision.REJECTED),
                candidate("c", "iqdb", decision = CandidateDecision.CONFIRMED),
            ),
        )
        val eligible = lists.eligibleForCollection {
            it.decision != CandidateDecision.REJECTED
        }
        assertEquals(listOf("a", "c"), eligible.map { it.key })
    }
}
