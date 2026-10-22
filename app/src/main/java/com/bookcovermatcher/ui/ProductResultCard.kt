package com.bookcovermatcher.ui

import androidx.compose.animation.core.Animatable
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
 * One catalogue result.
 *
 * Everything on the card comes from the catalogue's own response. The only action is "Open page",
 * which opens the catalogue's page for this exact item — the app never constructs a search or
 * shopping URL of its own.
 */
@Composable
fun ProductResultCard(item: ProductResultUi, index: Int, onOpen: (String) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(item.id) {
        entrance.snapTo(0f)
        entrance.animateTo(1f, tween(220, delayMillis = index * 45))
    }

    Row(
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
            .padding(10.dp),
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
            if (item.languages.isNotEmpty()) {
                Text(
                    "lang ${item.languages}",
                    modifier = Modifier.padding(top = 2.dp),
                    color = Palette.Muted,
                    fontSize = 10.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                Modifier
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Palette.Accent.copy(alpha = 0.14f))
                    .clickable(role = Role.Button, onClick = { onOpen(item.detailUrl) })
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Open ${item.source.lowercase()} page  ↗",
                    color = Palette.Accent,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

