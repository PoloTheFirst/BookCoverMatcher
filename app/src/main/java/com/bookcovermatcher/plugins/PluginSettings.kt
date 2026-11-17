// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.plugins

import android.util.AtomicFile
import java.io.File

/** Independent per-ID files allow atomic saves without rewriting unrelated or unknown choices. */
internal class PluginSettings(private val directory: File) : PluginChoices {
    private fun file(id: String) = AtomicFile(File(directory, "$id.choice"))
    override fun enabled(id: String): Boolean {
        val setting = file(id)
        return try {
            setting.openRead().use {
                when (it.read()) {
                    0 -> false
                    1 -> true
                    else -> error("Unreadable plugin setting")
                }
            }
        } catch (e: java.io.FileNotFoundException) {
            if (setting.baseFile.exists()) throw e
            false
        }
    }

    override fun save(id: String, enabled: Boolean) {
        check(directory.isDirectory || directory.mkdirs() || directory.isDirectory)
        val setting = file(id)
        val out = setting.startWrite()
        try {
            out.write(if (enabled) 1 else 0)
            setting.finishWrite(out)
            check(this.enabled(id) == enabled) { "Plugin setting was not saved" }
        } catch (e: Exception) {
            setting.failWrite(out)
            throw e
        }
    }
}
