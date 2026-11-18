// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.plugins

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PluginManagerTest {
    private val descriptor = PluginDescriptor("bcm.test", "Test", "", "1", 2..2, setOf(Capability.INFORMATION_CARD))
    private val card = InfoCard("Sample", "Content")
    private class Choices(initiallyImported: Boolean = true) : PluginChoices {
        private val importedIds = if (initiallyImported) mutableSetOf("bcm.test") else mutableSetOf()
        val saved = mutableMapOf("other.edition" to true)
        var writeFails = false
        override fun removed(id: String) = false
        override fun remove(id: String) { importedIds.remove(id); saved.remove(id) }
        override fun imported(id: String) = id in importedIds
        override fun enabled(id: String) = saved[id] == true
        override fun save(id: String, enabled: Boolean) {
            check(!writeFails)
            importedIds += id
            saved[id] = enabled
        }
    }
    private fun fixture(
        choices: Choices = Choices(),
        descriptor: PluginDescriptor = this.descriptor,
        activate: suspend () -> InfoCard = { card },
        stop: suspend () -> Unit = {},
    ): PluginManager {
        val registration = Registration(descriptor) {
            object : BundledPlugin {
                override val descriptor = descriptor
                override suspend fun activate() = activate.invoke()
                override suspend fun deactivate() = stop()
            }
        }
        return PluginManager(listOf(registration), choices, CoroutineScope(SupervisorJob()),
            Dispatchers.Unconfined, timeoutMs = 100)
    }

    @Test fun manualImportRespectsExistingSelectionOnlyOnce() = runBlocking {
        for (legacy in listOf(null, false, true)) {
            val choices = Choices(initiallyImported = false)
            val id = descriptor.id
            if (legacy != null) choices.saved[id] = legacy
            val manager = fixture(choices)
            manager.toggle(id, true)!!.join()
            assertTrue(manager.imported.value.isEmpty())
            assertNull(manager.rows.value.single().card)
            manager.importDescriptor("unknown.plugin")
            assertTrue(manager.imported.value.isEmpty())
            manager.importDescriptor(id)
            assertEquals(setOf(id), manager.imported.value)
            assertEquals(legacy == true, manager.rows.value.single().card != null)
            manager.toggle(id, false)!!.join()
            manager.importDescriptor(id)
            val restored = fixture(choices)
            assertEquals(setOf(id), restored.imported.value)
            assertNull(restored.rows.value.single().card)
        }
    }

    @Test fun descriptorRequiresAnExactBoundedDataFormat() {
        assertEquals("bcm.dlsite", readPluginId("BCM-PLUGIN/1\r\nbcm.dlsite\r\n".byteInputStream()))
        for (bad in listOf("", "BCM-PLUGIN/1", "BCM-PLUGIN/2\nbcm.dlsite",
            "BCM-PLUGIN/1\n../dlsite", "BCM-PLUGIN/1\nbcm.dlsite\nextra", "a".repeat(257))) {
            assertThrows(IllegalArgumentException::class.java) { readPluginId(bad.byteInputStream()) }
        }
    }

    @Test fun repeatAndRestore() = runBlocking {
        val choices = Choices()
        var starts = 0
        var stops = 0
        val manager = fixture(choices, activate = { starts++; card }, stop = { stops++ })
        val id = descriptor.id
        repeat(3) { manager.toggle(id, true)!!.join() }
        assertEquals(1, starts)
        assertEquals(card, manager.rows.value.single().card)
        val restored = fixture(choices)
        assertEquals(card, restored.rows.value.single().card)
        restored.toggle(id, false)!!.join()
        manager.toggle(id, false)!!.join()
        assertEquals(1, stops)
        assertNull(manager.rows.value.single().card)
        assertEquals(true, choices.saved["other.edition"])
        assertNull(manager.toggle("other.edition", true))
    }

    @Test fun incompatibleRestoreAndDuplicateIds() {
        val choices = Choices().apply { saved[descriptor.id] = true }
        val manager = fixture(choices, descriptor.copy(hostVersions = 3..3), activate = { error("unexpected") })
        assertEquals(PluginStatus.UNAVAILABLE, manager.rows.value.single().status)
        assertEquals(true, choices.saved[descriptor.id])
        val entry = Registration(descriptor) { error("unexpected creation") }
        assertThrows(IllegalArgumentException::class.java) {
            PluginManager(listOf(entry, entry), choices, CoroutineScope(SupervisorJob()))
        }
    }

    @Test fun activationAndSaveFailuresCanBeRetried() = runBlocking {
        val choices = Choices()
        var broken = true
        val manager = fixture(choices, activate = { check(!broken); card })
        val id = descriptor.id
        manager.toggle(id, true)!!.join()
        assertEquals(PluginStatus.FAILED, manager.rows.value.single().status)
        assertEquals(false, choices.saved[id])
        broken = false
        choices.writeFails = true
        manager.toggle(id, true)!!.join()
        assertNull(manager.rows.value.single().card)
        choices.writeFails = false
        manager.toggle(id, true)!!.join()
        choices.writeFails = true
        manager.toggle(id, false)!!.join()
        assertEquals(card, manager.rows.value.single().card)
        assertEquals(true, choices.saved[id])
        choices.writeFails = false
        manager.toggle(id, false)!!.join()
        assertNull(manager.rows.value.single().card)
    }

    @Test fun delayedCleanupPreventsOverlapAndStaleCard() = runBlocking {
        val draining = CompletableDeferred<Unit>()
        var starts = 0
        val manager = fixture(activate = { starts++; card }, stop = { draining.await() })
        val id = descriptor.id
        manager.toggle(id, true)!!.join()
        val off = manager.toggle(id, false)!!
        assertNull(manager.rows.value.single().card)
        val on = manager.toggle(id, true)!!
        joinAll(off, on)
        assertEquals(1, starts)
        assertNull(manager.rows.value.single().card)
        draining.complete(Unit)
        manager.toggle(id, true)!!.join()
        assertEquals(2, starts)
        manager.toggle(id, false)!!.join()
    }
}
