/**
 * The pieces both result cards are assembled from.
 *
 * Search draws a live catalogue result (`ProductResultCard`) and Wishlist draws a saved bookmark
 * (`SavedItemRow`). They are two sources of the same fields, so the expanded metadata block, the
 * expand/collapse control and the action chip are written once, here. A presentation change is then
 * a change to both cards — the only way the two tabs cannot drift apart again.
 */
package com.bookcovermatcher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookcovermatcher.core.wishlist.BookmarkPresentation
import com.bookcovermatcher.ui.theme.Palette

/** A missing value, spelled once. Same glyph the Wishlist mapping and the Search card print. */
internal const val MISSING_VALUE = BookmarkPresentation.MISSING

/** How many lines of a description an expanded card shows before it ellipsises. */
private const val DESCRIPTION_MAX_LINES = 4

/**
 * The expand/collapse control: a bordered square with a chevron that turns with the card.
 *
 * It is deliberately its own tap target, separate from every action, and it carries its own content
 * description so a screen reader announces `Expand …` / `Collapse …`.
 */
@Composable
internal fun ExpandChevronButton(
    expanded: Boolean,
    expandLabel: String,
    collapseLabel: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(220), label = "chevron")
    Box(
        modifier
            .size(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .border(1.dp, Palette.Line, RoundedCornerShape(9.dp))
            .clickable(role = Role.Button, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = if (expanded) collapseLabel else expandLabel,
            tint = if (expanded) Palette.Accent else Palette.Text,
            modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = rotation },
        )
    }
}


/** `DESCRIPTION` heading plus the provider's own text. A source without one shows `—`. */
@Composable
internal fun DescriptionBlock(description: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            "DESCRIPTION",
            color = Palette.Muted,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.8.sp,
        )
        Text(
            description?.trim()?.takeIf { it.isNotEmpty() } ?: MISSING_VALUE,
            color = Palette.Text,
            fontSize = 11.5.sp,
            maxLines = DESCRIPTION_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The expanded block: description, then every optional field as `Label  value`. */
@Composable
internal fun ResultDetails(
    description: String?,
    price: String?,
    availability: String?,
    identifier: String?,
    source: String?,
    url: String?,
    /** Display-only artist row; null when the record has no artist. */
    artistLine: String? = null,
    /** The shop's circle, always its own row and never part of [artistLine]. */
    circle: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        DescriptionBlock(description)
        DetailLine("Price", price)
        DetailLine("Availability", availability)
        DetailLine("Identifier", identifier)
        // The Wishlist card carries the artist and the circle as two values; the Search card has no
        // parsed artist row, so it omits both. A row is drawn only when the record really has it.
        artistLine?.let { DetailLine("Artist", it) }
        circle?.trim()?.takeIf { it.isNotEmpty() }?.let { DetailLine("Circle", it) }
        DetailLine("Source", source)
        if (!url.isNullOrBlank()) {
            Text(
                url,
                color = Palette.Muted,
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One `Label  value` row. A field the source never sent is drawn as `—`, so the card shows which
 * values exist and which do not instead of silently dropping a row.
 */
@Composable
internal fun DetailLine(label: String, value: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Palette.Muted, fontSize = 11.sp)
        Text(value ?: MISSING_VALUE, color = Palette.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** A rounded action used by the expanded action row of both cards. */
@Composable
internal fun ResultActionChip(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
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
