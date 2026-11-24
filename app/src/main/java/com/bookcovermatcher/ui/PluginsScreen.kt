// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.ui

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookcovermatcher.plugins.PluginManager
import com.bookcovermatcher.plugins.PluginStatus
import com.bookcovermatcher.plugins.card
import com.bookcovermatcher.plugins.readPluginId
import com.bookcovermatcher.ui.components.Panel
import kotlinx.coroutines.*

internal const val PLUGIN_DOCUMENTS_FOLDER = "Documents/BookCoverMatcher/Plugins"

private object OpenPluginDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(
            DocumentsContract.EXTRA_INITIAL_URI,
            DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:$PLUGIN_DOCUMENTS_FOLDER",
            ),
        )
}

@Composable
internal fun PluginsScreen(manager: PluginManager) {
    val imported = manager.imported.collectAsStateWithLifecycle().value
    val rows = manager.rows.collectAsStateWithLifecycle().value.filter { it.descriptor.id in imported }
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(OpenPluginDocument) { uri ->
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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Plugins", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
        Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import plugin") }
        message?.let {
            Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium)
        }
        for (row in rows) key(row.descriptor.id) {
            PluginSettingRow(
                name = row.descriptor.name,
                summary = row.descriptor.description,
                checked = row.status == PluginStatus.ENABLED,
                enabled = row.status in setOf(PluginStatus.DISABLED, PluginStatus.ENABLED, PluginStatus.FAILED),
                status = when (row.status) {
                    PluginStatus.ENABLING -> "Enabling…"
                    PluginStatus.DISABLING -> "Disabling…"
                    else -> row.message
                },
                onToggle = { manager.toggle(row.descriptor.id, it) },
            )
            row.card?.let { card ->
                Panel(card.title) { Text(card.body, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        if (rows.isEmpty()) PluginEmptyState(
            "No imported plugins",
            "This build includes no plugins. Import accepts only descriptors supported by this edition.",
        )
    }
}
