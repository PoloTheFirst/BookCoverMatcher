// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.plugins

import com.bookcovermatcher.plugins.groups.CompiledGroups

internal val editionGroups: List<PlatformGroup> = CompiledGroups.all().sortedWith(platformOrder).also { groups ->
    require(groups.distinctBy { it.id }.size == groups.size) { "Duplicate plugin ID" }
    val entries = groups.flatMap { it.entries }
    require(entries.distinctBy { it.id }.size == entries.size) { "Duplicate platform provider" }
}
