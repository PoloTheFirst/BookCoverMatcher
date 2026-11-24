// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import com.bookcovermatcher.ui.components.ScreenHeading
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bookcovermatcher.MainViewModel
import com.bookcovermatcher.UiState
import com.bookcovermatcher.core.format.Formatting
import com.bookcovermatcher.data.SavedWorkbook
import com.bookcovermatcher.ui.components.GradientButton
import com.bookcovermatcher.ui.theme.Palette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saved workbooks: app-managed copies, the display name, pinning/recency, storage and removal.
 *
 * The workbook bytes and the parsed caches are shown separately, because only the caches can be
 * rebuilt — clearing them never touches a saved workbook.
 */
@Composable
fun WorkbookLibraryScreen(ui: UiState, vm: MainViewModel) {
    BackHandler { vm.closeLibrary() }
    val pickWorkbook = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importWorkbook(uri)
    }
    var renameId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var removeId by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().systemBarsPadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        ScreenHeading("Saved workbooks")
                        Text(
                            if (ui.libraryBusy) "Copying the workbook…" else "${ui.savedWorkbooks.size} saved",
                            color = Palette.Muted, style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    LibraryAction("Back") { vm.closeLibrary() }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { pickWorkbook.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(), enabled = !ui.libraryBusy,
                    ) { Text("Import") }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GradientButton(
                            onClick = vm::openOneDriveBrowser,
                            colors = Palette.NavyGradient, contentColor = Palette.Text,
                            modifier = Modifier.weight(1f), contentPadding = 8.dp,
                            enabled = !ui.libraryBusy && ui.oneDrive.entryEnabled,
                        ) {
                            CloudImportLabel("OneDrive", ui.oneDrive.accountLabel, ui.oneDrive.signedIn,
                                ui.oneDrive.expired, ui.oneDrive.signingIn)
                        }
                        GradientButton(
                            onClick = vm::openGoogleDriveBrowser,
                            colors = Palette.NavyGradient, contentColor = Palette.Text,
                            modifier = Modifier.weight(1f), contentPadding = 8.dp,
                            enabled = !ui.libraryBusy,
                        ) {
                            CloudImportLabel("Google Drive", ui.googleDrive.accountLabel, ui.googleDrive.signedIn,
                                ui.googleDrive.expired, ui.googleDrive.signingIn)
                        }
                    }
                }
            }
            if (ui.savedWorkbooks.isEmpty()) item {
                Text(
                    "No workbooks saved yet. Import an .xlsx or .xlsm file and the app keeps its own copy, " +
                        "so it keeps working when the original file moves.",
                    color = Palette.Muted, style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (ui.comparison.selection.isNotEmpty()) item {
                FlowRow(
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.large)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${ui.comparison.selection.size}/2 selected for comparison", color = Palette.Text)
                    LibraryAction("Compare") { vm.runComparison() }
                    LibraryAction("Clear", Palette.Muted) { vm.clearComparisonSelection() }
                }
            }
            items(ui.savedWorkbooks, key = { it.id }) { entry ->
                LibraryRow(
                    entry = entry,
                    ui = ui,
                    vm = vm,
                    selectedForComparison = ui.comparison.isSelected(entry.id),
                    onOpen = { vm.openSavedWorkbook(entry.id) },
                    onCompare = { vm.toggleComparisonSelection(entry.id) },
                    onRename = { renameId = entry.id; renameText = entry.displayName },
                    onPin = { vm.setSavedWorkbookPinned(entry.id, !entry.pinned) },
                    onRemove = { removeId = entry.id },
                )
            }
            item { StoragePanel(ui, vm) }
        }

        renameId?.let { id ->
            TextInputDialog(
                title = "Rename workbook",
                hint = "Display name (the file itself is not renamed)",
                value = renameText,
                onChange = { renameText = it },
                confirmLabel = "Rename",
                onConfirm = { vm.renameSavedWorkbook(id, renameText); renameId = null },
                onDismiss = { renameId = null },
            )
        }

        removeId?.let { id ->
            val entry = ui.savedWorkbooks.firstOrNull { it.id == id }
            ConfirmDialog(
                title = "Remove from the library?",
                body = "\u201C${entry?.displayName ?: "This workbook"}\u201D will be removed together with the " +
                    "app's private copy and its parsed cache. The original file you imported is not touched.",
                confirmLabel = "Remove",
                onConfirm = { vm.removeSavedWorkbook(id); removeId = null },
                onDismiss = { removeId = null },
            )
        }

        // The OneDrive import panel (v2.6.0-dev.1 Phase 4). A dialog over this screen, so Back closes it
        // and the library underneath is unchanged.
        OneDriveBrowserDialog(ui, vm)
        GoogleDriveBrowserDialog(ui, vm)
    }
}

@Composable
private fun LibraryRow(
    entry: SavedWorkbook,
    ui: UiState,
    vm: MainViewModel,
    selectedForComparison: Boolean,
    onOpen: () -> Unit,
    onCompare: () -> Unit,
    onRename: () -> Unit,
    onPin: () -> Unit,
    onRemove: () -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .then(if (selectedForComparison || entry.pinned) Modifier.border(1.dp, Palette.Accent, shape) else Modifier)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.displayName, color = Palette.Text, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
            )
            if (entry.pinned) {
                Text("PINNED", color = Palette.Accent, fontSize = 8.5.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        Text(
            "${entry.originalName} \u00B7 ${entry.format.uppercase()} \u00B7 ${Formatting.fileSize(entry.sizeBytes)}",
            color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
        )
        WorkbookSourceInfo(entry, ui, vm)
        Text(
            entry.lastOpenedAt?.let { "Last opened ${stamp(it)}" } ?: "Never opened in this app",
            color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
        )
        Text(
            if (entry.sheetCount != null) {
                "${entry.sheetCount} sheet(s) \u00B7 ${entry.commentCount ?: 0} comment(s) \u00B7 " +
                    "${entry.imageCount ?: 0} image(s)"
            } else {
                "Counts appear after the first open"
            },
            color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LibraryAction("Open", onClick = onOpen)
            LibraryAction(if (selectedForComparison) "Unselect" else "Compare", onClick = onCompare)
            LibraryAction("Rename", onClick = onRename)
            LibraryAction(if (entry.pinned) "Unpin" else "Pin", onClick = onPin)
            LibraryAction("Remove", Palette.Danger, onClick = onRemove)
        }
    }
}

@Composable
private fun StoragePanel(ui: UiState, vm: MainViewModel) {
    Column(Modifier.fillMaxWidth().padding(4.dp)) {
        Text("Storage", color = Palette.Text, style = MaterialTheme.typography.titleMedium)
        Text("Saved workbook copies: ${Formatting.fileSize(ui.libraryBytes)}", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        Text("Parsed caches (regenerable): ${Formatting.fileSize(ui.cacheBytes)}", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.padding(top = 4.dp)) {
            LibraryAction("Clear parsed caches") { vm.clearParseCaches() }
        }
        Text(
            "Clearing caches never deletes a saved workbook; they are rebuilt on the next open.",
            color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun LibraryAction(
    label: String,
    color: androidx.compose.ui.graphics.Color = Palette.Accent,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp)) {
        Text(label, color = color)
    }
}

@Composable
private fun TextInputDialog(
    title: String,
    hint: String,
    value: String,
    onChange: (String) -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(14.dp)).background(Palette.Navy800)
                .border(1.dp, Palette.Line, RoundedCornerShape(14.dp)).padding(14.dp),
        ) {
            Text(title, color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(8.dp))
            Text(hint, color = Palette.Muted, fontSize = 9.sp)
            Box(
                Modifier.fillMaxWidth().padding(top = 4.dp)
                    .clip(RoundedCornerShape(9.dp)).background(Palette.Navy700)
                    .border(1.dp, Palette.Line, RoundedCornerShape(9.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = TextStyle(color = Palette.Text, fontSize = 12.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(Modifier.padding(top = 10.dp)) {
                LibraryAction("Cancel", Palette.Muted) { onDismiss() }
                Spacer(Modifier.width(14.dp))
                LibraryAction(confirmLabel) { onConfirm() }
            }
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(14.dp)).background(Palette.Navy800)
                .border(1.dp, Palette.Line, RoundedCornerShape(14.dp)).padding(14.dp),
        ) {
            Text(title, color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
            Text(body, color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.padding(top = 10.dp)) {
                LibraryAction("Cancel", Palette.Muted) { onDismiss() }
                Spacer(Modifier.width(14.dp))
                LibraryAction(confirmLabel, Palette.Danger) { onConfirm() }
            }
        }
    }
}

private fun stamp(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
