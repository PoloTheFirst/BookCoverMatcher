package com.bookcovermatcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookcovermatcher.BatchAction
import com.bookcovermatcher.BatchDeleteUi
import com.bookcovermatcher.BatchKind
import com.bookcovermatcher.BatchTargetUi
import com.bookcovermatcher.SelectionLogic
import com.bookcovermatcher.SelectionUi
import com.bookcovermatcher.ui.components.ButtonLabel
import com.bookcovermatcher.ui.components.GradientButton
import com.bookcovermatcher.ui.theme.Palette

/**
 * The visible way into multi-selection: a labelled, outlined button with a checkbox glyph.
 *
 * It is drawn in the header of every list that can be selected, so the feature is discoverable
 * without a hidden gesture. A long-press on a row is an additional shortcut, not the only way in.
 */
@Composable
fun SelectEntry(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.Accent.copy(alpha = 0.14f))
            .border(1.dp, Palette.Accent.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClickLabel = label) { onClick() }
            .semantics { contentDescription = label }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(13.dp)
                .clip(RoundedCornerShape(3.dp))
                .border(1.5.dp, Palette.Accent, RoundedCornerShape(3.dp)),
        )
        Text(label, color = Palette.Accent, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/**
 * The selection panel: the count, what can be tapped, and Select all / Clear / Done.
 *
 * It is pinned above the list and never scrolls away, and it states the actions in words so the
 * workflow does not depend on guessing what a check mark does.
 */
@Composable
fun SelectionToolbar(
    selection: SelectionUi,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Accent.copy(alpha = 0.10f))
            .border(1.dp, Palette.Accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${selection.count} selected",
                color = Palette.Text,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Spacer(Modifier.weight(1f))
            TextAction("Select all", Palette.Accent2, onSelectAll)
            TextAction("Clear", Palette.Muted, onClear)
            TextAction("Done", Palette.Accent, onDone)
        }
        Text(
            SelectionLogic.hint(selection.kind),
            color = Palette.Muted,
            fontSize = 9.5.sp,
            lineHeight = 13.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/** A small text button used by the selection panel. */
@Composable
private fun TextAction(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 5.dp),
    ) {
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/**
 * The action panel, pinned under the list: the count, Done, then Copy / Move / Delete.
 *
 * Move is only drawn where the model has a real destination (`SelectionLogic.canMove`): a folder has
 * no parent here, and moving a bookmark would un-bookmark it. Unsupported actions are absent rather
 * than present but broken.
 */
@Composable
fun BatchActionBar(
    kind: BatchKind,
    count: Int,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$count of ${SelectionLogic.noun(kind)} selected",
                color = Palette.Muted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            TextAction("Done", Palette.Accent, onDone)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            BatchButton("Copy", Palette.Accent2, count > 0 && SelectionLogic.canCopy(kind), Modifier.weight(1f), onCopy)
            if (SelectionLogic.canMove(kind)) {
                BatchButton("Move", Palette.Accent, count > 0, Modifier.weight(1f), onMove)
            }
            BatchButton(
                if (kind == BatchKind.BOOKMARKS) "Remove" else "Delete",
                Palette.Danger,
                count > 0 && SelectionLogic.canDelete(kind),
                Modifier.weight(1f),
                onDelete,
            )
        }
    }
}

@Composable
private fun BatchButton(label: String, color: Color, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = if (enabled) 0.16f else 0.05f))
            .border(1.dp, color.copy(alpha = if (enabled) 0.45f else 0.15f), RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 9.dp)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) color else Palette.Muted.copy(alpha = 0.6f),
            fontSize = 11.5.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
        )
    }
}

/** The selection control on a row: shape plus semantics, not only colour. */
@Composable
fun BatchCheck(selected: Boolean, label: String) {
    Box(
        Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (selected) Palette.Accent.copy(alpha = 0.22f) else Color.Transparent)
            .border(1.5.dp, if (selected) Palette.Accent else Palette.Line, CircleShape)
            .semantics {
                contentDescription = label
                stateDescription = if (selected) "Selected" else "Not selected"
            },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Text("\u2713", color = Palette.Accent, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
    }
}

/**
 * The destination picker for a Copy/Move batch: existing folders only, one tap, nothing written
 * before Confirm. The folder the batch is already in is shown but not selectable for a Move.
 */
@Composable
fun BatchTargetDialog(target: BatchTargetUi?, onCancel: () -> Unit, onPick: (String) -> Unit) {
    val state = target ?: return
    BackHandler(enabled = true) { onCancel() }
    var selectedId by remember(state) {
        mutableStateOf(state.folders.firstOrNull { it.id == state.defaultFolderId }?.id)
    }

    Scrim {
        DialogCard {
            Text(state.title, color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                "${state.count} selected. Choose the destination folder.",
                color = Palette.Muted,
                fontSize = 10.5.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier.heightIn(max = 230.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.folders.forEach { folder ->
                    val current = state.action == BatchAction.MOVE && folder.id == state.containerId
                    val active = selectedId == folder.id && !current
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) Palette.Accent.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.22f))
                            .border(1.dp, if (active) Palette.Accent else Palette.Line, RoundedCornerShape(10.dp))
                            .clickable(enabled = !current, role = Role.Button) {
                                selectedId = if (active) null else folder.id
                            }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                folder.name,
                                color = if (current) Palette.Muted else Palette.Text,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (current) "${folder.itemCount} item(s) \u00B7 current folder" else "${folder.itemCount} item(s)",
                                color = Palette.Muted,
                                fontSize = 10.sp,
                            )
                        }
                        if (active) Text("\u2713", color = Palette.Accent, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GradientButton(onCancel, Palette.GreyGradient, Color(0xFFEAEEF5), Modifier.weight(1f), minHeight = 46.dp) {
                    ButtonLabel("Cancel", Color(0xFFEAEEF5))
                }
                val chosen = selectedId
                GradientButton(
                    onClick = { chosen?.let(onPick) },
                    colors = if (chosen == null) Palette.GreyGradient else Palette.AccentGradient,
                    contentColor = Color.White,
                    modifier = Modifier.weight(1f),
                    minHeight = 46.dp,
                ) {
                    ButtonLabel(if (state.action == BatchAction.MOVE) "Move here" else "Copy here", Color.White)
                }
            }
        }
    }
}

/** Confirmation for a destructive batch: the count and the exact scope, never a vague warning. */
@Composable
fun BatchDeleteDialog(pending: BatchDeleteUi?, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val state = pending ?: return
    BackHandler(enabled = true) { onCancel() }
    Scrim {
        DialogCard {
            Text(state.title, color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                state.detail,
                color = Palette.Muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GradientButton(onCancel, Palette.GreyGradient, Color(0xFFEAEEF5), Modifier.weight(1f), minHeight = 46.dp) {
                    ButtonLabel("Cancel", Color(0xFFEAEEF5))
                }
                GradientButton(onConfirm, Palette.RedGradient, Color.White, Modifier.weight(1f), minHeight = 46.dp) {
                    ButtonLabel("Delete ${state.count}", Color.White)
                }
            }
        }
    }
}