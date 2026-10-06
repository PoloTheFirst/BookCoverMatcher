package com.bookcovermatcher.ui.components

import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookcovermatcher.ui.theme.Palette
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Gradient push-button: the `.btn` family of v1's CSS (primary, accent, ghost, yellow, red, grey). */
@Composable
fun GradientButton(
    onClick: () -> Unit,
    colors: List<Color>,
    contentColor: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minHeight: Dp = 54.dp,
    shape: Shape = RoundedCornerShape(14.dp),
    border: BorderStroke? = null,
    glow: Color? = null,
    contentPadding: Dp = 18.dp,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, tween(80), label = "press")
    val base = if (glow != null) {
        modifier.shadow(10.dp, shape, ambientColor = glow, spotColor = glow)
    } else {
        modifier
    }
    Row(
        modifier = base
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.5f
            }
            .clip(shape)
            .background(Brush.verticalGradient(colors), shape)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .heightIn(min = minHeight)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = contentPadding, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Label used inside [GradientButton]. */
@Composable
fun ButtonLabel(text: String, color: Color, fontSize: Float = 16f) {
    Text(text, color = color, fontSize = fontSize.sp, fontWeight = FontWeight.ExtraBold)
}

/** A bordered card with the small cyan upper-case heading (`.panel`). */
@Composable
fun Panel(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.055f), Color.White.copy(alpha = 0.018f))),
                shape,
            )
            .border(1.dp, Palette.Line, shape)
            .padding(14.dp),
    ) {
        Text(
            title.uppercase(),
            color = Palette.Accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.4.sp,
        )
        Spacer(Modifier.height(12.dp))
        content()
    }
}

/** Small upper-case caption above an input. */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(bottom = 6.dp),
        color = Palette.Muted,
        fontSize = 11.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 1.0.sp,
    )
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

/** Loads a small image file off the main thread (wishlist thumbnails). */
@Composable
fun FileImage(file: File?, modifier: Modifier = Modifier, description: String? = null) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, file) {
        value = if (file == null) null else withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(file.path)?.asImageBitmap()
        }
    }
    val loaded = bitmap
    if (loaded != null) {
        Image(bitmap = loaded, contentDescription = description, modifier = modifier, contentScale = ContentScale.Crop)
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
