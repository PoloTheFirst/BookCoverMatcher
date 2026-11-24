// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils

internal fun hueColor(hue: Int, chroma: Int = 40, tone: Int = 50) =
    Color(ColorUtils.M3HCTToColor((hue % 360).toFloat(), chroma.toFloat(), tone.toFloat()))

/** The website's hue-based approach, using paired Material foreground/background tones. */
internal fun customColorScheme(base: ColorScheme, hue: Int, dark: Boolean): ColorScheme {
    fun primary(tone: Int) = hueColor(hue, 40, tone)
    fun secondary(tone: Int) = hueColor(hue, 16, tone)
    fun tertiary(tone: Int) = hueColor(hue + 60, 24, tone)
    fun neutral(tone: Int) = hueColor(hue, 6, tone)
    fun variant(tone: Int) = hueColor(hue, 8, tone)
    val accent = if (dark) 80 else 40
    val onAccent = if (dark) 20 else 100
    val container = if (dark) 30 else 90
    val onContainer = if (dark) 90 else 10
    val surface = neutral(if (dark) 6 else 98)
    val onSurface = neutral(if (dark) 90 else 10)
    return base.copy(
        primary = primary(accent), onPrimary = primary(onAccent),
        primaryContainer = primary(container), onPrimaryContainer = primary(onContainer),
        inversePrimary = primary(if (dark) 40 else 80), surfaceTint = primary(accent),
        secondary = secondary(accent), onSecondary = secondary(onAccent),
        secondaryContainer = secondary(container), onSecondaryContainer = secondary(onContainer),
        tertiary = tertiary(accent), onTertiary = tertiary(onAccent),
        tertiaryContainer = tertiary(container), onTertiaryContainer = tertiary(onContainer),
        primaryFixed = primary(90), primaryFixedDim = primary(80),
        onPrimaryFixed = primary(10), onPrimaryFixedVariant = primary(30),
        secondaryFixed = secondary(90), secondaryFixedDim = secondary(80),
        onSecondaryFixed = secondary(10), onSecondaryFixedVariant = secondary(30),
        tertiaryFixed = tertiary(90), tertiaryFixedDim = tertiary(80),
        onTertiaryFixed = tertiary(10), onTertiaryFixedVariant = tertiary(30),
        background = surface, onBackground = onSurface, surface = surface, onSurface = onSurface,
        inverseSurface = neutral(if (dark) 90 else 20), inverseOnSurface = neutral(if (dark) 20 else 95),
        surfaceVariant = variant(if (dark) 30 else 90), onSurfaceVariant = variant(if (dark) 80 else 30),
        outline = variant(if (dark) 60 else 50), outlineVariant = variant(if (dark) 30 else 80),
        surfaceBright = neutral(if (dark) 24 else 98), surfaceDim = neutral(if (dark) 6 else 87),
        surfaceContainerLowest = neutral(if (dark) 4 else 100), surfaceContainerLow = neutral(if (dark) 10 else 96),
        surfaceContainer = neutral(if (dark) 12 else 94), surfaceContainerHigh = neutral(if (dark) 17 else 92),
        surfaceContainerHighest = neutral(if (dark) 22 else 90),
    )
}
