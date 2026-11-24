// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.plugins

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ExplicitDefaultsTest {
    private val parent = editionCatalogue.parent
    private val library = "bcm.ordinary-library"
    private val dlsite = "bcm.dlsite"

    /** Models both legacy choice-only files and the current imported/removed representation. */
    private class Settings(val providers: Set<String>? = null) : PluginChoices {
        val state = mutableMapOf<String, Int>()
        var failSave: String? = null
        override fun imported(id: String) = state[id] in setOf(2, 3)
        override fun removed(id: String) = state[id] == 4
        override fun enabled(id: String) = when (state[id]) {
            null -> initialPluginSelection(id, providers)
            1, 3 -> true
            else -> false
        }
        override fun save(id: String, enabled: Boolean) {
            check(id != failSave)
            state[id] = if (enabled) 3 else 2
        }
        override fun remove(id: String) { state[id] = 4 }
    }

    private fun start(owner: CoroutineScope, settings: Settings, network: () -> Unit): PluginManager {
        val catalogue = PluginCatalogue(parent, editionCatalogue.fetch.mapValues { suspend { network() } },
            editionCatalogue.bundled)
        return PluginManager(editionPlugins, settings, owner, Dispatchers.Unconfined, catalogue = catalogue)
    }

    @Test fun offlineInstallationPauseRemoveRestoreAndReopen() = runBlocking {
        val ordered = listOf(library, dlsite, "bcm.doujin-shop", "bcm.explicit-database", "bcm.reverse-image-discovery")
        assertEquals(ordered, editionGroups.map { it.id })
        assertEquals(ordered.toSet() + parent, editionCatalogue.bundled.keys)
        val settings = Settings()
        var requests = 0
        var owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var manager = start(owner, settings) { requests++ }
            fun status(id: String) = manager.rows.value.single { it.descriptor.id == id }
            assertEquals(editionCatalogue.bundled.keys, manager.imported.value)
            assertTrue(manager.rows.value.all { it.status == PluginStatus.ENABLED })
            manager.toggle(library, false)!!.join()
            manager.remove(dlsite).join()
            manager.toggle(parent, false)!!.join()
            assertEquals(PluginStatus.PAUSED, status(library).status)
            assertFalse(status(library).rememberedOn)
            assertTrue(status(ordered.last()).rememberedOn)

            owner.cancel()
            owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            manager = start(owner, settings) { requests++ }
            assertFalse(dlsite in manager.imported.value)
            assertEquals(PluginStatus.DISABLED, status(parent).status)
            manager.toggle(parent, true)!!.join()
            assertEquals(PluginStatus.DISABLED, status(library).status)
            assertEquals(PluginStatus.ENABLED, status(ordered.last()).status)
            // An explicit restore of a removed group also uses the local descriptor.
            manager.download(dlsite).join()
            assertTrue(dlsite in manager.imported.value)
            assertEquals(PluginStatus.DISABLED, status(dlsite).status)
            manager.remove(parent).join()

            owner.cancel()
            owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            manager = start(owner, settings) { requests++ }
            assertFalse(parent in manager.imported.value)
            assertTrue(manager.rows.value.none { it.contribution != null })
            manager.importDescriptor(parent)
            manager.toggle(parent, true)!!.join()
            assertEquals(PluginStatus.DISABLED, status(dlsite).status)
            assertEquals(PluginStatus.ENABLED, status(ordered.last()).status)
            assertEquals(0, requests)
        } finally { owner.cancel() }
    }

    @Test fun legacyEmptyPartialAndUnsetSelectionsAreDistinct() {
        assertTrue(initialPluginSelection(dlsite, null))
        assertFalse(initialPluginSelection(dlsite, emptySet()))
        assertFalse(initialPluginSelection(library, emptySet()))
        assertTrue(initialPluginSelection(library, setOf("ol")))
        assertFalse(initialPluginSelection(dlsite, setOf("ol")))
        assertTrue(initialPluginSelection(dlsite, setOf("dl")))
        assertTrue(initialPluginSelection("bcm.reverse-image-discovery", emptySet()))
        assertTrue(initialPluginSelection("bcm.doujin-shop", emptySet()))
        assertFalse(initialPluginSelection("bcm.unknown", null))
    }

    @Test fun aPartialMigrationResumesWithoutOverwritingStoredChoices() {
        val settings = Settings(setOf("dl")).apply {
            state[parent] = 0
            state[library] = 1
            state[dlsite] = 2
            state["bcm.doujin-shop"] = 4
            state["other.edition"] = 3
            failSave = "bcm.explicit-database"
        }
        var requests = 0
        val first = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            val manager = start(first, settings) { requests++ }
            assertFalse("bcm.explicit-database" in manager.imported.value)
            assertEquals(2, settings.state[parent])
            assertEquals(3, settings.state[library])
            assertEquals(2, settings.state[dlsite])
        } finally { first.cancel() }

        val prior = settings.state.toMap()
        settings.failSave = null
        val next = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            val manager = start(next, settings) { requests++ }
            assertEquals(prior + ("bcm.explicit-database" to 3), settings.state)
            assertFalse("bcm.doujin-shop" in manager.imported.value)
            assertTrue(manager.rows.value.none { it.contribution != null })
            assertEquals(0, requests)
        } finally { next.cancel() }
    }
}
