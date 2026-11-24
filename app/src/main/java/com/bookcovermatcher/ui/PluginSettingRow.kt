// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.bookcovermatcher.R

/** The toggle and Remove are siblings so keyboard and accessibility users can operate them separately. */
@Composable
internal fun PluginSettingRow(
    name: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean,
    status: String?,
    onToggle: (Boolean) -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val active = enabled && checked
    val selection = if (checked) "On" else "Off"
    val state = status ?: selection
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (active) colors.secondaryContainer else colors.surfaceContainer,
        border = if (active) BorderStroke(1.dp, lerp(colors.outlineVariant, colors.primary, 0.38f)) else null,
    ) {
        val foreground = if (active) colors.onSecondaryContainer else colors.onSurface
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.weight(1f)
                    .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onToggle)
                    .semantics { stateDescription = if (status == null) selection else "$selection, $status" }
                    .heightIn(min = 48.dp)
                    .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleSmall,
                        color = if (enabled) foreground else colors.onSurfaceVariant)
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = foreground)
                    Text(state, style = MaterialTheme.typography.labelMedium,
                        color = if (enabled) foreground else colors.onSurfaceVariant)
                }
                Switch(checked = checked, onCheckedChange = null, enabled = enabled)
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove $name", tint = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun PluginEmptyState(title: String, summary: String) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = colors.surfaceContainer) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_puzzle), null, Modifier.size(32.dp), tint = colors.primary)
            Column {
                Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                Text(summary, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
