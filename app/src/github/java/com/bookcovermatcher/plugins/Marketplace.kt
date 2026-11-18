// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.plugins

import java.io.IOException
import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal object ActiveMarketplace : SettingsPanelContribution()

private val marketplace = PluginDescriptor("bcm.marketplace", "Plugin marketplace",
    "Turn on to download provider plugins from GitHub. Providers with adult content are labeled below.", "1.0", 2..2, setOf(Capability.SETTINGS_PANEL))

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
    MarketplaceEntry("Ordinary libraries", "ordinary-library", "2871b4a6021901c8701dba05248da79f14c60be3",
        "Search Open Library and Google Books."),
    MarketplaceEntry("DLsite-fetch", "dlsite", "3b38b2f3c9129854686271213ba56067d083c465",
        "Search the DLsite books catalogue. Results may include mature listings."),
    MarketplaceEntry("Doujin shop shortcuts", "doujin-shop", "33c1e8fed567accb68b37dd5322f8b2a8813f75b",
        "Open Melonbooks, Suruga-ya and Toranoana in your browser. Some listings are for adults."),
    MarketplaceEntry("Explicit database shortcuts", "explicit-database", "b0bc3c383b59e12357036efd1d0fa670b4282caf",
        "Includes links to explicit adult sites. Opens nhentai, E-Hentai, Doujin no Mori or MyAnimeList in your browser."),
)

internal val editionCatalogue = PluginCatalogue(marketplace.id, marketplaceEntries.associate { entry ->
    entry.id to suspend { entry.download(); Unit }
})
