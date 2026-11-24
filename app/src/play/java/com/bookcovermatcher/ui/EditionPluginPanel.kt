// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.ui

import androidx.compose.runtime.Composable
import com.bookcovermatcher.plugins.PluginManager
import com.bookcovermatcher.plugins.PluginRow

@Composable
internal fun EditionPluginPanel(row: PluginRow, registry: List<PluginRow>, imported: Set<String>, manager: PluginManager) = Unit

@Composable
internal fun EditionImportHelp(onOpenUrl: (String) -> Unit) = Unit
