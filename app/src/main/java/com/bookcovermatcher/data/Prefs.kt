// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.data

import android.content.Context
import android.content.SharedPreferences

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

    fun observeDisplayMode(onChange: (String?) -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_DISPLAY_MODE || key == null) onChange(displayModeId)
        }
        sp.registerOnSharedPreferenceChangeListener(listener)
        onChange(displayModeId)
        return { sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private companion object {
        const val KEY_DISPLAY_MODE = "display_mode"
        const val KEY_AUTO_SCAN = "bcm_autoscan_v1"
        const val KEY_RANGE = "bcm_range"
    }
}
