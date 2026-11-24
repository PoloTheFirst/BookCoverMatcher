// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.bookcovermatcher.data.PalettePreferences
import com.bookcovermatcher.data.Prefs

internal enum class DisplayMode(val key: String, val label: String) {
    SYSTEM("system", "Follow system"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    companion object {
        fun fromPreference(key: String?): DisplayMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

@Composable
internal fun DisplayMode.isDark(): Boolean = when (this) {
    DisplayMode.SYSTEM -> isSystemInDarkTheme()
    DisplayMode.LIGHT -> false
    DisplayMode.DARK -> true
}

internal class DisplaySettings(
    val mode: DisplayMode,
    val select: (DisplayMode) -> Unit,
    val palette: PalettePreferences,
    val setPalette: (PalettePreferences) -> Unit,
)

internal val LocalDisplaySettings = compositionLocalOf<DisplaySettings> {
    error("Display settings must be supplied at the app root")
}

@Composable
internal fun rememberDisplaySettings(): DisplaySettings {
    val app = LocalContext.current.applicationContext
    val prefs = remember(app) { Prefs(app) }
    var mode by remember(prefs) { mutableStateOf(DisplayMode.fromPreference(prefs.displayModeId)) }
    var palette by remember(prefs) { mutableStateOf(prefs.palette) }
    DisposableEffect(prefs) {
        val stopObserving = prefs.observeDisplay {
            mode = DisplayMode.fromPreference(prefs.displayModeId)
            palette = prefs.palette
        }
        onDispose(stopObserving)
    }
    return DisplaySettings(mode, { prefs.displayModeId = it.key }, palette, { prefs.palette = it })
}
