// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bookcovermatcher.plugins.ActiveMarketplace
import com.bookcovermatcher.plugins.PluginRow
import com.bookcovermatcher.plugins.marketplaceEntries
import com.bookcovermatcher.ui.components.Panel
import com.bookcovermatcher.ui.theme.Palette
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun EditionPluginPanel(row: PluginRow, registry: List<PluginRow>, imported: Set<String>) {
    if (row.contribution !== ActiveMarketplace) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    Button(onClick = { expanded = !expanded }) { Text(if (expanded) "Close marketplace" else "Marketplace") }
    if (expanded) MarketplaceCatalogue(registry, imported)
}

@Composable
private fun MarketplaceCatalogue(registry: List<PluginRow>, imported: Set<String>) {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var pendingBytes by rememberSaveable { mutableStateOf<ByteArray?>(null) }
    var suggestedName by rememberSaveable { mutableStateOf("") }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pendingBytes
        pendingBytes = null
        if (uri == null) {
            message = "Save cancelled."
        } else if (bytes == null) {
            message = "Select Save descriptor to try again."
        } else {
            working = true
            scope.launch {
                try {
                    val filename = withContext(Dispatchers.IO) { saveDescriptor(resolver, uri, bytes, suggestedName) }
                    message = "Saved $filename. Open Plugins → Import plugin and choose the saved file."
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { message = "Could not write the descriptor. Try another location." }
                finally { working = false }
            }
        }
    }

    message?.let { Text(it, color = Palette.Text) }
    for (entry in marketplaceEntries) {
        Spacer(Modifier.height(8.dp))
        Panel(entry.name) {
            Text(entry.summary, color = Palette.Text)
            Text(entry.availability(registry, imported), color = Palette.Muted)
            Button(
                enabled = !working && pendingBytes == null,
                modifier = Modifier.semantics { contentDescription = "Save descriptor for ${entry.name}" },
                onClick = {
                    working = true
                    message = "Fetching ${entry.filename}…"
                    scope.launch {
                        try {
                            val bytes = withContext(Dispatchers.IO) { entry.download() }
                            pendingBytes = bytes
                            suggestedName = entry.filename
                            message = null
                            save.launch(entry.filename)
                        } catch (e: CancellationException) { throw e }
                        catch (_: IllegalArgumentException) {
                            pendingBytes = null
                            message = "The downloaded file did not match this plugin."
                        } catch (_: Exception) {
                            pendingBytes = null
                            message = "Could not fetch or save ${entry.filename}. Check your connection and try again."
                        } finally { working = false }
                    }
                },
            ) { Text("Save descriptor") }
        }
    }
}

private fun saveDescriptor(resolver: ContentResolver, uri: Uri, bytes: ByteArray, suggestedName: String): String {
    require(uri.scheme == ContentResolver.SCHEME_CONTENT)
    val stream = resolver.openOutputStream(uri, "wt") ?: throw IOException("Document unavailable")
    stream.use { it.write(bytes) }
    return runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: suggestedName
}
