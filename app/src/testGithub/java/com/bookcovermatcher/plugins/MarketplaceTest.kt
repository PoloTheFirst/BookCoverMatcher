// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.plugins

import org.junit.Assert.*
import org.junit.Test

class MarketplaceTest {
    @Test fun catalogueUsesThePublishedDescriptorsInPlatformOrder() {
        val pins = listOf(
            "ordinary-library" to "2871b4a6021901c8701dba05248da79f14c60be3",
            "dlsite" to "3b38b2f3c9129854686271213ba56067d083c465",
            "doujin-shop" to "33c1e8fed567accb68b37dd5322f8b2a8813f75b",
            "explicit-database" to "b0bc3c383b59e12357036efd1d0fa670b4282caf",
        )
        assertEquals(pins.map { it.first }, marketplaceEntries.map { it.slug })
        for ((entry, pin) in marketplaceEntries.zip(pins)) {
            val (slug, commit) = pin
            assertEquals("https://raw.githubusercontent.com/PoloTheFirst/BookCoverMatcher/$commit/plugins/manifests/$slug.bcmplugin",
                entry.url.toExternalForm())
            val bytes = "BCM-PLUGIN/1\r\nbcm.$slug\r\n".toByteArray()
            assertArrayEquals(bytes, entry.validatedBytes(bytes.inputStream()))
        }
    }

    @Test fun rejectsAnUnexpectedIdBadFormatAndOversizedFile() {
        val entry = marketplaceEntries.first()
        for (input in listOf("BCM-PLUGIN/1\nbcm.dlsite", "BCM-PLUGIN/2\nbcm.ordinary-library", "x".repeat(257))) {
            assertThrows(IllegalArgumentException::class.java) { entry.validatedBytes(input.byteInputStream()) }
        }
    }

    @Test fun catalogueContainsEachGroupOnceAndRetainsTheDlsiteId() {
        assertEquals(marketplaceEntries.map { it.id }, editionCatalogue.fetch.keys.toList())
        assertEquals("DLsite-fetch", marketplaceEntries.single { it.id == "bcm.dlsite" }.name)
    }
}
