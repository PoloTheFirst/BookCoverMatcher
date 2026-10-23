package com.bookcovermatcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookcovermatcher.ProviderState
import com.bookcovermatcher.ProviderStatusUi
import com.bookcovermatcher.core.online.SearchLimits
import com.bookcovermatcher.ui.theme.Palette

/**
 * The platform-selection section of the online panel.
 *
 * Each card has **two independent click targets**:
 *  * the card body toggles selection ([onToggle]) and never opens a browser;
 *  * the `Open ↗` control opens that platform's search page ([onOpenLink]) and never toggles
 *    selection — it is a sibling of the toggle row, not nested inside it, so no event can bubble
 *    between the two.
 *
 * Selected cards use the app's blue accent with a soft glow, a thicker accent border and a check
 * mark; unselected cards keep a subdued surface and muted text but stay fully usable. Nothing is
 * disabled by being unselected: its link still opens, and tapping it selects it.
 */
@Composable
fun PlatformPicker(
    providers: List<ProviderStatusUi>,
    onToggle: (String) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    if (providers.isEmpty()) return
    val selectedCount = providers.count { it.selected }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "PLATFORMS",
                color = Palette.Muted,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.8.sp,
            )
            Text(
                "$selectedCount of ${providers.size} selected",
                color = Palette.Muted,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.size(6.dp))
        providers.forEach { provider ->
            PlatformCard(provider, onToggle, onOpenLink)
            Spacer(Modifier.size(8.dp))
        }
        Text(
            "Selected platforms are included in the next search. Browser-only ones open their own " +
                "page instead \u2014 selection cannot make them searchable in the app.",
            color = Palette.Muted,
            fontSize = 9.5.sp,
        )
    }
}

@Composable
private fun PlatformCard(
    provider: ProviderStatusUi,
    onToggle: (String) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val selected = provider.selected
    // A browser-only card is never selectable, so it is drawn as a distinct, dimmed shortcut card.
    val shortcut = provider.manual

    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (selected) {
                    // Restrained glow: the accent colour, low elevation, no neon.
                    Modifier.shadow(
                        elevation = 10.dp,
                        shape = shape,
                        clip = false,
                        ambientColor = Palette.Accent,
                        spotColor = Palette.Accent,
                    )
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .background(
                when {
                    selected -> Palette.Accent.copy(alpha = 0.10f)
                    shortcut -> Palette.Warn.copy(alpha = 0.05f)
                    else -> Color.White.copy(alpha = 0.02f)
                },
                shape,
            )
            .border(
                width = if (selected) 1.6.dp else 1.dp,
                color = when {
                    selected -> Palette.Accent.copy(alpha = 0.85f)
                    shortcut -> Palette.Warn.copy(alpha = 0.30f)
                    else -> Palette.Line
                },
                shape = shape,
            )
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---- toggle target: the card body, minus the link button (browser-only cards have none) ----
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .then(
                    if (shortcut) {
                        // Not clickable at all: a browser-only card can never enter the selection.
                        Modifier.semantics {
                            contentDescription = "${provider.name}: browser only, cannot be selected"
                            stateDescription = "Browser only"
                        }
                    } else {
                        Modifier
                            .clickable(
                                role = Role.Checkbox,
                                onClickLabel = if (selected) "Deselect ${provider.name}" else "Select ${provider.name}",
                            ) { onToggle(provider.id) }
                            .semantics {
                                stateDescription = if (selected) "Selected" else "Not selected"
                                contentDescription = if (selected) {
                                    "Deselect ${provider.name}"
                                } else {
                                    "Select ${provider.name}"
                                }
                            }
                    },
                )
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            SelectionMark(selected, shortcut)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        provider.name,
                        color = if (selected) Palette.Text else Palette.Muted.copy(alpha = if (shortcut) 0.75f else 1f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    StatusChip(provider)
                }
                Text(
                    provider.host,
                    color = Palette.Muted,
                    fontSize = 9.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                provider.detail?.let {
                    Text(
                        it,
                        color = Palette.Muted.copy(alpha = if (selected) 1f else 0.8f),
                        fontSize = 9.5.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
        }

        // ---- independent link target: never toggles selection ----
        provider.searchUrl?.let { url -> OpenLinkButton(provider, url, onOpenLink) }
            ?: NoLinkLabel()
    }
}

/** Check mark in a circle: shape, not only colour, tells the two selection states apart. */
@Composable
private fun SelectionMark(selected: Boolean, shortcut: Boolean = false) {
    val tint = if (shortcut) Palette.Warn else Palette.Accent
    Box(
        Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(if (selected) tint.copy(alpha = 0.20f) else Color.Transparent)
            .border(
                width = if (selected) 1.6.dp else 1.dp,
                color = if (selected) tint else Palette.Line,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            selected -> Text("\u2713", color = tint, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
            // Browser-only: an arrow, never an empty checkbox that looks selectable.
            shortcut -> Text("\u2197", color = Palette.Warn.copy(alpha = 0.75f), fontSize = 11.sp)
        }
    }
}

/** A short factual label for what this platform is doing right now. */
@Composable
private fun StatusChip(provider: ProviderStatusUi) {
    val (label, color) = when (provider.state) {
        ProviderState.RESULTS -> "Results" to Palette.Ok
        ProviderState.EMPTY -> "No match" to Palette.Muted
        ProviderState.FAILED -> "Failed" to Palette.Danger
        ProviderState.BLOCKED -> "Blocked" to Palette.Warn
        ProviderState.SEARCHING -> "Searching" to Palette.Accent
        ProviderState.MANUAL -> "Browser only" to Palette.Warn
        ProviderState.SKIPPED -> "Not selected" to Palette.Muted
        ProviderState.READY -> (if (provider.selected) "Selected" else "Not selected") to Palette.Accent2
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(color.copy(alpha = 0.13f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(7.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(label, color = color, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/**
 * Opens the platform's own search page.
 *
 * A separate clickable node, deliberately a **sibling** of the selection row rather than a child:
 * tapping it cannot reach the toggle callback, and it works whether the platform is selected or not.
 */
@Composable
private fun OpenLinkButton(provider: ProviderStatusUi, url: String, onOpenLink: (String) -> Unit) {
    val description = "Open ${provider.name} search in browser"
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(Palette.Accent.copy(alpha = 0.10f))
            .border(1.dp, Palette.Accent.copy(alpha = 0.45f), RoundedCornerShape(9.dp))
            .clickable(role = Role.Button, onClickLabel = description) { onOpenLink(url) }
            .semantics { contentDescription = description }
            .padding(horizontal = 9.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Open \u2197",
            color = Palette.Accent,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
        )
    }
}

/** A provider with no verified public search page: said plainly, not drawn as a dead button. */
@Composable
private fun NoLinkLabel() {
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .border(1.dp, Palette.Line, RoundedCornerShape(9.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp)
            .semantics { contentDescription = "No verified search page for this platform" },
        contentAlignment = Alignment.Center,
    ) {
        Text("No link", color = Palette.Muted, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/**
 * The online result-count control.
 *
 * It sets a **maximum**, persisted by the controller through `OnlineSearchPrefs`; nothing here
 * promises that many cards. The offline workbook comparison keeps its own fixed five results and is
 * not affected by this control.
 */
@Composable
fun ResultLimitPicker(limit: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "ONLINE RESULTS",
                color = Palette.Muted,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.8.sp,
            )
            Text("max $limit", color = Palette.Accent, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
        }
        Row(
            Modifier.padding(top = 6.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SearchLimits.OPTIONS.forEach { option ->
                LimitChip(option, option == limit) { onSelect(option) }
            }
        }
        Text(
            "A maximum, not a promise: each platform caps it (DLsite and Melonbooks list one page, " +
                "Google Books stops at 40). The offline top-5 is unaffected.",
            color = Palette.Muted,
            fontSize = 9.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun LimitChip(value: Int, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (active) Palette.Accent.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.20f))
            .border(
                width = if (active) 1.4.dp else 1.dp,
                color = if (active) Palette.Accent else Palette.Line,
                shape = RoundedCornerShape(9.dp),
            )
            .selectable(selected = active, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            value.toString(),
            color = if (active) Palette.Accent else Palette.Muted,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}
