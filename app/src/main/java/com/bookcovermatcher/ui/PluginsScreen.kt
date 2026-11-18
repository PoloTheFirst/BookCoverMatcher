// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Button
import com.bookcovermatcher.plugins.readPluginId
import kotlinx.coroutines.*
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookcovermatcher.plugins.PluginManager
import com.bookcovermatcher.plugins.card
import com.bookcovermatcher.plugins.PluginStatus
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookcovermatcher.R
import com.bookcovermatcher.ui.components.Panel
import com.bookcovermatcher.ui.theme.Palette

@Composable
internal fun PluginsScreen(manager: PluginManager) {
    val imported = manager.imported.collectAsStateWithLifecycle().value
    val registry = manager.rows.collectAsStateWithLifecycle().value
    val rows = registry.filter { it.descriptor.id in imported }
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            message = try {
                val id = withContext(Dispatchers.IO) {
                    require(uri.scheme == "content")
                    requireNotNull(resolver.openInputStream(uri)).use(::readPluginId)
                }
                manager.importDescriptor(id)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { "Import failed. Choose a valid .bcmplugin file and try again." }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Text("Plugins", color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(12.dp))
        Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import plugin") }
        message?.let { Text(it, color = Palette.Text) }
        Spacer(Modifier.height(12.dp))
        for (row in rows) {
            val descriptor = row.descriptor
            val active = row.status == PluginStatus.ENABLED
            val interactive = row.status in setOf(PluginStatus.ENABLED, PluginStatus.DISABLED, PluginStatus.FAILED)
            Panel("Bundled plugin") {
                Row(
                    Modifier.fillMaxWidth().toggleable(
                        value = active, enabled = interactive, role = Role.Switch,
                        onValueChange = { manager.toggle(descriptor.id, it) },
                    ).padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ic_puzzle), null, tint = Palette.Accent)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(descriptor.name, color = Palette.Text, fontWeight = FontWeight.Bold)
                        Text(descriptor.description, color = Palette.Muted)
                        val status = when (row.status) {
                            PluginStatus.ENABLING -> "Enabling…"
                            PluginStatus.DISABLING -> "Disabling…"
                            else -> row.message
                        }
                        if (status != null) Text(status, color = Palette.Text)
                    }
                    Switch(checked = active, onCheckedChange = null, enabled = interactive)
                }
            }
            key(descriptor.id) { EditionPluginPanel(row, registry, imported) }
            row.card?.let { card ->
                Spacer(Modifier.height(12.dp))
                Panel(card.title) { Text(card.body, color = Palette.Text) }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (rows.isEmpty()) Panel("Plugin support") {
            Icon(
                painter = painterResource(R.drawable.ic_puzzle),
                contentDescription = null,
                tint = Palette.Accent,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text("No imported plugins", color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Choose a .bcmplugin file to add a plugin supported by this edition.",
                color = Palette.Muted,
                fontSize = 13.sp,
            )
        }
    }
}
