package com.bookcovermatcher.core

import com.bookcovermatcher.core.online.OnlineQuery
import com.bookcovermatcher.core.online.ProviderCapabilities
import com.bookcovermatcher.core.online.ProviderTier
import com.bookcovermatcher.core.online.SearchField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.5.0-dev.2 — the primary/secondary split, and what happens when the user switches the secondary sources off.
 *
 * The rule under test is not cosmetic: a switched-off provider must not be queried **and** must not be reported as
 * having found nothing.
 */
class ProviderTierTest {

    @Test
    fun `dlsite is the doujin anchor and the general book sources are secondary`() {
        assertEquals(ProviderTier.PRIMARY_DOUJIN, ProviderCapabilities.of("dl")!!.tier)
        assertEquals(ProviderTier.SECONDARY_BOOK, ProviderCapabilities.of("ol")!!.tier)
        assertEquals(ProviderTier.SECONDARY_BOOK, ProviderCapabilities.of("gb")!!.tier)
        assertFalse(ProviderCapabilities.isSecondary("dl"))
        assertTrue(ProviderCapabilities.isSecondary("ol"))
        assertTrue(ProviderCapabilities.isSecondary("GB"))
        assertFalse(ProviderCapabilities.isSecondary("unknown"))
        assertEquals(listOf("dl"), ProviderCapabilities.primaryProviders().map { it.id })
        assertEquals(listOf("ol", "gb"), ProviderCapabilities.secondaryProviders().map { it.id })
    }

    @Test
    fun `switching the secondary sources off removes them from the eligible set`() {
        val query = OnlineQuery.parse("title:Blue Archive")
        val selected = setOf("dl", "ol", "gb")

        assertEquals(listOf("ol", "gb", "dl"), ProviderCapabilities.eligible(selected, query).map { it.id })
        assertEquals(listOf("dl"), ProviderCapabilities.eligible(selected, query, includeSecondary = false).map { it.id })
    }

    @Test
    fun `a switched-off provider is reported as skipped, not as a failed or empty search`() {
        val query = OnlineQuery.parse("title:Blue Archive")
        val skipped = ProviderCapabilities.skipped(setOf("dl", "ol", "gb"), query, includeSecondary = false)
        assertEquals(listOf("ol", "gb"), skipped.map { it.id })
        assertEquals(
            "DLsite is never reported as skipped for a title query",
            emptyList<String>(),
            ProviderCapabilities.skipped(setOf("dl"), query, includeSecondary = false).map { it.id },
        )
    }

    @Test
    fun `the query still decides who can answer when the secondary sources are on`() {
        val circleOnly = OnlineQuery.parse("circle:Yostar")
        assertEquals(
            listOf("dl"),
            ProviderCapabilities.eligible(setOf("dl", "ol", "gb"), circleOnly).map { it.id },
        )
        assertFalse(ProviderCapabilities.canSearch("ol", circleOnly))
        assertTrue(ProviderCapabilities.canSearch("dl", circleOnly))
    }

    @Test
    fun `a code-only query reaches the providers that accept an identifier`() {
        val codeOnly = OnlineQuery.parse("RJ01234567")
        assertEquals(listOf(SearchField.CODE), codeOnly.fields)
        assertTrue(ProviderCapabilities.canSearch("dl", codeOnly))
        assertTrue(ProviderCapabilities.canSearch("ol", codeOnly))
        assertEquals(
            listOf("ol", "gb", "dl"),
            ProviderCapabilities.eligible(setOf("dl", "ol", "gb"), codeOnly).map { it.id },
        )
        // And a provider switched off still stays out of it.
        assertEquals(
            listOf("dl"),
            ProviderCapabilities.eligible(setOf("dl", "ol", "gb"), codeOnly, includeSecondary = false).map { it.id },
        )
    }

    @Test
    fun `an image-only query reaches no metadata provider, because none of them does image search`() {
        val imageOnly = OnlineQuery(imageReference = "/covers/x.png")
        assertTrue(ProviderCapabilities.eligible(setOf("dl", "ol", "gb"), imageOnly).isEmpty())
        assertTrue(ProviderCapabilities.ALL.none { it.supports(SearchField.IMAGE_REFERENCE) })
    }
}
