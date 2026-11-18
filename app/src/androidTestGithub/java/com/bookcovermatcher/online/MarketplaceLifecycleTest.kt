// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.online

import androidx.test.platform.app.InstrumentationRegistry
import com.bookcovermatcher.plugins.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class MarketplaceLifecycleTest {
    @Test fun migratePauseRestoreRemoveAndRetry() = runBlocking {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "market-test-${System.nanoTime()}")
        val choices = PluginSettings(dir) { false }
        val parent = editionCatalogue.parent
        val child = "bcm.dlsite"
        val library = "bcm.ordinary-library"
        choices.save(child, true)
        var owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var offline = true
        val fetched = mutableListOf<String>()
        val catalog = PluginCatalogue(parent, editionCatalogue.fetch.keys.associateWith { id ->
            suspend {
                fetched.add(id)
                if (offline) throw IOException("offline")
                Unit
            }
        })
        fun open() = PluginManager(editionPlugins, choices, owner, Dispatchers.Unconfined, catalogue = catalog)
        var manager = open()
        fun row(id: String) = manager.rows.value.single { it.descriptor.id == id }
        suspend fun waitFor(id: String, status: PluginStatus) = withTimeout(5000) {
            manager.rows.first { rows -> rows.any { it.descriptor.id == id && it.status == status } }
        }
        try {
            assertTrue(parent in manager.imported.value)
            assertTrue(row(child).rememberedOn)
            assertNull(row(child).contribution)
            assertTrue(fetched.isEmpty())
            assertEquals("Manage this plugin in Plugin Marketplace.", manager.importDescriptor(child))
            manager.toggle(parent, true)!!.join()
            waitFor(library, PluginStatus.FAILED)
            assertEquals(editionCatalogue.fetch.keys - child, fetched.toSet())
            assertTrue(row(child).contribution is ActivePlatforms)
            offline = false
            manager.download(library).join()
            waitFor(library, PluginStatus.DISABLED)
            manager.toggle(library, true)!!.join()
            assertTrue(row(library).contribution is ActivePlatforms)
            manager.toggle(parent, false)!!.join()
            assertTrue(row(library).rememberedOn)
            assertNull(row(library).contribution)
            manager.toggle(library, false)!!.join()
            assertTrue(choices.enabled(library))
            owner.cancel()
            owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            manager = open()
            assertTrue(row(library).rememberedOn)
            assertNull(row(library).contribution)
            manager.toggle(parent, true)!!.join()
            assertTrue(row(library).contribution is ActivePlatforms)
            manager.remove(library).join()
            assertFalse(library in manager.imported.value)
            assertNull(row(library).contribution)
            val count = fetched.count { it == library }
            manager.toggle(parent, false)!!.join()
            manager.toggle(parent, true)!!.join()
            assertEquals(count, fetched.count { it == library })
            manager.download(library).join()
            waitFor(library, PluginStatus.DISABLED)
            assertFalse(choices.enabled(library))
        } finally {
            owner.cancel()
            dir.deleteRecursively()
        }
    }

    @Test fun switchingOffDuringFetchDiscardsTheLateResult() = runBlocking {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "market-test-${System.nanoTime()}")
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val choices = PluginSettings(dir) { false }
        val parent = editionCatalogue.parent
        val child = "bcm.dlsite"
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val config = PluginCatalogue(parent, mapOf(child to suspend {
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
            finished.complete(Unit)
            Unit
        }))
        try {
            val manager = PluginManager(editionPlugins, choices, owner, Dispatchers.Unconfined, catalogue = config)
            manager.importDescriptor(parent)
            manager.toggle(parent, true)!!.join()
            withTimeout(5000) { started.await() }
            manager.toggle(parent, false)!!.join()
            release.complete(Unit)
            withTimeout(5000) { finished.await() }
            assertFalse(choices.imported(child))
            assertFalse(child in manager.imported.value)
            assertNull(manager.rows.value.single { it.descriptor.id == child }.contribution)
        } finally {
            release.complete(Unit)
            owner.cancel()
            dir.deleteRecursively()
        }
    }
}
