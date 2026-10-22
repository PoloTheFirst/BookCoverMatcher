package com.bookcovermatcher.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookcovermatcher.ProductResultUi
import com.bookcovermatcher.ui.components.JpegImage
import com.bookcovermatcher.ui.theme.Palette

/**
 * One catalogue result: a collapsed summary plus an expanded panel with the three actions.
 *
 * Every value comes from the provider's own response. `price` and `availability` are deliberately
 * part of the layout even though Open Library returns neither — they render as `—`, never as an
 * invented number. `matchType` says `Text match`, because the OCR text was searched; nothing here
 * claims a visual match.
 *
 * Expansion lives on a dedicated chevron on the header row, so tapping an action can never toggle
 * the card (the same rule the wishlist record card follows).
 */
@Composable
fun ProductResultCard(
    item: ProductResultUi,
    index: Int,
    onOpen: (String) -> Unit,
    onToggleBookmark: (ProductResultUi) -> Unit,
    onAddToWishlist: (ProductResultUi) -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(item.key) {
        entrance.snapTo(0f)
        entrance.animateTo(1f, tween(220, delayMillis = index * 45))
    }

    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = entrance.value
                translationY = (1f - entrance.value) * 6.dp.toPx()
            }
            .clip(shape)
            .background(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.02f))),
                shape,
            )
            .border(1.dp, Palette.Line, shape)
            .padding(10.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { expanded = !expanded },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier
                    .size(52.dp, 74.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Palette.Line, RoundedCornerShape(8.dp)),
            ) {
                val cover = item.coverJpeg
                if (cover != null) {
                    JpegImage(cover, Modifier.fillMaxHeight().fillMaxWidth(), "Cover of ${item.title}")
                } else {
                    Box(
                        Modifier.fillMaxWidth().fillMaxHeight().background(Palette.Navy650),
                        contentAlignment = Alignment.Center,
                    ) { Text("?", color = Palette.Muted, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                }
            }

            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    color = Palette.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    item.authors,
                    modifier = Modifier.padding(top = 2.dp),
                    color = Palette.Accent,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    item.meta,
                    modifier = Modifier.padding(top = 2.dp),
                    color = Palette.Muted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.padding(top = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    item.sourceDomain?.let { Chip(it, Palette.Muted) }
                    Chip(item.matchType, Palette.Accent2)
                }
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (item.bookmarked) Text("★ Bookmarked", color = Palette.Ok, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                    if (item.wishlisted) Text("✓ In wishlist", color = Palette.Accent, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                }
            }

            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .border(1.dp, Palette.Line, RoundedCornerShape(9.dp))
                    .clickable(role = Role.Button) { expanded = !expanded },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse result" else "Expand result",
                    tint = if (expanded) Palette.Accent else Palette.Text,
                    modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = rotation },
                )
            }
        }

        if (expanded) {
            ExpandedResult(item)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ActionChip("Open website ↗", Palette.Accent, Modifier.weight(1f)) { onOpen(item.detailUrl) }
                ActionChip(
                    if (item.bookmarked) "★ Bookmarked" else "★ Bookmark",
                    if (item.bookmarked) Palette.Ok else Palette.Text,
                    Modifier.weight(1f),
                ) { onToggleBookmark(item) }
                ActionChip("+ Wishlist", Palette.Accent2, Modifier.weight(1f)) { onAddToWishlist(item) }
            }
            Text(
                "Add to wishlist opens a picker and saves this one result only.",
                color = Palette.Muted,
                fontSize = 9.5.sp,
            )
        }
    }
}

/** All the metadata a provider may or may not have sent. Missing values are drawn as `—`. */
@Composable
private fun ExpandedResult(item: ProductResultUi) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            "DESCRIPTION",
            color = Palette.Muted,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.8.sp,
        )
        Text(item.description ?: "—", color = Palette.Text, fontSize = 11.5.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
        DetailLine("Price", item.price)
        DetailLine("Availability", item.availability)
        DetailLine("Identifier", item.identifier)
        DetailLine("Source", listOfNotNull(item.provider, item.sourceDomain).joinToString(" · "))
        Text(
            item.detailUrl,
            color = Palette.Muted,
            fontSize = 10.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DetailLine(label: String, value: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Palette.Muted, fontSize = 11.sp)
        Text(value ?: "—", color = Palette.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** A small rounded label, used for the source domain, the match type and the state badges. */
@Composable
fun Chip(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(color.copy(alpha = 0.13f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(7.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(text, color = color, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun ActionChip(label: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(9.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

