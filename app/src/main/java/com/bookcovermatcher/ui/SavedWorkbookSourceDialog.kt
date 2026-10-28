package com.bookcovermatcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bookcovermatcher.MainViewModel
import com.bookcovermatcher.UiState
import com.bookcovermatcher.data.SavedWorkbook
import com.bookcovermatcher.ui.theme.Palette

/**
 * Home/Search source picker: reuse a workbook that is already in the app's saved-workbook library.
 *
 * The list is the **existing** library data (`ui.savedWorkbooks`, same ids and hashes the library screen
 * uses) — there is no second library. Picking an entry stages that app-managed copy as the scan source,
 * so the external "Choose file" workflow is untouched and can still be used.
 */
@Composable
fun SavedWorkbookSourceDialog(ui: UiState, vm: MainViewModel) {
    Dialog(onDismissRequest = vm::closeSavedWorkbookPicker) {
        Column(
            Modifier
                .widthIn(max = 440.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.Navy800)
                .border(1.dp, Palette.Line, RoundedCornerShape(14.dp))
                .padding(14.dp),
        ) {
            Text("Use a saved workbook", color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                "The scan reads the app's private copy. The original file you imported is never modified, " +
                    "and no macro is run.",
                color = Palette.Muted, fontSize = 9.sp,
            )
            Spacer(Modifier.height(10.dp))
            when {
                ui.workbookLoading -> Text("Opening the saved copy\u2026", color = Palette.Muted, fontSize = 11.sp)
                ui.savedWorkbooks.isEmpty() -> Text(
                    "No saved workbooks yet. Import one in More \u2192 Saved workbooks, then come back here.",
                    color = Palette.Muted, fontSize = 11.sp,
                )
                else -> LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(ui.savedWorkbooks, key = { it.id }) { entry -> SavedSourceRow(entry) { vm.useSavedWorkbook(entry.id) } }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Close",
                color = Palette.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .clickable(role = Role.Button) { vm.closeSavedWorkbookPicker() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun SavedSourceRow(entry: SavedWorkbook, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(Palette.Navy700)
            .border(1.dp, Palette.Line, RoundedCornerShape(9.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                (if (entry.pinned) "\u2605 " else "") + entry.displayName,
                color = Palette.Text, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            )
            // Always shown: two saved workbooks may share a display name, and the original filename is
            // what tells them apart.
            Text(entry.originalName, color = Palette.Muted, fontSize = 9.sp, maxLines = 1)
            val counts = listOfNotNull(
                entry.sheetCount?.let { "$it sheet(s)" },
                entry.commentCount?.let { "$it comment(s)" },
                entry.imageCount?.let { "$it image(s)" },
            )
            if (counts.isNotEmpty()) {
                Text(counts.joinToString(" \u00B7 "), color = Palette.Muted, fontSize = 8.5.sp)
            }
        }
        Text("Use", color = Palette.Accent, fontSize = 10.5.sp, fontWeight = FontWeight.ExtraBold)
    }
}
