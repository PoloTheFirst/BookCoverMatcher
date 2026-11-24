// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** BCM's dark surfaces and semantic foregrounds, shared by native and custom-drawn components. */
object Palette {
    val Navy950 = Color(0xFF081220)
    val Navy900 = Color(0xFF0C1B2B)
    val Navy800 = Color(0xFF122336)
    val Navy700 = Color(0xFF1C2E41)
    val Navy650 = Color(0xFF263D52)
    val Line = Color(0xFF496075)
    val Text = Color(0xFFEDF4FA)
    val Muted = Color(0xFFB6C8D8)
    val Accent = Color(0xFF67D8FC)
    val Accent2 = Color(0xFF78DEC9)
    val Danger = Color(0xFFFF6B6B)
    val Ok = Color(0xFF3DDC84)
    val Warn = Color(0xFFFFB020)

    val OnAccent = Color(0xFF00223B)
    val OnAccent2 = Color(0xFF00261F)

    val PrimaryGradient = listOf(Color(0xFF00E0FF), Color(0xFF0090C8))
    val AccentGradient = listOf(Color(0xFF25E6C5), Color(0xFF00A68F))
    val GhostGradient = listOf(Color.Transparent, Color.Transparent)
    val NavyGradient = listOf(Navy650, Navy700)
    val YellowGradient = listOf(Color(0xFFFFE066), Color(0xFFF0B400))
    val RedGradient = listOf(Color(0xFFFF5B5B), Color(0xFFC02B2B))
    val GreyGradient = listOf(Color(0xFF596172), Color(0xFF3C4454))
    val GreenGradient = listOf(Color(0xFF31C973), Color(0xFF1E9C57))

    /** The page background: a soft blue glow fading into near-black. */
    fun pageBackground(widthPx: Float, heightPx: Float): Brush = Brush.radialGradient(
        0.00f to Color(0xFF152B3F),
        0.40f to Color(0xFF102234),
        0.65f to Navy800,
        1.00f to Navy950,
        center = androidx.compose.ui.geometry.Offset(widthPx / 2f, 0f),
        radius = heightPx * 1.1f,
    )
}

@Composable
fun BookCoverTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Palette.Accent,
        onPrimary = Palette.OnAccent,
        secondary = Palette.Accent2,
        onSecondary = Palette.OnAccent2,
        primaryContainer = Color(0xFF124B60),
        onPrimaryContainer = Color(0xFFBCEAFF),
        secondaryContainer = Color(0xFF1B4D48),
        onSecondaryContainer = Color(0xFFAEF5E4),
        tertiary = Color(0xFFC4C6FF),
        onTertiary = Color(0xFF29305C),
        background = Palette.Navy950,
        onBackground = Palette.Text,
        surface = Palette.Navy900,
        surfaceContainerLowest = Palette.Navy950,
        surfaceContainerLow = Color(0xFF102030),
        surfaceContainer = Palette.Navy800,
        surfaceContainerHigh = Palette.Navy700,
        surfaceContainerHighest = Palette.Navy650,
        onSurface = Palette.Text,
        surfaceVariant = Palette.Navy650,
        onSurfaceVariant = Palette.Muted,
        outline = Palette.Muted,
        outlineVariant = Palette.Line,
        error = Palette.Danger,
    )
    MaterialExpressiveTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface, content = content)
    }
}
