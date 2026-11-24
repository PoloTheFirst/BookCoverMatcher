// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import com.bookcovermatcher.ui.theme.DisplayMode
import com.bookcovermatcher.ui.theme.LocalDisplaySettings
import com.bookcovermatcher.ui.theme.hueColor
import kotlin.math.roundToInt

@Composable
internal fun DisplayButton() {
    val settings = LocalDisplaySettings.current
    var open by rememberSaveable { mutableStateOf(false) }
    val colors = settings.palette
    val supportsPhoneColors = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val phoneColors = supportsPhoneColors && colors.phone
    val colorLabel = when {
        phoneColors -> "Phone colors"
        colors.custom -> "Custom palette"
        else -> "Sophie palette"
    }
    ListItem(
        headlineContent = { Text("Display") },
        supportingContent = { Text("${settings.mode.label} · $colorLabel") },
        trailingContent = {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        },
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
            .clickable(role = Role.Button) { open = true },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Display") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Appearance", style = MaterialTheme.typography.titleSmall)
                    Column(Modifier.selectableGroup()) {
                        for (mode in DisplayMode.entries) {
                            DisplayChoice(mode.label, settings.mode == mode) { settings.select(mode) }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("Colors", style = MaterialTheme.typography.titleSmall)
                    Row(
                        Modifier.fillMaxWidth().toggleable(
                            value = phoneColors, enabled = supportsPhoneColors, role = Role.Switch,
                            onValueChange = { settings.setPalette(colors.copy(phone = it)) },
                        ).heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("Use phone colors")
                            Text(
                                if (supportsPhoneColors) "Use the Android system palette."
                                else "Available on Android 12 and newer.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = phoneColors, onCheckedChange = null, enabled = supportsPhoneColors)
                    }
                    if (!phoneColors) {
                        Column(Modifier.selectableGroup()) {
                            DisplayChoice("Sophie palette", !colors.custom) { settings.setPalette(colors.copy(custom = false)) }
                            DisplayChoice("Custom palette", colors.custom) { settings.setPalette(colors.copy(custom = true)) }
                        }
                        if (colors.custom) {
                            CustomHuePicker(colors.hue) { settings.setPalette(colors.copy(hue = it)) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Done") } },
        )
    }
}

@Composable
private fun DisplayChoice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onSelect)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

private val hexColorPattern = Regex("[a-fA-F0-9]{6}")

@Composable
private fun CustomHuePicker(hue: Int, select: (Int) -> Unit) {
    var preview by remember(hue) { mutableStateOf(hue) }
    var hex by rememberSaveable { mutableStateOf("") }
    val rgb = hex.trim().removePrefix("#").takeIf { it.matches(hexColorPattern) }?.toInt(16)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(24.dp).background(hueColor(preview), CircleShape))
        Text("Accent hue · $preview°")
    }
    Slider(
        value = preview.toFloat(), valueRange = 0f..360f,
        onValueChange = { preview = it.roundToInt() },
        onValueChangeFinished = { select(preview) },
        modifier = Modifier.semantics { contentDescription = "Accent hue" },
    )
    OutlinedTextField(
        value = hex, onValueChange = { hex = it }, singleLine = true,
        label = { Text("Use hue from hex color") }, placeholder = { Text("#80435C") },
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            TextButton(enabled = rgb != null, onClick = {
                if (rgb != null) {
                    val hct = FloatArray(3)
                    ColorUtils.colorToM3HCT(rgb or 0xFF000000.toInt(), hct)
                    select(hct[0].roundToInt())
                    hex = ""
                }
            }) { Text("Apply") }
        },
    )
}
