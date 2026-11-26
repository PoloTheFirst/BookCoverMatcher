// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import androidx.core.graphics.ColorUtils
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcovermatcher.data.PalettePreferences
import com.bookcovermatcher.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherIconDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val prefs = Prefs(app)
    private val day = Configuration.UI_MODE_NIGHT_NO
    private val night = Configuration.UI_MODE_NIGHT_YES

    private fun select(palette: PalettePreferences) {
        instrumentation.runOnMainSync { prefs.palette = palette }
    }

    private fun onlyLauncher(name: String): ActivityInfo {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(app.packageName)
        val info = app.packageManager.queryIntentActivities(launcher, 0).single().activityInfo
        assertEquals("${app.packageName}.launcher.$name", info.name)
        assertEquals(MainActivity::class.java.name, info.targetActivity)
        assertNotNull(app.packageManager.getActivityInfo(ComponentName(app, MainActivity::class.java), 0))
        return info
    }

    private fun pixels(info: ActivityInfo, mode: Int): IntArray {
        val config = Configuration(app.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
        val context = app.createConfigurationContext(config)
        val icon = context.getDrawable(info.iconResource) as AdaptiveIconDrawable
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) assertNotNull(icon.monochrome)
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 96, 96)
        icon.draw(Canvas(bitmap))
        val pixels = IntArray(96 * 96)
        bitmap.getPixels(pixels, 0, 96, 0, 0, 96, 96)
        bitmap.recycle()
        return pixels
    }

    @Test fun sophieLauncherKeepsTheOriginalDayAndNightArtwork() {
        val previous = prefs.palette
        try {
            select(PalettePreferences())
            val info = onlyLauncher("Sophie")
            for ((mode, tile) in listOf(day to "#FAF5EF", night to "#80435C")) {
                val pixels = pixels(info, mode)
                assertTrue("Missing $tile launcher background", pixels.count { it == Color.parseColor(tile) } > 1000)
                val page = Color.parseColor(if (mode == day) "#FEDBE2" else "#FDB1CB")
                assertTrue("Missing matching book foreground", pixels.count { it == page } > 150)
            }
        } finally {
            select(previous)
        }
    }

    @Test fun savingAnAccentSelectsOneLauncherWithTheSameHueInDayAndNight() {
        val previous = prefs.palette
        try {
            for (stored in listOf(37, 182, 359, 360, 0)) {
                select(PalettePreferences(custom = true, hue = stored))
                val hue = stored % 360
                val info = onlyLauncher("Hue$hue")
                for ((mode, tone) in listOf(day to 90f, night to 30f)) {
                    val tile = ColorUtils.M3HCTToColor(hue.toFloat(), 40f, tone)
                    assertTrue("Hue $hue mode $mode", pixels(info, mode).count { it == tile } > 1000)
                }
            }
            val colors = PalettePreferences(phone = true, custom = true, hue = 270)
            select(colors)
            onlyLauncher(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "Sophie" else "Hue270")
            select(colors.copy(phone = false))
            onlyLauncher("Hue270")
            select(colors.copy(phone = false, custom = false))
            onlyLauncher("Sophie")
        } finally {
            select(previous)
        }
    }
}
