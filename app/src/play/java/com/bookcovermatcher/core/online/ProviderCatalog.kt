// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.online

object ProviderCatalog {
    val ALL: List<ProviderSpec> = emptyList()
    val AUTOMATED: List<ProviderSpec> = emptyList()
    val SELECTABLE: List<ProviderSpec> = emptyList()
    val BROWSER_ONLY: List<ProviderSpec> = emptyList()
    fun byId(id: String): ProviderSpec? = null
}
