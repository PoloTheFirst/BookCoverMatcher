// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
import android.content.pm.PackageManager.DONT_KILL_APP
import android.content.res.Configuration
import android.os.Build
import com.bookcovermatcher.data.PalettePreferences
import com.bookcovermatcher.data.Prefs

internal fun launcherComponent(context: Context, palette: PalettePreferences): ComponentName {
    val phoneColors = palette.phone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val entry = if (palette.custom && !phoneColors) "Hue${palette.hue % 360}" else "Sophie"
    return ComponentName(context, "${context.packageName}.launcher.$entry")
}

class BcmApplication : Application() {
    private lateinit var prefs: Prefs
    private var stopObservingDisplay: (() -> Unit)? = null
    private var selectedIcon: ComponentName? = null
    private var lastLauncherNightMode: Int? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        // SharedPreferences weakly references listeners. Retain this registration for the process.
        stopObservingDisplay = prefs.observeDisplay { refreshLauncherIcon(resources.configuration) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshLauncherIcon(newConfig)
    }

    override fun onTerminate() {
        stopObservingDisplay?.invoke()
        super.onTerminate()
    }

    private fun refreshLauncherIcon(configuration: Configuration) {
        val desired = launcherComponent(this, prefs.palette)
        val sophie = launcherComponent(this, PalettePreferences())
        val nightMode = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (selectedIcon == desired && lastLauncherNightMode == nightMode) return

        // Read the actual enabled aliases on startup as well. An older Android process can be
        // interrupted between enabling the new entry and retiring the old one.
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
        val enabled = packageManager.queryIntentActivities(launcher, 0)
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
            .filter { it.className.startsWith("$packageName.launcher.") }
        val changes = buildList {
            if (desired !in enabled) {
                add(desired to if (desired == sophie) COMPONENT_ENABLED_STATE_DEFAULT else COMPONENT_ENABLED_STATE_ENABLED)
            }
            for (old in enabled) {
                if (old != desired) {
                    add(old to if (old == sophie) COMPONENT_ENABLED_STATE_DISABLED else COMPONENT_ENABLED_STATE_DEFAULT)
                }
            }
        }
        if (changes.isNotEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.setComponentEnabledSettings(changes.map { (entry, state) ->
                    PackageManager.ComponentEnabledSetting(entry, state, DONT_KILL_APP)
                })
            } else {
                // Keep a working entry throughout the switch on API 26–32.
                for ((entry, state) in changes) {
                    packageManager.setComponentEnabledSetting(entry, state, DONT_KILL_APP)
                }
            }
        } else if (nightMode != Configuration.UI_MODE_NIGHT_UNDEFINED && lastLauncherNightMode != nightMode) {
            // The alias stays active while the launcher reloads the newly qualified day/night art.
            // Toggle our always-enabled target between equivalent states, as on earlier releases.
            val target = ComponentName(this, MainActivity::class.java)
            val next = when (packageManager.getComponentEnabledSetting(target)) {
                COMPONENT_ENABLED_STATE_DEFAULT -> COMPONENT_ENABLED_STATE_ENABLED
                COMPONENT_ENABLED_STATE_ENABLED -> COMPONENT_ENABLED_STATE_DEFAULT
                else -> null
            }
            if (next != null) packageManager.setComponentEnabledSetting(target, next, DONT_KILL_APP)
        }
        selectedIcon = desired
        lastLauncherNightMode = nightMode
    }
}
