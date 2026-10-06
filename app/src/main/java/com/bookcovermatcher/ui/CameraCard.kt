package com.bookcovermatcher.ui

import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import com.bookcovermatcher.UiState
import com.bookcovermatcher.camera.CameraController
import com.bookcovermatcher.core.Config
import com.bookcovermatcher.core.autoscan.ScanPhase
import com.bookcovermatcher.ui.theme.Palette
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * The viewfinder: live preview, the capture guide and the Auto Scan feedback.
 *
 * Layout is v1's: a 3:4 card (at most 56 % of the screen height) with the dashed guide in the middle
 * (70 % x 78 %), the status badge top-left. New in v2: the guide and badge change colour with what Auto Scan
 * sees, a scan line sweeps the guide while it is armed, three pips show the stability streak, three chips show
 * sharp / light / steady, and a flash plus a haptic tick confirm every capture.
 */
@Composable
fun CameraCard(
    state: UiState,
    camera: CameraController,
    lifecycleOwner: LifecycleOwner,
    captureEvents: SharedFlow<Unit>,
    onLive: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            keepScreenOn = true
        }
    }

    LaunchedEffect(state.cameraWanted, state.cameraRestart) {
        if (state.cameraWanted) camera.start(lifecycleOwner, previewView, onLive, onError) else camera.stop()
    }
    DisposableEffect(Unit) { onDispose { camera.stop() } }

    // capture flash + haptic tick
    val flash = remember { Animatable(0f) }
    LaunchedEffect(captureEvents) {
        captureEvents.collect {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            flash.snapTo(0.55f)
            flash.animateTo(0f, tween(320))
        }
    }

    // tap-to-focus ring
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    val focusAlpha = remember { Animatable(0f) }

    val auto = state.autoScan && state.cameraLive
    val tick = state.scanTick
    val phase = if (auto) tick?.phase else null

    val guideTarget = when (phase) {
        null -> Palette.Accent.copy(alpha = 0.40f)
        ScanPhase.HOLD_STILL, ScanPhase.TOO_DARK, ScanPhase.TOO_BRIGHT -> Palette.Warn.copy(alpha = 0.85f)
        ScanPhase.STABILIZING -> Palette.Accent2.copy(alpha = 0.90f)
        ScanPhase.SAME_COVER -> Palette.Muted.copy(alpha = 0.80f)
        ScanPhase.CAPTURED -> Palette.Ok
    }
    val guideColor by animateColorAsState(guideTarget, tween(220), label = "guideColor")
    val bracketTarget = when (phase) {
        null -> Palette.Accent
        ScanPhase.STABILIZING -> Palette.Accent2
        ScanPhase.HOLD_STILL, ScanPhase.TOO_DARK, ScanPhase.TOO_BRIGHT -> Palette.Warn
        ScanPhase.SAME_COVER -> Palette.Muted
        ScanPhase.CAPTURED -> Palette.Ok
    }
    val bracketColor by animateColorAsState(bracketTarget, tween(220), label = "bracketColor")

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val maxHeight: Dp = (screenHeightDp * 0.56f).dp
        val cardHeight: Dp = (maxWidth * (4f / 3f)).coerceAtMost(maxHeight)
        val shape = RoundedCornerShape(18.dp)

        SideEffect { camera.viewAspect = maxWidth.value / cardHeight.value }

        Box(
            Modifier
                .fillMaxWidth()
                .height(cardHeight)
                .shadow(14.dp, shape, clip = false)
                .clip(shape)
                .background(Color.Black)
                .border(1.dp, Palette.Line, shape),
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

            if (!state.cameraLive) {
                Text(
                    "Camera is off\nTap “Scan Book Cover” to start",
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                    color = Palette.Muted.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }

            // dim outside the guide + dashed guide + corner brackets
            Canvas(Modifier.fillMaxSize()) { drawGuide(guideColor, bracketColor) }

            // scan line sweeping the guide while Auto Scan is armed
            if (auto && phase != ScanPhase.CAPTURED) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(Config.GUIDE_W)
                        .fillMaxHeight(Config.GUIDE_H)
                        .clip(RoundedCornerShape(14.dp)),
                ) { ScanLine(bracketColor) }
            }

            // status badge (top-left)
            val badgeColor = when (phase) {
                ScanPhase.HOLD_STILL, ScanPhase.TOO_DARK, ScanPhase.TOO_BRIGHT -> Palette.Warn
                ScanPhase.CAPTURED -> Palette.Ok
                else -> Palette.Accent
            }
            Text(
                state.cameraBadge.uppercase(),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .clip(CircleShape)
                    .background(Color(0xB8001428))
                    .border(1.dp, badgeColor.copy(alpha = 0.35f), CircleShape)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                color = badgeColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.9.sp,
            )

            // stability pips + quality chips (Auto Scan only)
            if (auto) {
                Column(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    val target = tick?.stableTarget ?: 3
                    val filled = tick?.stableCount ?: 0
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(target) { i ->
                            Box(
                                Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(if (i < filled) Palette.Accent2 else Color.White.copy(alpha = 0.25f)),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        QualityChip("Sharp", tick?.sharp)
                        QualityChip("Light", tick?.lit)
                        QualityChip("Steady", tick?.steady)
                    }
                }
            }

            // capture flash
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = flash.value }
                    .background(Color.White),
            )

            // focus ring
            Canvas(Modifier.fillMaxSize()) {
                val p = focusPoint
                if (p != null && focusAlpha.value > 0f) {
                    drawCircle(
                        color = Palette.Accent.copy(alpha = focusAlpha.value),
                        radius = 30.dp.toPx(),
                        center = p,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }

            // topmost layer: taps focus the camera (does not block scrolling the page)
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            camera.focusAt(offset.x, offset.y)
                            focusPoint = offset
                            scope.launch {
                                focusAlpha.snapTo(1f)
                                focusAlpha.animateTo(0f, tween(700))
                            }
                        }
                    },
            )
        }
    }
}

/** Dims everything outside the guide and draws the dashed outline plus the four corner brackets. */
private fun DrawScope.drawGuide(guideColor: Color, bracketColor: Color) {
    val gw = size.width * Config.GUIDE_W
    val gh = size.height * Config.GUIDE_H
    val left = (size.width - gw) / 2f
    val top = (size.height - gh) / 2f
    val radius = 14.dp.toPx()

    val outer = Path().apply { addRect(Rect(Offset.Zero, size)) }
    val inner = Path().apply { addRoundRect(RoundRect(left, top, left + gw, top + gh, CornerRadius(radius, radius))) }
    val dim = Path.combine(PathOperation.Difference, outer, inner)
    drawPath(dim, Color(0x4D001020))

    val dash = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx()))
    drawRoundRect(
        color = guideColor,
        topLeft = Offset(left, top),
        size = Size(gw, gh),
        cornerRadius = CornerRadius(radius, radius),
        style = Stroke(width = 2.dp.toPx(), pathEffect = dash),
    )

    // one bracket (top-left), mirrored around the guide centre for the other corners
    val len = 28.dp.toPx()
    val bracket = Path().apply {
        moveTo(left, top + len)
        lineTo(left, top + radius)
        arcTo(Rect(left, top, left + 2 * radius, top + 2 * radius), 180f, 90f, false)
        lineTo(left + len, top)
    }
    val pivot = Offset(size.width / 2f, size.height / 2f)
    val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
    for ((sx, sy) in listOf(1f to 1f, -1f to 1f, 1f to -1f, -1f to -1f)) {
        scale(sx, sy, pivot) { drawPath(bracket, bracketColor, style = stroke) }
    }
}

@Composable
private fun ScanLine(color: Color) {
    val transition = rememberInfiniteTransition(label = "scanLine")
    val y by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse),
        label = "scanLineY",
    )
    Canvas(Modifier.fillMaxSize()) {
        val py = size.height * y
        val band = 26.dp.toPx()
        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color.Transparent, color.copy(alpha = 0.30f), Color.Transparent),
                startY = py - band,
                endY = py + band,
            ),
            topLeft = Offset(0f, py - band),
            size = Size(size.width, band * 2),
        )
        drawLine(color.copy(alpha = 0.85f), Offset(0f, py), Offset(size.width, py), strokeWidth = 2.dp.toPx())
    }
}

/** "Sharp" / "Light" / "Steady" indicator; [ok] is null before the first analysed frame. */
@Composable
private fun QualityChip(label: String, ok: Boolean?) {
    val color = when (ok) {
        true -> Palette.Ok
        false -> Palette.Warn
        null -> Palette.Muted
    }
    Row(
        Modifier
            .clip(CircleShape)
            .background(Color(0xB8001428))
            .border(1.dp, color.copy(alpha = 0.45f), CircleShape)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Text(label.uppercase(), color = color, fontSize = 9.5.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp)
    }
}
