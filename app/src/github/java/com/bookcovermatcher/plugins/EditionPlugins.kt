// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.plugins

import com.bookcovermatcher.core.online.ProductSearchDataSource
import com.bookcovermatcher.online.DlsiteSource

private class ActiveDlsite : CatalogueProvider("dl"),
    ProductSearchDataSource by DlsiteSource()

private val dlsite = PluginDescriptor(
    "bcm.dlsite", "DLsite", "A search sends your query to the DLsite books catalogue. Results may include mature listings.",
    "1.0", 2..2, setOf(Capability.CATALOGUE_PROVIDER),
)
internal val editionPlugins = listOf(Registration(dlsite) {
    object : BundledPlugin {
        override val descriptor = dlsite
        override suspend fun activate() = ActiveDlsite()
    }
})
