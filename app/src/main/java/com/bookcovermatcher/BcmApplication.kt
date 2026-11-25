// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.res.Configuration

class BcmApplication : Application() {
    private var lastLauncherNightMode: Int? = null

    override fun onCreate() {
        super.onCreate()
        refreshLauncherIcon(resources.configuration)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshLauncherIcon(newConfig)
    }

    private fun refreshLauncherIcon(configuration: Configuration) {
        val nightMode = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (nightMode == Configuration.UI_MODE_NIGHT_UNDEFINED || lastLauncherNightMode == nightMode) return

        // A launcher can still have the old bitmap when our process starts. Changing the
        // enabled setting of the same, manifest-enabled activity asks it to reload the icon
        // while keeping the original home-screen component continuously launchable.
        val entry = ComponentName(this, MainActivity::class.java)
        val next = when (packageManager.getComponentEnabledSetting(entry)) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            else -> return
        }
        packageManager.setComponentEnabledSetting(entry, next, PackageManager.DONT_KILL_APP)
        lastLauncherNightMode = nightMode
    }
}
