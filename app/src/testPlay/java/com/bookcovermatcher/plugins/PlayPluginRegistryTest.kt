// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.plugins

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlayPluginRegistryTest {
    @Test fun freshAndRestoredInstallsExposeNoPlugins() = runBlocking {
        for (restored in listOf(false, true)) {
            val choices = object : PluginChoices {
                override fun removed(id: String) = false
                override fun remove(id: String) = error("Unexpected settings write")
                override fun imported(id: String) = restored
                override fun enabled(id: String) = restored
                override fun save(id: String, enabled: Boolean) = error("Unexpected settings write")
            }
            val manager = PluginManager(bundledPlugins + editionPlugins, choices, this, Dispatchers.Unconfined)
            assertTrue(manager.rows.value.isEmpty())
            for (id in listOf("bcm.ordinary-library", "bcm.dlsite", "bcm.doujin-shop",
                    "bcm.explicit-database", "bcm.reverse-image-discovery", "bcm.marketplace")) {
                val descriptor = readPluginId("BCM-PLUGIN/1\n$id\n".byteInputStream())
                assertEquals("This plugin is unavailable in this edition.", manager.importDescriptor(descriptor))
                assertNull(manager.toggle(id, true))
            }
            assertTrue(manager.imported.value.isEmpty())
        }
    }
}
