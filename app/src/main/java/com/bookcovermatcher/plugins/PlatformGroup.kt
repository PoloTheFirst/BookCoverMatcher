// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.plugins

import com.bookcovermatcher.core.online.ProductSearchDataSource
import com.bookcovermatcher.core.online.reverse.ReverseImageSource
import com.bookcovermatcher.core.online.ProviderSpec

/** One settings switch controls availability. Each entry keeps its legacy identity and position. */
internal open class PlatformGroup(
    val id: String,
    val index: Int,
    name: String,
    description: String,
    val entries: List<ProviderSpec>,
    val reverseSources: ((() -> String?) -> List<ReverseImageSource>)? = null,
    val createSources: () -> List<ProductSearchDataSource> = { emptyList() },
) {
    val registration: Registration
    init {
        val metadata = PluginDescriptor(id, name, description, "1.0", 2..2, setOf(Capability.PLATFORM_GROUP))
        registration = Registration(metadata) {
            object : BundledPlugin {
                override val descriptor = metadata
                override suspend fun activate() = ActivePlatforms(this@PlatformGroup, createSources())
            }
        }
    }
}

internal class ActivePlatforms(val group: PlatformGroup, val sources: List<ProductSearchDataSource>) :
    PlatformGroupContribution()

internal val platformOrder = compareBy<PlatformGroup>({ it.index }, { it.id })

internal fun List<ActivePlatforms>.platforms(): List<ProviderSpec> =
    map { it.group }.sortedWith(platformOrder).flatMap { it.entries }

internal val editionPlugins get() = editionGroups.map { it.registration } + editionPanels

/** Only automated catalogue sources had individual choices before groups were introduced. */
internal fun initialPluginSelection(id: String, legacySelection: Set<String>?): Boolean {
    val selectable = editionGroups.firstOrNull { it.id == id }?.entries.orEmpty().filter { it.selectable }
    val preinstalled = editionCatalogue?.bundled?.containsKey(id) == true
    if (legacySelection == null || selectable.isEmpty()) return preinstalled
    return selectable.any { it.id in legacySelection }
}
