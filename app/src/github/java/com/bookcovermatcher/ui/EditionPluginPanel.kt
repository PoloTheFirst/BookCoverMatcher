// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bookcovermatcher.plugins.PluginManager
import com.bookcovermatcher.plugins.PluginRow
import com.bookcovermatcher.plugins.PluginStatus
import com.bookcovermatcher.plugins.marketplaceEntries
import com.bookcovermatcher.ui.components.Panel
import com.bookcovermatcher.ui.theme.Palette

@Composable
internal fun EditionPluginPanel(row: PluginRow, registry: List<PluginRow>, imported: Set<String>, manager: PluginManager) {
    if (row.descriptor.id != manager.catalogue?.parent) return
    val active = row.status == PluginStatus.ENABLED
    Column(Modifier.padding(start = 12.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (entry in marketplaceEntries) key(entry.id) {
            val child = registry.firstOrNull { it.descriptor.id == entry.id }
            if (child != null && entry.id in imported) {
                PluginSwitchCard(child, manager)
            } else if (active) {
                Panel(null) {
                    Text(entry.name, color = Palette.Text)
                    Text(entry.summary, color = Palette.Muted)
                    when (child?.status) {
                        null, PluginStatus.UNAVAILABLE -> Text("Requires a GitHub build containing this plugin", color = Palette.Muted)
                        PluginStatus.FETCHING -> Text("Downloading…", color = Palette.Text)
                        else -> {
                            child.message?.let { Text(it, color = Palette.Text) }
                            Button(onClick = { manager.download(entry.id) }) {
                                Text(if (child.status == PluginStatus.FAILED) "Retry download" else "Download")
                            }
                        }
                    }
                }
            }
        }
    }
}
