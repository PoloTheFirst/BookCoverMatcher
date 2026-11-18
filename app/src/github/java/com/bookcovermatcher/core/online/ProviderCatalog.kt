// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.core.online

import com.bookcovermatcher.plugins.editionGroups

object ProviderCatalog {
    val ungrouped = listOf(ProviderSpec(
        id = "di",
        name = "Doujinshi.info",
        host = "doujinshi.info",
        access = ProviderAccess.BROWSER_ONLY,
        note = "Browser only: automated requests are refused (HTTP 403), so its search opens in your browser.",
        searchTemplate = "https://www.doujinshi.info/works?query={q}",
        landingUrl = "https://www.doujinshi.info/",
    ))
    val ALL = editionGroups.flatMap { it.entries } + ungrouped
    val AUTOMATED = ALL.filter { it.access == ProviderAccess.AUTOMATED }
    val SELECTABLE = AUTOMATED
    val BROWSER_ONLY = ALL.filter { it.access == ProviderAccess.BROWSER_ONLY }
    fun byId(id: String) = ALL.firstOrNull { it.id.equals(id, ignoreCase = true) }
}
