// SPDX-License-Identifier: GPL-3.0-or-later

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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.bookcovermatcher.UiState
import com.bookcovermatcher.camera.CameraController
import com.bookcovermatcher.core.Config
import com.bookcovermatcher.core.autoscan.ScanPhase
import com.bookcovermatcher.core.autoscan.ScanTick
import com.bookcovermatcher.ui.components.CameraGlyph
import com.bookcovermatcher.ui.theme.Palette
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/** The preview and guide use the same crop proportions as the captured image. */
@Composable
fun CameraCard(
    state: UiState,
    camera: CameraController,
    lifecycleOwner: LifecycleOwner,
    captureEvents: SharedFlow<Unit>,
    onLive: () -> Unit,
    onError: (String) -> Unit,
    cameraReady: Boolean,
    onPreviewStreamChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val previewView = remember(context, state.cameraRestart) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            keepScreenOn = true
        }
    }

    // A successful CameraX bind can precede the first frame, especially on resume.
    val notifyStreaming by rememberUpdatedState(onPreviewStreamChanged)
    DisposableEffect(previewView, lifecycleOwner) {
        val stream = previewView.previewStreamState
        val observer = Observer<PreviewView.StreamState> { notifyStreaming(it == PreviewView.StreamState.STREAMING) }
        stream.observe(lifecycleOwner, observer)
        onDispose { stream.removeObserver(observer) }
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

    val auto = state.autoScan && cameraReady
    val tick = state.scanTick
    val phase = if (auto) tick?.phase else null

    val scheme = MaterialTheme.colorScheme
    val targetColor = when (phase) {
        ScanPhase.HOLD_STILL, ScanPhase.TOO_DARK, ScanPhase.TOO_BRIGHT -> scheme.tertiary
        ScanPhase.STABILIZING -> scheme.secondary
        ScanPhase.SAME_COVER -> scheme.onSurfaceVariant
        ScanPhase.CAPTURED -> Palette.Ok
        null -> scheme.primary
    }
    val guideColor by animateColorAsState(targetColor, label = "guideColor")
    // A contrasting keyline keeps the website color visible on black and white covers.
    val keyline = scheme.surfaceBright
    val cameraShape = MaterialTheme.shapes.extraLarge
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = cameraShape,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cardHeight = (maxWidth * (4f / 3f)).coerceAtMost((screenHeightDp * 0.56f).dp)
            SideEffect { camera.viewAspect = maxWidth.value / cardHeight.value }

            Box(
                Modifier.fillMaxWidth().height(cardHeight).clip(cameraShape)
                    .background(Color.Black),
            ) {
                key(previewView) {
                    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                }

                if (!cameraReady) {
                    Column(
                        Modifier.fillMaxSize().background(scheme.surfaceContainerLow)
                            .padding(horizontal = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Surface(shape = MaterialTheme.shapes.large, color = scheme.primaryContainer) {
                            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                                CameraGlyph(scheme.onPrimaryContainer, size = 28.dp)
                            }
                        }
                        Text(
                            if (state.cameraWanted) "Starting camera…" else "Camera is off",
                            modifier = Modifier.padding(top = 8.dp),
                            color = scheme.onSurface,
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                        if (!state.cameraWanted) {
                            Text(
                                "Tap Scan Book Cover to start",
                                color = scheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                Canvas(Modifier.fillMaxSize()) { drawGuide(guideColor, keyline) }

                if (auto && phase != ScanPhase.CAPTURED) {
                    Box(
                        Modifier.align(Alignment.Center)
                            .fillMaxWidth(Config.GUIDE_W).fillMaxHeight(Config.GUIDE_H)
                            .clip(RoundedCornerShape(16.dp)),
                    ) { ScanLine(guideColor) }
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
                            color = keyline.copy(alpha = focusAlpha.value),
                            radius = 30.dp.toPx(),
                            center = p,
                            style = Stroke(width = 5.dp.toPx()),
                        )
                        drawCircle(
                            color = guideColor.copy(alpha = focusAlpha.value),
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
        if (cameraReady) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(guideColor))
                    Spacer(Modifier.width(8.dp))
                    Text(state.cameraBadge, color = scheme.onSurface, style = MaterialTheme.typography.labelLarge)
                }
                if (auto) ScanFeedback(tick)
            }
        }
    }
}

/** Dims everything outside the guide and draws the dashed outline plus the four corner brackets. */
private fun DrawScope.drawGuide(guideColor: Color, keyline: Color) {
    val gw = size.width * Config.GUIDE_W
    val gh = size.height * Config.GUIDE_H
    val left = (size.width - gw) / 2f
    val top = (size.height - gh) / 2f
    val radius = 16.dp.toPx()

    val outer = Path().apply { addRect(Rect(Offset.Zero, size)) }
    val inner = Path().apply { addRoundRect(RoundRect(left, top, left + gw, top + gh, CornerRadius(radius, radius))) }
    val dim = Path.combine(PathOperation.Difference, outer, inner)
    drawPath(dim, Color.Black.copy(alpha = 0.27f))

    val dash = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx()))
    drawRoundRect(
        color = guideColor.copy(alpha = 0.70f),
        topLeft = Offset(left, top),
        size = Size(gw, gh),
        cornerRadius = CornerRadius(radius, radius),
        style = Stroke(width = 2.dp.toPx(), pathEffect = dash),
    )

    // one bracket (top-left), mirrored around the guide centre for the other corners
    val len = 32.dp.toPx()
    val bracket = Path().apply {
        moveTo(left, top + len)
        lineTo(left, top + radius)
        arcTo(Rect(left, top, left + 2 * radius, top + 2 * radius), 180f, 90f, false)
        lineTo(left + len, top)
    }
    val pivot = Offset(size.width / 2f, size.height / 2f)
    val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
    val backing = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
    val bloom = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
    for ((sx, sy) in listOf(1f to 1f, -1f to 1f, 1f to -1f, -1f to -1f)) {
        scale(sx, sy, pivot) {
            drawPath(bracket, guideColor.copy(alpha = 0.24f), style = bloom)
            drawPath(bracket, keyline, style = backing)
            drawPath(bracket, guideColor, style = stroke)
        }
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

/** Put capture diagnostics outside the crop so large labels never cover the book. */
@Composable
private fun ScanFeedback(tick: ScanTick?) {
    val scheme = MaterialTheme.colorScheme
    val target = tick?.stableTarget ?: 3
    LinearProgressIndicator(
        progress = { (tick?.stableCount ?: 0).toFloat() / target },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Capture stability" },
        color = scheme.primary,
        trackColor = scheme.surfaceContainerHighest,
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        QualityChip("Sharp", tick?.sharp)
        QualityChip("Light", tick?.lit)
        QualityChip("Steady", tick?.steady)
    }
}

@Composable
private fun QualityChip(label: String, ok: Boolean?) {
    val scheme = MaterialTheme.colorScheme
    val container = when (ok) {
        true -> scheme.primaryContainer
        false -> scheme.tertiaryContainer
        null -> scheme.surfaceContainerHighest
    }
    val content = when (ok) {
        true -> scheme.onPrimaryContainer
        false -> scheme.onTertiaryContainer
        null -> scheme.onSurfaceVariant
    }
    val description = when (ok) {
        true -> "Ready"
        false -> "Adjust"
        null -> "Waiting"
    }
    Surface(
        modifier = Modifier.semantics(mergeDescendants = true) { stateDescription = description },
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = content,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (ok != null) {
                Icon(if (ok) Icons.Filled.Check else Icons.Filled.Close,
                    contentDescription = null, modifier = Modifier.size(14.dp))
            }
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
