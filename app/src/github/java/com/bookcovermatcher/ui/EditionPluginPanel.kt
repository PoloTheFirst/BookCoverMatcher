// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.TextButton
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
    Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (entry in marketplaceEntries) key(entry.id) {
            val child = registry.firstOrNull { it.descriptor.id == entry.id }
            if (child != null && entry.id in imported) {
                PluginSwitchCard(child, manager)
            } else if (active) {
                Surface(
                    Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(entry.name, style = MaterialTheme.typography.titleSmall)
                        Text(entry.summary, style = MaterialTheme.typography.bodySmall)
                        when (child?.status) {
                            null, PluginStatus.UNAVAILABLE -> Text("Requires a GitHub build containing this plugin")
                            PluginStatus.FETCHING -> Text("Downloading…")
                            else -> {
                                child.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                FilledTonalButton(onClick = { manager.download(entry.id) }) {
                                    Text(if (child.status == PluginStatus.FAILED) "Retry download" else "Download")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun EditionImportHelp(onOpenUrl: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide import guide" else "How to import plugins") }
    AnimatedVisibility(expanded) {
        Panel("Import guide") {
            Text("Set up the folders on your phone", color = Palette.Text)
            Text("1. Open the Files app. Tap Internal storage, then Documents.", color = Palette.Muted)
            Text("2. In Documents, create a folder named BookCoverMatcher. Open the BookCoverMatcher folder.", color = Palette.Muted)
            Text("3. In BookCoverMatcher, create a folder named Plugins. Open the Plugins folder. If either folder already exists, open the existing folder.", color = Palette.Muted)
            Text("Save your .bcmplugin files in Internal storage/$PLUGIN_DOCUMENTS_FOLDER", color = Palette.Text)
            Text("Import Plugin Marketplace in BCM", Modifier.padding(top = 12.dp), color = Palette.Text)
            Text("marketplace.bcmplugin is the setup file that adds the Plugin Marketplace switch. If you already see that switch in BCM, turn it on.", color = Palette.Muted)
            Text("1. Tap Get marketplace.bcmplugin below. On GitHub, choose Download raw file. If the file lands in Downloads, use the Files app to move it to the Plugins folder you opened above.", color = Palette.Muted)
            TextButton(onClick = { onOpenUrl("https://github.com/PoloTheFirst/BookCoverMatcher/blob/plugin/plugin-marketplace/plugins/manifests/marketplace.bcmplugin") }) {
                Text("Get marketplace.bcmplugin")
            }
            Text("2. Return to BCM. Tap the Plugins tab (the puzzle piece next to More), then tap Import plugin. In the file picker, open Documents → BookCoverMatcher → Plugins and select marketplace.bcmplugin. If you already see the file, tap it.", color = Palette.Muted)
            Text("3. Back in BCM, turn on Plugin Marketplace. When the downloads finish, turn on a plugin such as Ordinary Library Fetch.", color = Palette.Muted)
            Text("From a custom plugin author", Modifier.padding(top = 12.dp), color = Palette.Text)
            Text("1. Check that the plugin works with your version of BCM. Save the author’s .bcmplugin file in the same $PLUGIN_DOCUMENTS_FOLDER folder.", color = Palette.Muted)
            Text("2. Tap Import plugin, choose the file and turn on its switch. If BCM says to manage it in Plugin Marketplace, use that group’s marketplace switch.", color = Palette.Muted)
            Text("Return to Search → Online. Enabled platforms appear in order automatically.", Modifier.padding(top = 8.dp), color = Palette.Text)
        }
    }
}
