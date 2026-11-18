// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.online

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcovermatcher.core.online.ProviderCatalog
import com.bookcovermatcher.data.OnlineSearchPrefs
import com.bookcovermatcher.plugins.*
import com.bookcovermatcher.ui.launchBrowser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class PlatformGroupsDeviceTest {
    @Test fun importedGroupsFeedPlatformsAndBrowserIntentsInIndexOrder() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "platform-fixture-" + UUID.randomUUID()
        val directory = File(base.cacheDir, name)
        val intercepted = mutableListOf<Intent>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(file: String, mode: Int) = base.getSharedPreferences(name + file, mode)
            override fun startActivity(intent: Intent) { intercepted.add(intent) }
        }
        val prefs = OnlineSearchPrefs(context)
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val settings = PluginSettings(directory) { false }
        fun restore() = PluginManager(editionPlugins, settings, owner, Dispatchers.Unconfined)
        var manager = restore()
        fun active() = manager.rows.value.mapNotNull { it.contribution as? ActivePlatforms }
        val c = withContext(Dispatchers.Main) {
            OnlineSearchController(context, owner, prefs, activeGroups = ::active)
        }
        suspend fun refresh() = withContext(Dispatchers.Main) { c.refreshCataloguePlugins() }
        fun rows() = c.ui.value.providers.map { it.id }
        val expected = editionGroups.sortedBy { it.index }.flatMap { it.entries }.map { it.id }
        try {
            assertEquals(emptyList<String>(), rows())
            for (g in editionGroups.reversed()) {
                manager.importDescriptor(readPluginId(("BCM-PLUGIN/1\n" + g.id).byteInputStream()))
                assertFalse(settings.enabled(g.id))
                manager.toggle(g.id, true)!!.join()
            }
            refresh()
            assertEquals(expected, rows())
            c.setQuery("Blue Archive")
            val shortcuts = c.ui.value.providers.filter { it.manual }
            for (row in shortcuts) {
                val url = requireNotNull(row.searchUrl)
                assertTrue(c.canOpenPlatform(url))
                launchBrowser(context, url)
                val intent = intercepted.last()
                assertEquals(Intent.ACTION_VIEW, intent.action)
                assertEquals(url, intent.dataString)
                assertEquals("https", intent.data!!.scheme)
                assertTrue(intent.data!!.host!!.removePrefix("www.") == row.host)
                assertTrue(url.contains("Blue%20Archive"))
            }
            for (g in editionGroups) {
                manager.toggle(g.id, false)!!.join()
                refresh()
                for (spec in g.entries) {
                    assertFalse(spec.id in rows())
                    assertFalse(c.canOpenPlatform(spec.searchUrl("Blue Archive")))
                }
            }
            assertEquals(emptyList<String>(), rows())
            for (g in editionGroups) manager.toggle(g.id, true)!!.join()
            manager = restore()
            refresh()
            assertEquals(expected, rows())
            for (g in editionGroups) assertTrue(settings.enabled(g.id))
        } finally {
            owner.cancel()
            directory.deleteRecursively()
            base.deleteSharedPreferences(name + "bookcover_online")
        }
    }
}
