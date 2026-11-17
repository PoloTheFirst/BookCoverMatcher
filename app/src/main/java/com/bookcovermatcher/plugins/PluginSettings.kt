// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.plugins

import android.util.AtomicFile
import java.io.File

/** Independent per-ID files allow atomic saves without rewriting unrelated or unknown choices. */
internal class PluginSettings(private val directory: File, private val initialChoice: (String) -> Boolean) : PluginChoices {
    private fun file(id: String) = AtomicFile(File(directory, "$id.choice"))
    override fun imported(id: String) = read(id) in setOf(2, 3)
    override fun enabled(id: String) = when (read(id)) {
        0, 2 -> false
        1, 3 -> true
        null -> initialChoice(id)
        else -> error("Unreadable plugin setting")
    }
    private fun read(id: String): Int? {
        val setting = file(id)
        return try {
            setting.openRead().use { it.read() }
        } catch (e: java.io.FileNotFoundException) {
            if (setting.baseFile.exists()) throw e
            null
        }
    }

    override fun save(id: String, enabled: Boolean) {
        check(directory.isDirectory || directory.mkdirs() || directory.isDirectory)
        val setting = file(id)
        val out = setting.startWrite()
        try {
            out.write(if (enabled) 3 else 2)
            setting.finishWrite(out)
            check(imported(id) && this.enabled(id) == enabled) { "Plugin setting was not saved" }
        } catch (e: Exception) {
            setting.failWrite(out)
            throw e
        }
    }
}
