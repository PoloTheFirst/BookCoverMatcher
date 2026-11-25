// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher

import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherIconDeviceTest {
    @Test fun originalLauncherEntryResolvesBothFullColorIcons() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(app.packageName)
        val info = app.packageManager.queryIntentActivities(launcher, 0).single().activityInfo
        assertEquals(ComponentName(app, MainActivity::class.java), ComponentName(info.packageName, info.name))
        for ((mode, tile) in listOf(Configuration.UI_MODE_NIGHT_NO to "#FAF5EF", Configuration.UI_MODE_NIGHT_YES to "#80435C")) {
            val config = Configuration(app.resources.configuration)
            config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
            val context = app.createConfigurationContext(config)
            val icon = context.getDrawable(info.iconResource) as AdaptiveIconDrawable
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            icon.setBounds(0, 0, 96, 96)
            icon.draw(Canvas(bitmap))
            val pixels = IntArray(96 * 96)
            bitmap.getPixels(pixels, 0, 96, 0, 0, 96, 96)
            assertTrue("Missing $tile launcher background", pixels.count { it == Color.parseColor(tile) } > 1000)
            val page = Color.parseColor(if (mode == Configuration.UI_MODE_NIGHT_NO) "#FEDBE2" else "#FDB1CB")
            assertTrue("Missing matching book foreground", pixels.count { it == page } > 150)
        }
    }
}
