// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.data

import android.content.Context
import android.content.SharedPreferences

internal data class PalettePreferences(
    val phone: Boolean = false,
    val custom: Boolean = false,
    val hue: Int = 348,
)

/** Local app preferences shared between editions. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("bookcover_matcher", Context.MODE_PRIVATE)

    /** Whether Auto Scan is switched on. Restored on launch and, unlike v1, really re-armed. */
    var autoScan: Boolean
        get() = sp.getBoolean(KEY_AUTO_SCAN, false)
        set(value) { sp.edit().putBoolean(KEY_AUTO_SCAN, value).apply() }

    var range: String
        get() = sp.getString(KEY_RANGE, null) ?: com.bookcovermatcher.core.Config.DEFAULT_RANGE
        set(value) { sp.edit().putString(KEY_RANGE, value).apply() }

    var displayModeId: String?
        get() = sp.getString(KEY_DISPLAY_MODE, null)
        set(value) { sp.edit().putString(KEY_DISPLAY_MODE, value).apply() }

    internal var palette: PalettePreferences
        get() = PalettePreferences(
            phone = sp.getBoolean(KEY_PHONE, false),
            custom = sp.getBoolean(KEY_CUSTOM, false),
            hue = sp.getInt(KEY_HUE, 348).coerceIn(0, 360),
        )
        set(value) {
            sp.edit().putBoolean(KEY_PHONE, value.phone).putBoolean(KEY_CUSTOM, value.custom)
                .putInt(KEY_HUE, value.hue.coerceIn(0, 360)).apply()
        }

    fun observeDisplay(onChange: () -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in DISPLAY_KEYS) onChange()
        }
        sp.registerOnSharedPreferenceChangeListener(listener)
        onChange()
        return { sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private companion object {
        const val KEY_DISPLAY_MODE = "display_mode"
        const val KEY_PHONE = "display_phone_colors"
        const val KEY_CUSTOM = "display_custom_palette"
        const val KEY_HUE = "display_palette_hue"
        val DISPLAY_KEYS = setOf(KEY_DISPLAY_MODE, KEY_PHONE, KEY_CUSTOM, KEY_HUE)
        const val KEY_AUTO_SCAN = "bcm_autoscan_v1"
        const val KEY_RANGE = "bcm_range"
    }
}
