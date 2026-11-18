// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.plugins

import com.bookcovermatcher.plugins.groups.CompiledGroups

internal val editionGroups: List<PlatformGroup> = CompiledGroups.all().sortedBy { it.index }.also { groups ->
    require(groups.distinctBy { it.index }.size == groups.size) { "Duplicate platform index" }
    val entries = groups.flatMap { it.entries }
    require(entries.distinctBy { it.id }.size == entries.size) { "Duplicate platform provider" }
}
