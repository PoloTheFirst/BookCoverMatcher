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

internal fun List<ActivePlatforms>.platforms(): List<ProviderSpec> =
    sortedBy { it.group.index }.flatMap { it.group.entries }

internal val editionPlugins get() = editionGroups.map { it.registration } + editionPanels
