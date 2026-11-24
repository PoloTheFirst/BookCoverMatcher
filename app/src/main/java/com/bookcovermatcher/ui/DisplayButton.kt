// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bookcovermatcher.ui.theme.DisplayMode
import com.bookcovermatcher.ui.theme.LocalDisplaySettings

@Composable
internal fun DisplayButton() {
    val settings = LocalDisplaySettings.current
    var open by rememberSaveable { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text("Display") },
        supportingContent = { Text(settings.mode.label) },
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
            .clickable(role = Role.Button) { open = true },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Display") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                    for (mode in DisplayMode.entries) {
                        val selected = settings.mode == mode
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected, role = Role.RadioButton) { settings.select(mode) }
                                .heightIn(min = 48.dp).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                                if (mode == DisplayMode.SYSTEM) {
                                    Text("Match the phone’s light or dark appearance.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Done") } },
        )
    }
}
