// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.ui.theme

import android.content.res.Configuration
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcovermatcher.MainActivity
import com.bookcovermatcher.data.PalettePreferences
import com.bookcovermatcher.data.Prefs
import com.bookcovermatcher.ui.DisplayButton
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class DisplayPaletteDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = Prefs(context)

    private fun await(label: String, matches: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (!matches()) {
            if (SystemClock.uptimeMillis() >= deadline) fail(label)
            SystemClock.sleep(80)
        }
    }

    private fun withDefaults(run: () -> Unit) {
        val mode = prefs.displayModeId
        val colors = prefs.palette
        try {
            prefs.displayModeId = null
            prefs.palette = PalettePreferences()
            run()
        } finally {
            prefs.displayModeId = mode
            prefs.palette = colors
        }
    }

    @Test fun paletteAndAppearanceAreIndependentAndSurviveRecreation() = withDefaults {
        val actual = AtomicReference<Color>()
        ActivityScenario.launch(MainActivity::class.java).use { screen ->
            fun observe() = screen.onActivity { activity ->
                activity.setContent {
                    val display = rememberDisplaySettings()
                    BookCoverTheme(display.mode.isDark(), display.palette) {
                        val primary = MaterialTheme.colorScheme.primary
                        SideEffect { actual.set(primary) }
                    }
                }
            }
            fun expect(label: String, color: Color) = await(label) { actual.get() == color }

            observe()
            val systemDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val sophieLight = Color(0xFF80435C)
            val sophieDark = Color(0xFFFDB1CB)
            expect("Sophie follows system on first use", if (systemDark) sophieDark else sophieLight)
            prefs.displayModeId = "light"
            expect("Explicit light", sophieLight)
            prefs.palette = PalettePreferences(custom = true, hue = 140)
            val customLight = hueColor(140, 40, 40)
            val customDark = hueColor(140, 40, 80)
            expect("Live custom hue", customLight)
            prefs.palette = prefs.palette.copy(phone = true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                expect("Phone palette in light mode", dynamicLightColorScheme(context).primary)
            } else expect("Manual palette fallback", customLight)
            prefs.displayModeId = "dark"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                expect("Phone palette in dark mode", dynamicDarkColorScheme(context).primary)
            } else expect("Dark manual palette fallback", customDark)
            prefs.palette = prefs.palette.copy(phone = false)
            expect("Restored custom hue", customDark)
            actual.set(null)
            screen.recreate()
            observe()
            expect("Recreated custom palette", customDark)
            prefs.palette = prefs.palette.copy(custom = false)
            expect("Reset to dark Sophie", sophieDark)
            assertEquals("dark", prefs.displayModeId)
            assertEquals(140, prefs.palette.hue)
        }
    }

    @Test fun displayDialogExposesManualAndSystemColorChoices() = withDefaults {
        ActivityScenario.launch(MainActivity::class.java).use { screen ->
            screen.onActivity { activity ->
                activity.setContent {
                    val display = rememberDisplaySettings()
                    BookCoverTheme(display.mode.isDark(), display.palette) {
                        CompositionLocalProvider(LocalDisplaySettings provides display) { DisplayButton() }
                    }
                }
            }
            fun descendants(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
                yield(node)
                for (i in 0 until node.childCount) node.getChild(i)?.let { yieldAll(descendants(it)) }
            }
            fun control(label: String): AccessibilityNodeInfo? {
                val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
                val node = descendants(root).firstOrNull {
                    it.text?.toString()?.lines()?.contains(label) == true || it.contentDescription?.toString() == label
                }
                return generateSequence(node) { it.parent }.firstOrNull { it.isClickable || it.isCheckable }
            }
            fun tap(label: String) = await("Tap $label") {
                control(label)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            tap("Display")
            tap("Custom palette")
            await("Custom selected") { prefs.palette.custom }
            fun nodes(): Sequence<AccessibilityNodeInfo> =
                instrumentation.uiAutomation.rootInActiveWindow?.let(::descendants) ?: emptySequence()
            fun scroll(action: Int) {
                nodes().firstOrNull { it.isScrollable }?.performAction(action)
                instrumentation.waitForIdleSync()
            }
            scroll(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            await("Set hue with slider") {
                val slider = nodes().firstOrNull { it.rangeInfo != null }
                val arguments = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 210f) }
                slider?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, arguments) == true
            }
            await("Slider hue saved") { prefs.palette.hue == 210 }
            await("Enter hex color") {
                val field = nodes().firstOrNull { it.isEditable }
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "#287C85")
                }
                field?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) == true
            }
            tap("Apply")
            val hct = FloatArray(3)
            ColorUtils.colorToM3HCT(0xFF287C85.toInt(), hct)
            await("Hex hue saved") { prefs.palette.hue == kotlin.math.round(hct[0]).toInt() }
            scroll(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                tap("Use phone colors")
                await("Phone selected") { prefs.palette.phone && control("Custom palette") == null }
                tap("Use phone colors")
                await("Manual palette restored") { !prefs.palette.phone && control("Custom palette") != null }
            } else {
                await("Older Android shows disabled phone control") { control("Use phone colors")?.isEnabled == false }
            }
            tap("Dark")
            tap("Sophie palette")
            tap("Done")
            await("Dialog closed with chosen preferences") {
                control("Done") == null && prefs.displayModeId == "dark" && !prefs.palette.custom
            }
        }
    }

    @Test fun customPalettesHaveReadableTextAcrossHueRange() {
        for (dark in listOf(false, true)) {
            val base = if (dark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()
            for (hue in 0..360 step 15) {
                val scheme = customColorScheme(base, hue, dark)
                for ((ink, paper) in listOf(scheme.onSurface to scheme.surface, scheme.onPrimary to scheme.primary,
                    scheme.onSecondary to scheme.secondary, scheme.onTertiaryContainer to scheme.tertiaryContainer)) {
                    assertTrue("Hue $hue dark=$dark", ColorUtils.calculateContrast(ink.toArgb(), paper.toArgb()) >= 4.5)
                }
            }
            assertNotEquals(customColorScheme(base, 100, dark).primary, customColorScheme(base, 280, dark).primary)
        }
    }
}
