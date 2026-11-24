// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.plugins

import org.junit.Assert.*
import org.junit.Test

class MarketplaceTest {
    @Test fun catalogueUsesThePublishedDescriptorsInPlatformOrder() {
        val pins = listOf(
            "ordinary-library" to "227ce7e3f412c272670cb56d4e15a87d36a95fb5",
            "dlsite" to "e4794a5005a489f944bb1cb70eea645301671015",
            "doujin-shop" to "835dfcbe44ad8a59f7db95be022d751a432622f4",
            "explicit-database" to "36f47ba18d483d767a893b5decac6531c8b51058",
            "reverse-image-discovery" to "50a8dce34aaddf1f7dbb31a6f65a38baa8e90654",
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
        assertEquals("DLsite Fetch", marketplaceEntries.single { it.id == "bcm.dlsite" }.name)
    }
}
