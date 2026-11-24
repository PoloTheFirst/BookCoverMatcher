// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bookcovermatcher.ProviderState
import com.bookcovermatcher.ProviderStatusUi
import com.bookcovermatcher.core.online.SearchLimits

@Composable
fun PlatformPicker(
    providers: List<ProviderStatusUi>,
    onToggle: (String) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    if (providers.isEmpty()) return
    val selected = providers.count { it.selected }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Platforms", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        Text("$selected of ${providers.size} selected", style = MaterialTheme.typography.labelLarge)
        providers.forEach { provider -> PlatformCard(provider, onToggle, onOpenLink) }
        Text("Selected platforms are included in the next search. Browser-only platforms open in your browser.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlatformCard(provider: ProviderStatusUi, onToggle: (String) -> Unit, onOpen: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val selected = provider.selected
    Surface(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        color = if (selected) colors.secondaryContainer else colors.surfaceContainer,
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // A shortcut has only the browser action. The checkbox and browser button are sibling targets.
            val selection = if (provider.manual) Modifier else Modifier.toggleable(
                value = selected, role = Role.Checkbox, onValueChange = { onToggle(provider.id) },
            )
            Row(
                Modifier.weight(1f).then(selection).heightIn(min = 48.dp).padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!provider.manual) Checkbox(checked = selected, onCheckedChange = null)
                Column {
                    Text(provider.name, style = MaterialTheme.typography.titleSmall)
                    Text(provider.host, style = MaterialTheme.typography.bodySmall)
                    val status = when (provider.state) {
                        ProviderState.RESULTS -> "Results"
                        ProviderState.EMPTY -> "No match"
                        ProviderState.FAILED -> "Failed"
                        ProviderState.BLOCKED -> "Blocked"
                        ProviderState.SEARCHING -> "Searching"
                        ProviderState.MANUAL -> "Browser only"
                        ProviderState.SKIPPED -> "Not selected"
                        ProviderState.READY -> if (selected) "Selected" else "Not selected"
                    }
                    Text(status, style = MaterialTheme.typography.labelMedium)
                    provider.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            val url = provider.searchUrl
            if (url != null) {
                TextButton(onClick = { onOpen(url) }, modifier = Modifier.semantics {
                    contentDescription = "Open ${provider.name} search in browser"
                }) { Text("Open ↗") }
            } else {
                Text("No link", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** Set the maximum for online results. Offline cover matching has its own fixed limit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResultLimitPicker(limit: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Online results", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SearchLimits.OPTIONS.forEach { option ->
                FilterChip(selected = option == limit, onClick = { onSelect(option) },
                    modifier = Modifier.heightIn(min = 48.dp), label = { Text(option.toString()) })
            }
        }
        Text("Each catalogue limits how many results it can return. Your maximum is $limit.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
