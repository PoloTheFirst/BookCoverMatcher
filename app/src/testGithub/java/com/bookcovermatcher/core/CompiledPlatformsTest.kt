// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.core

import com.bookcovermatcher.core.online.ProviderCatalog
import com.bookcovermatcher.plugins.*
import org.junit.Assert.*
import org.junit.Test

class CompiledPlatformsTest {
    @Test fun eachBundledGroupOwnsItsProvidersAndOnlyFetchGroupsCreateAdapters() {
        val expected = listOf(listOf("ol", "gb"), listOf("dl"), listOf("mb", "sy", "tr"), listOf("nh", "eh", "dm", "mal"))
        for (group in editionGroups) {
            assertEquals(expected.getOrElse(group.index) { emptyList() }, group.entries.map { it.id })
            assertEquals(group.entries.filter { it.selectable }.map { it.name }, group.createSources().map { it.name })
        }
        val active = editionGroups.reversed().map { ActivePlatforms(it, emptyList()) }
        assertEquals(editionGroups.sortedBy { it.index }.flatMap { expected.getOrElse(it.index) { emptyList() } } + "di", active.platforms().map { it.id })
        assertEquals(listOf("di"), emptyList<ActivePlatforms>().platforms().map { it.id })
        ProviderCatalog.byId("mal")?.let { assertEquals("myanimelist.net", it.host) }
    }
}
