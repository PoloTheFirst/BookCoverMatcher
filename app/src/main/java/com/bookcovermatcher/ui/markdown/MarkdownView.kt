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
import com.bookcovermatcher.core.author.AuthorProfile
import com.bookcovermatcher.core.markdown.MdBlock
import com.bookcovermatcher.core.markdown.MdItem
import com.bookcovermatcher.core.markdown.MdRun
import com.bookcovermatcher.ui.theme.Palette

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
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEachIndexed { index, block ->
            Block(block, baseSize, onOpenUrl, onLinkRefused, first = index == 0)
        }
    }
}

@Composable
private fun Block(
    block: MdBlock,
    baseSize: TextUnit,
    onOpenUrl: (String) -> Unit,
    onLinkRefused: (String) -> Unit,
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

        else -> OtherBlock(block, baseSize, onOpenUrl, onLinkRefused)
    }
}

@Composable
private fun OtherBlock(
    block: MdBlock,
    baseSize: TextUnit,
    onOpenUrl: (String) -> Unit,
    onLinkRefused: (String) -> Unit,
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

        is MdBlock.Image -> Row(
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


