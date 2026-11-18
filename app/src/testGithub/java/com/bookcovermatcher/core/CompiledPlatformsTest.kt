// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.core

import com.bookcovermatcher.core.online.ProviderCatalog
import com.bookcovermatcher.plugins.*
import org.junit.Assert.*
import org.junit.Test

class CompiledPlatformsTest {
    @Test fun customGroupsSharingAnIndexKeepTheSamePlatformOrderAfterReactivation() {
        fun group(id: String, index: Int) = PlatformGroup(id, index, id, "", listOf(
            com.bookcovermatcher.core.online.ProviderSpec(id, id, "example.org",
                com.bookcovermatcher.core.online.ProviderAccess.BROWSER_ONLY, "", null, null)
        ))
        val a = group("org.example.archive", 100)
        val b = group("org.example.library", 100)
        val first = group("org.example.z", 10)
        for (groups in listOf(listOf(b, a, first), listOf(first, a, b))) {
            val active = groups.map { ActivePlatforms(it, emptyList()) }
            assertEquals(listOf(first.id, a.id, b.id), active.platforms().map { it.id })
        }
    }

    @Test fun eachBundledGroupOwnsItsProvidersAndOnlyFetchGroupsCreateAdapters() {
        val expected = listOf(listOf("ol", "gb"), listOf("dl"), listOf("mb", "sy", "tr"), listOf("nh", "eh", "dm", "mal", "di"))
        for (group in editionGroups) {
            assertEquals(expected.getOrElse(group.index) { emptyList() }, group.entries.map { it.id })
            assertEquals(group.entries.filter { it.selectable }.map { it.name }, group.createSources().map { it.name })
        }
        val active = editionGroups.reversed().map { ActivePlatforms(it, emptyList()) }
        assertEquals(editionGroups.sortedBy { it.index }.flatMap { expected.getOrElse(it.index) { emptyList() } }, active.platforms().map { it.id })
        assertEquals(emptyList<String>(), emptyList<ActivePlatforms>().platforms().map { it.id })
        ProviderCatalog.byId("mal")?.let { assertEquals("myanimelist.net", it.host) }
    }
}
