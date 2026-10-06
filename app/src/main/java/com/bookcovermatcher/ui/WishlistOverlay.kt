package com.bookcovermatcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookcovermatcher.MainViewModel
import com.bookcovermatcher.UiState
import com.bookcovermatcher.WishlistMatchUi
import com.bookcovermatcher.WishlistRecordUi
import com.bookcovermatcher.ui.components.FileImage
import com.bookcovermatcher.ui.components.dashedBorder
import com.bookcovermatcher.ui.theme.Palette

/** Full-screen wishlist (v1's `.overlay`). The system back gesture closes it. */
@Composable
fun WishlistOverlay(ui: UiState, vm: MainViewModel) {
    BackHandler(enabled = ui.wishlistOpen && ui.pendingDeleteId == null && ui.renaming == null) { vm.closeWishlist() }

    AnimatedVisibility(visible = ui.wishlistOpen, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xE0000A14))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .systemBarsPadding()
                .padding(16.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            val shape = RoundedCornerShape(18.dp)
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .clip(shape)
                    .background(Brush.verticalGradient(listOf(Color(0xFF00294B), Color(0xFF001A33))), shape)
                    .border(1.dp, Palette.Line, shape)
                    .padding(14.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Star, contentDescription = null, tint = Palette.Warn, modifier = Modifier.size(20.dp))
                    Text(
                        "WISHLIST",
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                        color = Palette.Accent,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.6.sp,
                    )
                    SquareIconButton(Icons.Filled.Close, "Close wishlist", Palette.Text, round = true, onClick = vm::closeWishlist)
                }
                Spacer(Modifier.height(12.dp))

                if (ui.wishlist.isEmpty()) {
                    Text(
                        "No saved records yet. Scan a cover and add it to your wishlist.",
                        modifier = Modifier
                            .fillMaxWidth()
                            .dashedBorder(Palette.Line, 12.dp)
                            .padding(horizontal = 12.dp, vertical = 26.dp),
                        color = Palette.Muted,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    LazyColumn(
                        Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(ui.wishlist, key = { it.id }) { record -> RecordCard(record, vm) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SquareIconButton(
    icon: ImageVector,
    description: String,
    tint: Color,
    modifier: Modifier = Modifier,
    round: Boolean = false,
    borderColor: Color = Palette.Line,
    iconRotation: Float = 0f,
    onClick: () -> Unit,
) {
    val shape = if (round) CircleShape else RoundedCornerShape(10.dp)
    Box(
        modifier
            .size(if (round) 38.dp else 34.dp)
            .clip(shape)
            .border(1.dp, borderColor, shape)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = iconRotation },
        )
    }
}

@Composable
private fun RecordCard(rec: WishlistRecordUi, vm: MainViewModel) {
    var expanded by rememberSaveable(rec.id) { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    val shape = RoundedCornerShape(14.dp)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.02f))), shape)
            .border(1.dp, Palette.Line, shape)
            .padding(10.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            FileImage(
                rec.scanThumb,
                Modifier.size(56.dp, 76.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, Palette.Line, RoundedCornerShape(8.dp)),
                "Cover",
            )
            Column(Modifier.weight(1f).padding(top = 2.dp)) {
                Text(
                    rec.name,
                    color = Palette.Text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(rec.timeText, modifier = Modifier.padding(top = 3.dp), color = Palette.Muted, fontSize = 11.5.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SquareIconButton(Icons.Filled.Edit, "Rename", Palette.Text) { vm.requestRename(rec.id) }
                SquareIconButton(
                    Icons.Filled.KeyboardArrowDown,
                    if (expanded) "Collapse" else "Expand",
                    if (expanded) Palette.Accent else Palette.Text,
                    iconRotation = rotation,
                ) { expanded = !expanded }
                SquareIconButton(
                    Icons.Filled.Delete,
                    "Delete record",
                    Palette.Danger,
                    borderColor = Palette.Danger.copy(alpha = 0.45f),
                ) { vm.requestDelete(rec.id) }
            }
        }

        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.fillMaxWidth().height(1.dp).dashedBorder(Palette.Line, 0.dp))
                if (rec.matches.isEmpty()) {
                    Text("No matches saved.", color = Palette.Muted, fontSize = 11.sp)
                } else {
                    rec.matches.forEachIndexed { i, m -> MatchLine(i, m) }
                }
            }
        }
    }
}

@Composable
private fun MatchLine(index: Int, m: WishlistMatchUi) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FileImage(
            m.thumb,
            Modifier.size(32.dp, 44.dp).clip(RoundedCornerShape(5.dp)).border(1.dp, Palette.Line, RoundedCornerShape(5.dp)),
            "Match ${index + 1}",
        )
        Text(m.cell, color = Palette.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(m.source, color = Palette.Muted, fontSize = 10.5.sp)
        Spacer(Modifier.weight(1f))
        Text(m.percentText, color = Palette.Muted, fontSize = 12.5.sp)
    }
}
