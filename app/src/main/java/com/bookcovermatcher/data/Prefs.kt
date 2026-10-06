package com.bookcovermatcher.data

import android.content.Context

/** Tiny settings store (the v1 app kept the same two values in `localStorage`). */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("bookcover_matcher", Context.MODE_PRIVATE)

    /** Whether Auto Scan is switched on. Restored on launch and, unlike v1, really re-armed. */
    var autoScan: Boolean
        get() = sp.getBoolean(KEY_AUTO_SCAN, false)
        set(value) { sp.edit().putBoolean(KEY_AUTO_SCAN, value).apply() }

    var range: String
        get() = sp.getString(KEY_RANGE, null) ?: com.bookcovermatcher.core.Config.DEFAULT_RANGE
        set(value) { sp.edit().putString(KEY_RANGE, value).apply() }

    private companion object {
        const val KEY_AUTO_SCAN = "bcm_autoscan_v1"
        const val KEY_RANGE = "bcm_range"
    }
}
