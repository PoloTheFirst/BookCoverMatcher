// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.bookcovermatcher.core.author.AuthorImageSizing
import com.bookcovermatcher.core.author.AuthorProfile
import com.bookcovermatcher.core.author.SmilAnimation
import com.bookcovermatcher.core.markdown.MdBlock
import com.bookcovermatcher.core.markdown.MdImageGrouping
import com.bookcovermatcher.data.author.PacmanCompositor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import com.bookcovermatcher.core.markdown.MdItem
import com.bookcovermatcher.core.markdown.MdRun
import com.bookcovermatcher.ui.theme.Palette

/**
 * A picture the renderer can draw, with the shape it must reserve so text does not jump while it loads.
 *
 * [animation] is set only for sources that declare animation this app can play; [bitmap] is then the frame at
 * time zero, so the still path stays correct for callers that do not animate.
 */
data class MarkdownImage(
    val bitmap: ImageBitmap,
    val aspectRatio: Float,
    val animation: SmilAnimation? = null,
)

/**
 * Draws parsed Markdown (v2.6.0).
 *
 * One renderer for every document this app shows — the third-party notices, the GPL text and the author's profile
 * README — so legal text, code and links are drawn in exactly one place. It reads only the parsed blocks: nothing
 * here fetches, executes or evaluates anything.
 *
 * Links are the only interactive element, and they pass through [AuthorProfile.linkTarget], so an `http:`,
 * `javascript:` or `intent:` target inside a document cannot leave the app.
 */
@Composable
fun MarkdownDocument(
    blocks: List<MdBlock>,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    baseSize: TextUnit = 11.sp,
    onLinkRefused: (String) -> Unit = {},
    images: Map<String, MarkdownImage> = emptyMap(),
    /** Play an animated source (the contribution graph). False keeps the first frame, for a collapsed panel. */
    animateImages: Boolean = true,
) {
    // Badges are grouped so thirteen of them share a few lines instead of one row each (v2.6.0 crash-fix 1).
    val groups = remember(blocks) { MdImageGrouping.group(blocks) { AuthorImageSizing.isBadge(it) } }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        groups.forEachIndexed { index, group ->
            when {
                group.size > 1 -> BadgeRow(group, baseSize, images)
                else -> Block(
                    block = group.first(),
                    baseSize = baseSize,
                    onOpenUrl = onOpenUrl,
                    onLinkRefused = onLinkRefused,
                    images = images,
                    animateImages = animateImages,
                    first = index == 0,
                )
            }
        }
    }
}

/**
 * One line of Shields.io badges, wrapping when the row is full.
 *
 * Each badge keeps its own fixed height and aspect ratio, so the strip looks like the README's badge row rather
 * than thirteen stretched bands.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BadgeRow(
    group: List<MdBlock>,
    baseSize: TextUnit,
    images: Map<String, MarkdownImage>,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        group.forEach { block ->
            if (block is MdBlock.Image) {
                val picture = images[block.url]
                if (picture != null) {
                    Badge(block, picture)
                } else {
                    // A badge that has not loaded is still a badge: name it in place, never a wide empty box.
                    Text(
                        text = block.alt.ifBlank { "badge" },
                        color = Palette.Muted,
                        fontSize = baseSize * 0.85f,
                    )
                }
            }
        }
    }
}

/** One badge at its fixed display height, with the width its own aspect ratio asks for. */
@Composable
private fun Badge(block: MdBlock.Image, picture: MarkdownImage) {
    val box = AuthorImageSizing.box(block.url, picture.aspectRatio, BADGE_REFERENCE_WIDTH_DP)
    Image(
        bitmap = picture.bitmap,
        contentDescription = block.alt,
        // Fit, not FillWidth: the reserved box already has the badge's own shape, and Fit can never distort it.
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .width(box.widthDp.dp)
            .height(box.heightDp.dp),
    )
}

@Composable
private fun Block(
    block: MdBlock,
    baseSize: TextUnit,
    onOpenUrl: (String) -> Unit,
    onLinkRefused: (String) -> Unit,
    images: Map<String, MarkdownImage>,
    animateImages: Boolean,
    first: Boolean,
) {
    when (block) {
        is MdBlock.Heading -> {
            val size = when (block.level) {
                1 -> baseSize * 1.7f
                2 -> baseSize * 1.45f
                3 -> baseSize * 1.25f
                4 -> baseSize * 1.12f
                else -> baseSize
            }
            Text(
                text = annotated(block.runs, size, onOpenUrl, onLinkRefused),
                color = Palette.Text,
                fontSize = size,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = size * 1.3f,
                modifier = Modifier.fillMaxWidth().padding(top = if (first) 0.dp else 8.dp),
            )
        }

        is MdBlock.Paragraph -> Text(
            text = annotated(block.runs, baseSize, onOpenUrl, onLinkRefused),
            color = Palette.Text,
            fontSize = baseSize,
            lineHeight = baseSize * 1.5f,
        )

        is MdBlock.Listing -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            block.items.forEach { item -> Item(item, baseSize, onOpenUrl, onLinkRefused) }
        }

        else -> OtherBlock(block, baseSize, onOpenUrl, onLinkRefused, images, animateImages)
    }
}

@Composable
private fun OtherBlock(
    block: MdBlock,
    baseSize: TextUnit,
    onOpenUrl: (String) -> Unit,
    onLinkRefused: (String) -> Unit,
    images: Map<String, MarkdownImage>,
    animateImages: Boolean,
) {
    when (block) {
        is MdBlock.Code -> Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Palette.Navy950)
                .border(1.dp, Palette.Line, RoundedCornerShape(6.dp))
                .padding(8.dp),
        ) {
            Text(
                text = block.text,
                color = Palette.Accent2,
                fontSize = baseSize * 0.92f,
                fontFamily = FontFamily.Monospace,
                lineHeight = baseSize * 1.4f,
            )
        }

        is MdBlock.Quote -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(Palette.Accent2))
            Spacer(Modifier.width(8.dp))
            Text(
                text = annotated(block.runs, baseSize, onOpenUrl, onLinkRefused),
                color = Palette.Muted,
                fontSize = baseSize,
                lineHeight = baseSize * 1.5f,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.weight(1f),
            )
        }

        is MdBlock.Table -> Table(block, baseSize, onOpenUrl, onLinkRefused)

        MdBlock.Rule -> Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .height(1.dp)
                .background(Palette.Line),
        )

        is MdBlock.Image -> {
            val picture = images[block.url]
            if (picture != null) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    // One rule decides every picture's box (v2.6.0 crash-fix 1): a Shields.io badge is drawn at a
                    // fixed height and a bounded width, everything else keeps the full width and its own aspect
                    // ratio. Reserving the box up front is also what stops the README jumping while a picture loads.
                    val box = AuthorImageSizing.box(block.url, picture.aspectRatio, maxWidth.value)
                    val frame = Modifier
                        .width(box.widthDp.dp)
                        .height(box.heightDp.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, Palette.Line, RoundedCornerShape(6.dp))
                    val animation = picture.animation
                    if (animation != null) {
                        AnimatedSvgPicture(
                            animation = animation,
                            picture = picture,
                            contentDescription = block.alt,
                            modifier = frame,
                            playing = animateImages,
                            // Render frames at the size the picture is actually drawn at: no wasted pixels, no
                            // upscaling, and the loop costs less than rendering at a fixed 1000 px.
                            frameWidthPx = constraints.maxWidth,
                        )
                    } else {
                        Image(
                            bitmap = picture.bitmap,
                            contentDescription = block.alt,
                            contentScale = ContentScale.FillWidth,
                            modifier = frame,
                        )
                    }
                }
            } else {
                // No picture yet (never fetched, offline with no cache, or refused): keep the reference visible
                // and tappable rather than showing a broken-image icon or silently dropping the image.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, Palette.Line, RoundedCornerShape(6.dp))
                        .clickable {
                            val target = AuthorProfile.linkTarget(block.url)
                            if (target == null) onLinkRefused(block.url) else onOpenUrl(target)
                        }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Image", color = Palette.Accent2, fontSize = baseSize * 0.9f, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = block.alt,
                        color = Palette.Text,
                        fontSize = baseSize,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        is MdBlock.Omitted -> Text(
            text = buildString {
                append("Embedded HTML is not displayed: ")
                append(
                    if (block.alts.isEmpty()) {
                        "this part of the original document uses HTML this app does not render."
                    } else {
                        block.alts.joinToString(", ")
                    },
                )
            },
            color = Palette.Muted,
            fontSize = baseSize * 0.9f,
            lineHeight = baseSize * 1.35f,
        )

        else -> Unit
    }
}

/**
 * The contribution graph, played.
 *
 * The source is an SMIL-animated SVG, and `AndroidSVG` renders only static geometry. Rather than show a frozen
 * first frame, [PacmanCompositor] samples the document's *own* timeline and draws each frame from a pre-rendered
 * scenery plus the five moving sprites, so the graph moves exactly as its keyTimes say, with no WebView, no script
 * and no invented animation.
 *
 * Playback is bounded on every axis:
 *
 *  * one loop per image, owned by this `LaunchedEffect`, so a recomposition cannot start a second one and leaving
 *    composition cancels it;
 *  * it runs only while the caller says the picture is being shown ([playing]) and only while the app is RESUMED,
 *    so a backgrounded app does no rendering work;
 *  * the scene is built once, off the main thread, and released when the picture leaves the screen;
 *  * frames are paced by `withFrameNanos` — the display's own vsync — rather than by a fixed delay, and a tick that
 *    would look identical draws nothing.
 */
@Composable
private fun AnimatedSvgPicture(
    animation: SmilAnimation,
    picture: MarkdownImage,
    contentDescription: String,
    modifier: Modifier,
    playing: Boolean,
    frameWidthPx: Int,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var frame by remember(animation) { mutableStateOf(picture.bitmap) }
    val width = frameWidthPx.coerceIn(64, 1_000)
    // The scene graph and its bitmaps cost one render (~70 ms), so they are built once, off the main thread, and
    // released when the picture leaves the screen. Until then the still picture is shown.
    val compositor = remember(animation, width) { mutableStateOf<PacmanCompositor?>(null) }
    LaunchedEffect(animation, width) {
        compositor.value = withContext(Dispatchers.Default) {
            PacmanCompositor.build(animation.sourceDocument, width)
        }
    }
    val live = compositor.value
    DisposableEffect(live) { onDispose { live?.release() } }
    LaunchedEffect(animation, playing, lifecycle, live) {
        if (!playing || live == null) {
            frame = picture.bitmap
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // A display-synchronised clock: `withFrameNanos` wakes on the compositor's own vsync (Choreographer), so
            // each pass is exactly one presented frame — 16.7 ms at 60 Hz — instead of a fixed delay that drifts and
            // misses vsyncs. A frame that would look identical is skipped, so a still stretch costs no redraw.
            val startNanos = System.nanoTime()
            var lastKey = ""
            while (isActive) {
                withFrameNanos { now ->
                    val elapsedMs = ((now - startNanos) / 1_000_000L) % animation.durationMs
                    val key = live.frameKey(elapsedMs)
                    if (key != lastKey) {
                        lastKey = key
                        frame = live.nextFrame(elapsedMs).asImageBitmap()
                    }
                }
            }
        }
    }
    Image(
        bitmap = frame,
        contentDescription = contentDescription,
        contentScale = ContentScale.FillWidth,
        modifier = modifier,
    )
}

/**
 * The row width a badge is sized against.
 *
 * Badges are a fixed, badge-shaped size everywhere — 20 dp high and as wide as their own aspect ratio needs — so
 * this is only the reference for the defensive width cap, and choosing a phone-width value keeps the strip stable
 * across screens and orientations instead of growing with the display.
 */
private const val BADGE_REFERENCE_WIDTH_DP = 360f

@Composable
private fun Item(
    item: MdItem,
    baseSize: TextUnit,
    onOpenUrl: (String) -> Unit,
    onLinkRefused: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = (item.level * 14).dp)) {
        Text(
            text = if (item.ordered) "${item.number}." else "\u2022",
            color = Palette.Accent2,
            fontSize = baseSize,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(20.dp),
        )
        Text(
            text = annotated(item.runs, baseSize, onOpenUrl, onLinkRefused),
            color = Palette.Text,
            fontSize = baseSize,
            lineHeight = baseSize * 1.45f,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Table(
    block: MdBlock.Table,
    baseSize: TextUnit,
    onOpenUrl: (String) -> Unit,
    onLinkRefused: (String) -> Unit,
) {
    val size = baseSize * 0.92f
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, Palette.Line, RoundedCornerShape(6.dp)),
    ) {
        Row(Modifier.fillMaxWidth().background(Palette.Navy800).padding(6.dp)) {
            block.header.forEach { cell ->
                Text(
                    text = annotated(cell, size, onOpenUrl, onLinkRefused),
                    color = Palette.Text,
                    fontSize = size,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(end = 6.dp),
                )
            }
        }
        block.rows.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(6.dp)) {
                row.forEach { cell ->
                    Text(
                        text = annotated(cell, size, onOpenUrl, onLinkRefused),
                        color = Palette.Text,
                        fontSize = size,
                        lineHeight = size * 1.4f,
                        modifier = Modifier.weight(1f).padding(end = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * Builds the styled span list for one block.
 *
 * A run with a URL becomes a real link only when it survives [AuthorProfile.linkTarget]; otherwise it keeps its
 * own listener and stays visible text, so nothing is hidden merely because a link looked suspicious.
 */
@Composable
private fun annotated(
    runs: List<MdRun>,
    size: TextUnit,
    onOpenUrl: (String) -> Unit,
    onLinkRefused: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    runs.forEach { run ->
        val target = AuthorProfile.linkTarget(run.url)
        val style = SpanStyle(
            color = if (run.url == null) Palette.Text else Palette.Accent,
            fontWeight = if (run.bold) FontWeight.Bold else null,
            fontStyle = if (run.italic) FontStyle.Italic else null,
            fontFamily = if (run.code) FontFamily.Monospace else null,
            background = if (run.code) Palette.Navy950 else Palette.Navy950.copy(alpha = 0f),
            textDecoration = when {
                run.strike -> TextDecoration.LineThrough
                run.url != null -> TextDecoration.Underline
                else -> null
            },
            fontSize = if (run.code) size * 0.94f else TextUnit.Unspecified,
        )
        val start = length
        withStyle(style) { append(run.text) }
        val end = length
        val url = run.url
        if (url != null) {
            val label = if (target != null) "link-$start" else "refused-$start"
            addLink(
                LinkAnnotation.Clickable(tag = label) {
                    if (target != null) onOpenUrl(target) else onLinkRefused(url)
                },
                start = start,
                end = end,
            )
        }
    }
}


