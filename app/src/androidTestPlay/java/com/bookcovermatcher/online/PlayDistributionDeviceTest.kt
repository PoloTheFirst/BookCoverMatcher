// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.online

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcovermatcher.CatalogueUi
import com.bookcovermatcher.data.OnlineSearchPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run only on an isolated test installation; never clears workbook storage. */
@RunWith(AndroidJUnit4::class)
class PlayDistributionDeviceTest {
    @Test fun restoredGithubPreferencesCannotActivateCatalogueOrOnlineOcr() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = context.getSharedPreferences("bookcover_online", Context.MODE_PRIVATE)
        val saved = store.all
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            store.edit().putBoolean("online_enabled", true)
                .putBoolean("online_auto_search", true).putBoolean("online_secondary_sources", true)
                .putStringSet("online_providers", setOf("ol", "gb", "dl"))
                .putString("online_ocr_engine", "python").commit()
            val prefs = OnlineSearchPrefs(context)
            assertTrue(prefs.selectedProviders.isEmpty())
            assertFalse(prefs.autoSearch)
            assertFalse(prefs.secondarySources)
            assertEquals("rust", prefs.ocrEngine)
            val choices = object : com.bookcovermatcher.plugins.PluginChoices {
                override fun removed(id: String) = false
                override fun remove(id: String) = error("Unexpected removal")
                override fun imported(id: String) = true
                override fun enabled(id: String) = true
                override fun save(id: String, enabled: Boolean) = error("Unexpected plugin setting write")
            }
            val manager = com.bookcovermatcher.plugins.PluginManager(
                com.bookcovermatcher.plugins.editionPlugins, choices, scope, Dispatchers.Unconfined)
            kotlinx.coroutines.runBlocking {
                for (id in listOf("bcm.ordinary-library", "bcm.dlsite", "bcm.doujin-shop", "bcm.explicit-database", "bcm.marketplace", "bcm.reverse-image-discovery")) {
                    assertEquals("This plugin is unavailable in this edition.", manager.importDescriptor(id))
                }
            }
            assertTrue(manager.imported.value.isEmpty())
            val controller = OnlineSearchController(context, scope, prefs = prefs)
            try {
                assertTrue(controller.reverseAvailable())
                assertEquals(2, controller.ui.value.reverse.providers.size)
                controller.search("restored query")
                controller.setAutoSearch(true)
                controller.setSecondarySources(true)
                controller.toggleProvider("dl")
                assertTrue(controller.ui.value.providers.isEmpty())
                assertTrue(controller.ui.value.catalogue is CatalogueUi.Idle)
                assertEquals(setOf("ol", "gb", "dl"), store.getStringSet("online_providers", null))
                assertEquals("python", store.getString("online_ocr_engine", null))
                assertTrue(store.getBoolean("online_auto_search", false))
            } finally { controller.shutdown() }
        } finally {
            scope.cancel()
            val edit = store.edit().clear()
            saved.forEach { (key, value) ->
                when (value) {
                    is String -> edit.putString(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                    is Long -> edit.putLong(key, value)
                    is Float -> edit.putFloat(key, value)
                    is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            edit.commit()
        }
    }
}
