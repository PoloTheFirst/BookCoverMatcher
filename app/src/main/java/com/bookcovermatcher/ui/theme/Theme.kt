// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.bookcovermatcher.data.PalettePreferences

/** Maps existing BCM component colors onto the Material roles used by the website. */
@Immutable
internal class BcmPalette(val scheme: ColorScheme, val Ok: Color, val Warn: Color) {
    val Navy950 = scheme.surfaceContainerLowest
    val Navy900 = scheme.surface
    val Navy800 = scheme.surfaceContainer
    val Navy700 = scheme.surfaceContainerHigh
    val Navy650 = scheme.surfaceContainerHighest
    val Line = scheme.outlineVariant
    val Text = scheme.onSurface
    val Muted = scheme.onSurfaceVariant
    val Accent = scheme.primary
    val Accent2 = scheme.secondary
    val Danger = scheme.error
    val OnAccent = scheme.onPrimary
    val OnAccent2 = scheme.onSecondary

    val PrimaryGradient = listOf(Accent, scheme.primaryContainer)
    val AccentGradient = listOf(Accent2, scheme.secondaryContainer)
    val GhostGradient = listOf(Color.Transparent, Color.Transparent)
    val NavyGradient = listOf(Navy650, Navy700)
    val YellowGradient = listOf(Color(0xFFFFE066), Color(0xFFF0B400))
    val RedGradient = listOf(Color(0xFFBD3B45), Color(0xFFA12B36))
    val GreyGradient = listOf(Navy700, Navy650)
    val GreenGradient = listOf(Color(0xFF19744C), Color(0xFF14663E))

    fun pageBackground(widthPx: Float, heightPx: Float): Brush = Brush.radialGradient(
        0.0f to lerp(scheme.surface, scheme.primaryContainer, 0.60f),
        0.54f to scheme.surface,
        1.0f to scheme.surfaceContainerLowest,
        center = Offset(widthPx * 0.22f, 0f),
        radius = heightPx.coerceAtLeast(1f),
    )
}

// The Atelier site's Sophie semantic roles.
private val LightPalette = BcmPalette(
    scheme = lightColorScheme(
        primary = Color(0xFF80435C),
        onPrimary = Color(0xFFFFFAF7),
        primaryContainer = Color(0xFFFEDBE2),
        onPrimaryContainer = Color(0xFF563545),
        secondary = Color(0xFF795244),
        onSecondary = Color(0xFFFFFAF7),
        secondaryContainer = Color(0xFFFFDEC6),
        onSecondaryContainer = Color(0xFF563545),
        tertiary = Color(0xFF826331),
        onTertiary = Color(0xFFFFFAF7),
        tertiaryContainer = Color(0xFFFFDFAC),
        onTertiaryContainer = Color(0xFF513C21),
        surface = Color(0xFFFAF5EF),
        surfaceDim = Color(0xFFEADED8),
        surfaceBright = Color(0xFFFFFAF7),
        surfaceContainerLowest = Color(0xFFFFFDF9),
        surfaceContainerLow = Color(0xFFFFFAF5),
        surfaceContainer = Color(0xFFF7EDE6),
        surfaceContainerHigh = Color(0xFFF1E5DF),
        surfaceContainerHighest = Color(0xFFEBDCD5),
        onSurface = Color(0xFF3E3035),
        onSurfaceVariant = Color(0xFF6A535D),
        outline = Color(0xFF987F86),
        outlineVariant = Color(0xFFD9C4BD),
        background = Color(0xFFFAF5EF),
        onBackground = Color(0xFF3E3035),
        surfaceVariant = Color(0xFFEBDCD5),
    ),
    Ok = Color(0xFF14683D), Warn = Color(0xFF775014),
)

private val DarkPalette = BcmPalette(
    scheme = darkColorScheme(
        primary = Color(0xFFFDB1CB),
        onPrimary = Color(0xFF482335),
        primaryContainer = Color(0xFF61374B),
        onPrimaryContainer = Color(0xFFFEDBE2),
        secondary = Color(0xFFFFCAAB),
        onSecondary = Color(0xFF462D23),
        secondaryContainer = Color(0xFF5D4237),
        onSecondaryContainer = Color(0xFFFFDEC6),
        tertiary = Color(0xFFE8C78B),
        onTertiary = Color(0xFF3F301A),
        tertiaryContainer = Color(0xFF58462D),
        onTertiaryContainer = Color(0xFFFFDFAC),
        surface = Color(0xFF211B21),
        surfaceDim = Color(0xFF19151B),
        surfaceBright = Color(0xFF3E333C),
        surfaceContainerLowest = Color(0xFF19151B),
        surfaceContainerLow = Color(0xFF2A222A),
        surfaceContainer = Color(0xFF30262F),
        surfaceContainerHigh = Color(0xFF3A2E38),
        surfaceContainerHighest = Color(0xFF443742),
        onSurface = Color(0xFFF7E8EB),
        onSurfaceVariant = Color(0xFFD4BAC4),
        outline = Color(0xFFA88B98),
        outlineVariant = Color(0xFF634B58),
        background = Color(0xFF211B21),
        onBackground = Color(0xFFF7E8EB),
        surfaceVariant = Color(0xFF443742),
    ),
    Ok = Color(0xFF77DBA1), Warn = Color(0xFFFFCB81),
)

private val LocalPalette = staticCompositionLocalOf { LightPalette }
internal val Palette: BcmPalette
    @Composable @ReadOnlyComposable get() = LocalPalette.current

@Composable
internal fun BookCoverTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    colors: PalettePreferences = PalettePreferences(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val palette = remember(context, LocalConfiguration.current, colors, darkTheme) {
        val base = if (darkTheme) DarkPalette else LightPalette
        val scheme = when {
            colors.phone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            colors.custom -> customColorScheme(base.scheme, colors.hue, darkTheme)
            else -> return@remember base
        }
        BcmPalette(scheme, base.Ok, base.Warn)
    }
    MaterialExpressiveTheme(colorScheme = palette.scheme) {
        CompositionLocalProvider(LocalPalette provides palette, LocalContentColor provides palette.Text, content = content)
    }
}
