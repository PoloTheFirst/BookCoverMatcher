// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.plugins

import java.io.IOException
import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal object ActiveMarketplace : SettingsPanelContribution()

private val marketplace = PluginDescriptor("bcm.marketplace", "Plugin Marketplace",
    "Manage the five discovery plugins included in this edition.", "1.0", 2..2, setOf(Capability.SETTINGS_PANEL))

internal val editionPanels = listOf(Registration(marketplace) {
    object : BundledPlugin {
        override val descriptor = marketplace
        override suspend fun activate() = ActiveMarketplace
    }
})

internal class MarketplaceEntry(val name: String, val slug: String, private val commit: String, val summary: String) {
    val id = "bcm.$slug"
    val filename = "$slug.bcmplugin"
    val url get() = URL("https://raw.githubusercontent.com/PoloTheFirst/BookCoverMatcher/$commit/plugins/manifests/$filename")

    fun validatedBytes(input: InputStream): ByteArray {
        val file = readPluginFile(input)
        require(file.id == id) { "Unexpected plugin ID" }
        return file.bytes
    }

    fun download(): ByteArray {
        val connection = url.openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
            return connection.inputStream.use(::validatedBytes)
        } finally {
            connection.disconnect()
        }
    }


}

internal val marketplaceEntries = listOf(
    MarketplaceEntry("Ordinary Library Fetch", "ordinary-library", "227ce7e3f412c272670cb56d4e15a87d36a95fb5",
        "Search Open Library and Google Books."),
    MarketplaceEntry("DLsite Fetch", "dlsite", "e4794a5005a489f944bb1cb70eea645301671015",
        "Search the DLsite books catalogue. Results may include mature listings."),
    MarketplaceEntry("Doujin Shop Shortcuts", "doujin-shop", "835dfcbe44ad8a59f7db95be022d751a432622f4",
        "Open Melonbooks, Suruga-ya and Toranoana in your browser. Some listings are for adults."),
    MarketplaceEntry("Explicit Database Shortcuts", "explicit-database", "36f47ba18d483d767a893b5decac6531c8b51058",
        "Includes links to explicit adult sites. Opens nhentai, E-Hentai, Doujin no Mori, MyAnimeList or Doujinshi.info in your browser."),
    MarketplaceEntry("Reverse Image Discovery", "reverse-image-discovery", "50a8dce34aaddf1f7dbb31a6f65a38baa8e90654",
        "Search a cover with SauceNAO or open IQDB in your browser."),
)

internal val editionCatalogue = PluginCatalogue(marketplace.id, marketplaceEntries.associate { entry ->
    entry.id to suspend { entry.download(); Unit }
}, bundled = (marketplaceEntries.associate { it.id to it.filename } + (marketplace.id to "marketplace.bcmplugin"))
    .mapValues { (expected, filename) ->
        {
            val stream = checkNotNull(ActiveMarketplace::class.java.getResourceAsStream("/$filename")) {
                "Missing bundled plugin descriptor: $filename"
            }
            stream.use { require(readPluginId(it) == expected) { "Unexpected bundled plugin ID" } }
        }
    })
