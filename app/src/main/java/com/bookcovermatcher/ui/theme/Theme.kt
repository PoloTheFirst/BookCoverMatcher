package com.bookcovermatcher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** The navy / cyan palette of v1's `app.css` (`:root` variables). */
object Palette {
    val Navy950 = Color(0xFF000D1A)
    val Navy900 = Color(0xFF001428)
    val Navy800 = Color(0xFF001F3F)
    val Navy700 = Color(0xFF062B52)
    val Navy650 = Color(0xFF083663)
    val Line = Color(0xFF154A7E)
    val Text = Color(0xFFEAF3FB)
    val Muted = Color(0xFF8FB2D4)
    val Accent = Color(0xFF00D4FF)
    val Accent2 = Color(0xFF22E0C8)
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
        0.00f to Color(0xFF07407A),
        0.40f to Color(0xFF00254A),
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
        background = Palette.Navy800,
        onBackground = Palette.Text,
        surface = Palette.Navy700,
        onSurface = Palette.Text,
        surfaceVariant = Palette.Navy650,
        onSurfaceVariant = Palette.Muted,
        outline = Palette.Line,
        error = Palette.Danger,
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
