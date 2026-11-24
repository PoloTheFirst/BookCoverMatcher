// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.plugins

import java.io.InputStream

/** Small data-only descriptor. Accept only the format version and one stable plugin ID. */
internal fun readPluginId(input: InputStream): String = readPluginFile(input).id

internal class PluginFile(val id: String, val bytes: ByteArray)

internal fun readPluginFile(input: InputStream): PluginFile {
    val buffer = ByteArray(257)
    var used = 0
    while (used < buffer.size) {
        val count = input.read(buffer, used, buffer.size - used)
        if (count < 0) break
        require(count > 0)
        used += count
    }
    require(used < buffer.size)
    val lines = buffer.decodeToString(0, used).replace("\r\n", "\n").removeSuffix("\n").lines()
    require(lines.size == 2 && lines[0] == "BCM-PLUGIN/1")
    val id = lines[1]
    require(id.matches(Regex("[a-z][a-z0-9_.-]{0,79}")))
    return PluginFile(id, buffer.copyOf(used))
}
