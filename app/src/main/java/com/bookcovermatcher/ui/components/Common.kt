// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.ui.components

import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.alpha
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookcovermatcher.ui.theme.Palette
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shared Material action. Existing callers supply the palette and minimum size for their workflow. */
@Composable
fun GradientButton(
    onClick: () -> Unit,
    colors: List<Color>,
    contentColor: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minHeight: Dp = 54.dp,
    shape: Shape = ButtonDefaults.shape,
    border: BorderStroke? = null,
    glow: Color? = null,
    contentPadding: Dp = 18.dp,
    content: @Composable RowScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val surface = when (colors) {
        Palette.PrimaryGradient -> scheme.primary
        Palette.AccentGradient -> if (contentColor == Palette.OnAccent2) scheme.secondary else scheme.secondaryContainer
        Palette.NavyGradient -> scheme.surfaceContainerHighest
        Palette.GreenGradient -> Color(0xFF14663E)
        Palette.GreyGradient -> scheme.surfaceContainerHigh
        else -> colors.last()
    }
    val accentFill = colors == Palette.PrimaryGradient || colors == Palette.AccentGradient
    val face = when {
        !accentFill -> Modifier
        colors == Palette.AccentGradient && contentColor != Palette.OnAccent2 ->
            Modifier.background(Brush.linearGradient(listOf(surface, lerp(surface, scheme.secondary, 0.12f))), shape)
        else -> Modifier.background(Brush.linearGradient(listOf(
            lerp(surface, Color.White, 0.09f), surface, lerp(surface, scheme.secondary, 0.45f),
        )), shape)
    }
    val halo = if (enabled && glow != null) {
        Modifier.dropShadow(shape, Shadow(radius = 9.dp, color = glow, spread = 1.dp))
    } else Modifier
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = minHeight.coerceAtLeast(48.dp))
            .alpha(if (enabled) 1f else 0.6f).then(halo).then(if (enabled) face else Modifier),
        enabled = enabled,
        shape = shape,
        border = border,
        elevation = null,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (accentFill) Color.Transparent else surface, contentColor = contentColor,
        ),
        contentPadding = PaddingValues(horizontal = contentPadding, vertical = 8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** Label used inside [GradientButton]. */
@Composable
fun ButtonLabel(text: String, color: Color, fontSize: Float = 16f) {
    Text(text, color = color, style = MaterialTheme.typography.labelLarge, fontSize = fontSize.sp,
        textAlign = TextAlign.Center)
}

/** Heading used at the start of a destination. */
@Composable
fun ScreenHeading(title: String) {
    Text(title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
fun Panel(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        border = BorderStroke(1.dp, lerp(MaterialTheme.colorScheme.outlineVariant,
            MaterialTheme.colorScheme.primary, 0.24f).copy(alpha = 0.68f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(if (title == null) 8.dp else 16.dp)) {
            if (title != null) {
                Text(
                    title, modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(12.dp))
            }
            content()
        }
    }
}

@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.padding(bottom = 6.dp), color = MaterialTheme.colorScheme.secondary,
        style = MaterialTheme.typography.labelLarge)
}

/** Rounded dashed outline (`border: 1px dashed var(--line)`). */
fun Modifier.dashedBorder(color: Color, radius: Dp, width: Dp = 1.dp): Modifier = this.drawBehind {
    val w = width.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(w / 2f, w / 2f),
        size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx()))),
    )
}

/** Decodes a small JPEG held in memory (cover thumbnails). */
@Composable
fun JpegImage(bytes: ByteArray, modifier: Modifier = Modifier, description: String? = null) {
    val bitmap: ImageBitmap? = remember(bytes) {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = description, modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(Color.Black))
    }
}

/**
 * Loads a small image file off the main thread (wishlist thumbnails).
 *
 * [contentScale] defaults to [ContentScale.Crop] because the list rows want a filled tile; the
 * result-profile detail view passes [ContentScale.Fit] so a portrait cover is never cropped.
 */
@Composable
fun FileImage(
    file: File?,
    modifier: Modifier = Modifier,
    description: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, file) {
        value = if (file == null) null else withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(file.path)?.asImageBitmap()
        }
    }
    val loaded = bitmap
    if (loaded != null) {
        Image(bitmap = loaded, contentDescription = description, modifier = modifier, contentScale = contentScale)
    } else {
        Box(modifier.background(Color.Black))
    }
}

/** Keeps the last non-null value so an exit animation can still draw its content after the state became null. */
@Composable
fun <T : Any> rememberLastNonNull(value: T?): T? {
    val holder = remember { arrayOfNulls<Any>(1) }
    if (value != null) holder[0] = value
    @Suppress("UNCHECKED_CAST")
    return (value ?: holder[0]) as T?
}

// ---------------------------------------------------------------------------------------------
// Segmented tabs (Wishlist sections, Search result sources)
// ---------------------------------------------------------------------------------------------

/**
 * The tab track the workflow image draws for a set of related lists: a dark pill with the active
 * tab filled in accent.
 *
 * Shared by the Wishlist sections (Folders / Bookmarks) and the Search result sources
 * (Workbook / Online / Bookmarks) so both groups read the same way, and so neither screen grows its
 * own copy of the styling.
 */
@Composable
fun SegmentedTabRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .selectableGroup()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        content = content,
    )
}

/**
 * One tab of a [SegmentedTabRow]. The label carries its own count (`Workbook (5)`) so the user can
 * see where results are without visiting every tab.
 *
 * [count] puts that number on its **own line** under the title, which is what keeps a directory entry
 * readable when the row is shared between four sections: the title never has to make room for the count,
 * and the count is shown even when it is zero or still unknown (the caller decides what "unknown" says).
 *
 * [maxLines] exists for rows with four short sections on a narrow screen: the label wraps onto a second
 * line at its normal size instead of being cut to `Catalogue (5\u2026)`. Shrinking the font is not an option —
 * the section name is what the user reads.
 */
@Composable
fun SegmentedTab(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    count: String? = null,
    maxLines: Int = 1,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .selectable(selected = active, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label,
                color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else Palette.Muted,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = if (LocalDensity.current.fontScale > 1f) Int.MAX_VALUE else maxLines.coerceAtLeast(2),
                overflow = TextOverflow.Ellipsis,
            )
            if (count != null) {
                Text(
                    count,
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else Palette.Muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Glyphs drawn with Canvas (no icon font / extra dependency)
// ---------------------------------------------------------------------------------------------

@Composable
fun CameraGlyph(color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.06f, h * 0.26f),
            size = Size(w * 0.88f, h * 0.60f),
            cornerRadius = CornerRadius(w * 0.14f),
            style = stroke,
        )
        drawLine(color, Offset(w * 0.33f, h * 0.15f), Offset(w * 0.67f, h * 0.15f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
        drawCircle(color, radius = w * 0.18f, center = Offset(w * 0.5f, h * 0.56f), style = stroke)
    }
}

/** The Auto Scan icon: a target reticle. */
@Composable
fun TargetGlyph(color: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val c = Offset(w / 2f, this.size.height / 2f)
        val stroke = Stroke(width = w * 0.085f, cap = StrokeCap.Round)
        drawCircle(color, radius = w * 0.31f, center = c, style = stroke)
        drawCircle(color, radius = w * 0.08f, center = c)
        val tick = w * 0.14f
        drawLine(color, Offset(c.x, w * 0.04f), Offset(c.x, w * 0.04f + tick), strokeWidth = w * 0.085f, cap = StrokeCap.Round)
        drawLine(color, Offset(c.x, w * 0.96f), Offset(c.x, w * 0.96f - tick), strokeWidth = w * 0.085f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.04f, c.y), Offset(w * 0.04f + tick, c.y), strokeWidth = w * 0.085f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.96f, c.y), Offset(w * 0.96f - tick, c.y), strokeWidth = w * 0.085f, cap = StrokeCap.Round)
    }
}
