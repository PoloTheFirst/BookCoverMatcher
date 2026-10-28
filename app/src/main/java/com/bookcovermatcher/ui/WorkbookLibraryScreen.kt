package com.bookcovermatcher.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bookcovermatcher.MainViewModel
import com.bookcovermatcher.UiState
import com.bookcovermatcher.core.format.Formatting
import com.bookcovermatcher.data.SavedWorkbook
import com.bookcovermatcher.ui.components.ButtonLabel
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

    Box(Modifier.fillMaxSize().background(Palette.Navy800)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Saved workbooks", color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        if (ui.libraryBusy) "Copying the workbook\u2026" else "${ui.savedWorkbooks.size} saved",
                        color = Palette.Muted, fontSize = 10.5.sp,
                    )
                }
                GradientButton(
                    onClick = { pickWorkbook.launch(arrayOf("*/*")) },
                    colors = Palette.NavyGradient,
                    contentColor = Palette.Text,
                    modifier = Modifier.width(96.dp),
                    minHeight = 36.dp,
                    enabled = !ui.libraryBusy,
                ) { ButtonLabel("Import", Palette.Text, 11f) }
                Spacer(Modifier.width(6.dp))
                GradientButton(
                    onClick = vm::closeLibrary,
                    colors = Palette.GreyGradient,
                    contentColor = Palette.Text,
                    modifier = Modifier.width(64.dp),
                    minHeight = 36.dp,
                ) { ButtonLabel("Back", Palette.Text, 11f) }
            }

            if (ui.savedWorkbooks.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "No workbooks saved yet. Import an .xlsx or .xlsm file and the app keeps its own copy, " +
                            "so it keeps working when the original file moves.",
                        color = Palette.Muted, fontSize = 12.sp,
                    )
                }
            } else {
                if (ui.comparison.selection.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Palette.Accent.copy(alpha = 0.12f)).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${ui.comparison.selection.size}/2 selected for comparison",
                            color = Palette.Text, fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        LibraryAction("Compare") { vm.runComparison() }
                        Spacer(Modifier.width(12.dp))
                        LibraryAction("Clear", Palette.Muted) { vm.clearComparisonSelection() }
                    }
                }
                LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    items(ui.savedWorkbooks, key = { it.id }) { entry ->
                        LibraryRow(
                            entry = entry,
                            selectedForComparison = ui.comparison.isSelected(entry.id),
                            onOpen = { vm.openSavedWorkbook(entry.id) },
                            onCompare = { vm.toggleComparisonSelection(entry.id) },
                            onRename = { renameId = entry.id; renameText = entry.displayName },
                            onPin = { vm.setSavedWorkbookPinned(entry.id, !entry.pinned) },
                            onRemove = { removeId = entry.id },
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }

            StoragePanel(ui, vm)
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
    }
}

@Composable
private fun LibraryRow(
    entry: SavedWorkbook,
    selectedForComparison: Boolean,
    onOpen: () -> Unit,
    onCompare: () -> Unit,
    onRename: () -> Unit,
    onPin: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.Navy700)
            .border(
                1.dp,
                when {
                    selectedForComparison -> Palette.Accent
                    entry.pinned -> Palette.Accent.copy(alpha = 0.6f)
                    else -> Palette.Line
                },
                RoundedCornerShape(10.dp),
            )
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.displayName, color = Palette.Text, fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f),
            )
            if (entry.pinned) {
                Text("PINNED", color = Palette.Accent, fontSize = 8.5.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        Text(
            "${entry.originalName} \u00B7 ${entry.format.uppercase()} \u00B7 ${Formatting.fileSize(entry.sizeBytes)}",
            color = Palette.Muted, fontSize = 9.5.sp, maxLines = 1,
        )
        Text(
            entry.lastOpenedAt?.let { "Last opened ${stamp(it)}" } ?: "Never opened in this app",
            color = Palette.Muted, fontSize = 9.sp,
        )
        Text(
            if (entry.sheetCount != null) {
                "${entry.sheetCount} sheet(s) \u00B7 ${entry.commentCount ?: 0} comment(s) \u00B7 " +
                    "${entry.imageCount ?: 0} image(s)"
            } else {
                "Counts appear after the first open"
            },
            color = Palette.Muted, fontSize = 9.sp,
        )
        Row(Modifier.padding(top = 6.dp)) {
            LibraryAction("Open") { onOpen() }
            Spacer(Modifier.width(12.dp))
            LibraryAction(if (selectedForComparison) "Unselect" else "Compare") { onCompare() }
            Spacer(Modifier.width(12.dp))
            LibraryAction("Rename") { onRename() }
            Spacer(Modifier.width(12.dp))
            LibraryAction(if (entry.pinned) "Unpin" else "Pin") { onPin() }
            Spacer(Modifier.width(12.dp))
            LibraryAction("Remove", Palette.Danger) { onRemove() }
        }
    }
}

@Composable
private fun StoragePanel(ui: UiState, vm: MainViewModel) {
    Column(Modifier.fillMaxWidth().background(Palette.Navy700).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text("Storage", color = Palette.Text, fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold)
        Text("Saved workbook copies: ${Formatting.fileSize(ui.libraryBytes)}", color = Palette.Muted, fontSize = 9.5.sp)
        Text("Parsed caches (regenerable): ${Formatting.fileSize(ui.cacheBytes)}", color = Palette.Muted, fontSize = 9.5.sp)
        Row(Modifier.padding(top = 4.dp)) {
            LibraryAction("Clear parsed caches") { vm.clearParseCaches() }
        }
        Text(
            "Clearing caches never deletes a saved workbook; they are rebuilt on the next open.",
            color = Palette.Muted, fontSize = 8.5.sp,
        )
    }
}

@Composable
private fun LibraryAction(
    label: String,
    color: androidx.compose.ui.graphics.Color = Palette.Accent,
    onClick: () -> Unit,
) {
    Text(
        label,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick).padding(vertical = 2.dp),
    )
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
