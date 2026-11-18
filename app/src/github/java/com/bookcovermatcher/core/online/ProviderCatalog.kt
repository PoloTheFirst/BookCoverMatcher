// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.core.online

import com.bookcovermatcher.plugins.editionGroups

object ProviderCatalog {
    val ALL = editionGroups.flatMap { it.entries }
    val AUTOMATED = ALL.filter { it.access == ProviderAccess.AUTOMATED }
    val SELECTABLE = AUTOMATED
    val BROWSER_ONLY = ALL.filter { it.access == ProviderAccess.BROWSER_ONLY }
    fun byId(id: String) = ALL.firstOrNull { it.id.equals(id, ignoreCase = true) }
}
