package com.bookcovermatcher.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookcovermatcher.MainViewModel
import com.bookcovermatcher.UiState
import com.bookcovermatcher.core.excel.comments.MetricChange
import com.bookcovermatcher.core.excel.comments.SheetDiff
import com.bookcovermatcher.core.excel.comments.SheetPresence
import com.bookcovermatcher.core.excel.comments.WorkbookDiff
import com.bookcovermatcher.ui.components.ButtonLabel
import com.bookcovermatcher.ui.components.GradientButton
import com.bookcovermatcher.ui.theme.Palette

/**
 * Read-only metadata comparison of two saved workbooks: worksheets by name, order, visibility, declared
 * used range and the counts the parser can establish. It is **not** a cell-by-cell diff, and equal totals
 * are not proof of equality.
 */
@Composable
fun WorkbookComparisonScreen(ui: UiState, vm: MainViewModel) {
    val state = ui.comparison
    BackHandler { vm.closeComparison() }

    Box(Modifier.fillMaxSize().background(Palette.Navy800)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Compare workbooks", color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Metadata-level comparison, not a cell-by-cell diff", color = Palette.Muted, fontSize = 9.5.sp)
                }
                GradientButton(
                    onClick = vm::closeComparison,
                    colors = Palette.GreyGradient,
                    contentColor = Palette.Text,
                    modifier = Modifier.width(72.dp),
                    minHeight = 36.dp,
                ) { ButtonLabel("Back", Palette.Text, 11f) }
            }

            when {
                state.loading -> CenteredNote("Reading both workbooks\u2026")
                state.error != null -> CenteredNote(state.error, Palette.Warn)
                state.diff == null -> CenteredNote("Pick two workbooks in the library, then press Compare.")
                else -> DiffBody(state.diff)
            }
        }
    }
}

@Composable
private fun CenteredNote(text: String, color: androidx.compose.ui.graphics.Color = Palette.Muted) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = color, fontSize = 12.sp)
    }
}

@Composable
private fun DiffBody(diff: WorkbookDiff) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item { SidesCard(diff) }
        item { Spacer(Modifier.height(8.dp)) }
        item {
            SectionTitle(
                "Summary \u00B7 ${diff.additions.size} added \u00B7 ${diff.removals.size} removed \u00B7 " +
                    "${diff.changed.size} changed \u00B7 ${diff.identicalSheets.size} identical",
            )
        }
        if (diff.totals.isEmpty()) {
            item { Line("The overall counts match. That does not prove the workbooks are equivalent.", Palette.Muted) }
        } else {
            items(diff.totals) { change -> ChangeLine(change) }
        }
        if (diff.orderChanged) item { Line("Worksheet order differs between the two workbooks.", Palette.Warn) }
        if (diff.headersChanged) {
            item { Line("The catalogue header row differs (or exists on one side only).", Palette.Warn) }
        }
        item { Spacer(Modifier.height(8.dp)) }
        item { SectionTitle("Worksheets") }
        items(diff.sheets) { sheet -> SheetRow(sheet) }
        item {
            Spacer(Modifier.height(10.dp))
            Text(
                "Counts come from the parser: comments, verified image links, unresolved references, media " +
                    "files and catalogue records. \u201Cunknown\u201D means the parser could not establish a value; it is " +
                    "never treated as zero.",
                color = Palette.Muted, fontSize = 8.5.sp,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SidesCard(diff: WorkbookDiff) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.Navy700)
            .border(1.dp, Palette.Line, RoundedCornerShape(10.dp)).padding(10.dp),
    ) {
        SideLine("Left", diff.left.label, diff.left.originalName, diff.left.failure, diff.left.note)
        Spacer(Modifier.height(6.dp))
        SideLine("Right", diff.right.label, diff.right.originalName, diff.right.failure, diff.right.note)
        if (diff.selfComparison) {
            Spacer(Modifier.height(6.dp))
            Text("Both sides are the same workbook (identical content).", color = Palette.Warn, fontSize = 9.5.sp)
        }
    }
}

@Composable
private fun SideLine(role: String, label: String, original: String, failure: String?, note: String?) {
    Row(verticalAlignment = Alignment.Top) {
        Text("$role:", color = Palette.Muted, fontSize = 9.sp, modifier = Modifier.width(40.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = Palette.Text, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(original, color = Palette.Muted, fontSize = 9.sp, maxLines = 1)
            if (failure != null) Text("Could not be read: $failure", color = Palette.Danger, fontSize = 9.sp)
            else if (note != null) Text(note, color = Palette.Muted, fontSize = 8.5.sp)
        }
    }
}

@Composable
private fun SheetRow(sheet: SheetDiff) {
    val badge: String
    val colour: androidx.compose.ui.graphics.Color
    when (sheet.presence) {
        SheetPresence.BOTH -> if (sheet.changes.isEmpty()) {
            badge = "\u2713"; colour = Palette.Ok
        } else {
            badge = "~"; colour = Palette.Warn
        }
        SheetPresence.RIGHT_ONLY -> { badge = "+"; colour = Palette.Ok }
        SheetPresence.LEFT_ONLY -> { badge = "\u2212"; colour = Palette.Danger }
    }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .clip(RoundedCornerShape(9.dp)).background(Palette.Navy700)
            .border(1.dp, Palette.Line, RoundedCornerShape(9.dp)).padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                badge, color = colour, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.width(18.dp),
            )
            Text(
                sheet.name, color = Palette.Text, fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, modifier = Modifier.weight(1f),
            )
            Text(
                when (sheet.presence) {
                    SheetPresence.BOTH -> if (sheet.changes.isEmpty()) "Same metadata" else "${sheet.changes.size} change(s)"
                    SheetPresence.RIGHT_ONLY -> "Only in right"
                    SheetPresence.LEFT_ONLY -> "Only in left"
                },
                color = colour, fontSize = 8.5.sp,
            )
        }
        sheet.changes.forEach { change ->
            Text(
                "${change.field}: ${change.left ?: "unknown"} \u2192 ${change.right ?: "unknown"}",
                color = Palette.Warn, fontSize = 9.sp,
            )
        }
        if (sheet.presence != SheetPresence.BOTH) {
            val metrics = sheet.left ?: sheet.right
            if (metrics != null) {
                Text(
                    "${metrics.comments} comment(s) \u00B7 ${metrics.linkedImages} image(s) \u00B7 " +
                        (metrics.dimension ?: "used range not declared"),
                    color = Palette.Muted, fontSize = 8.5.sp,
                )
            }
        }
    }
}

@Composable
private fun ChangeLine(change: MetricChange) {
    Text(
        "${change.field}: ${change.left ?: "unknown"} \u2192 ${change.right ?: "unknown"}",
        color = if (change.unknown) Palette.Warn else Palette.Muted,
        fontSize = 9.5.sp,
        modifier = Modifier.padding(vertical = 1.dp),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text, color = Palette.Text, fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun Line(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, color = color, fontSize = 9.5.sp, modifier = Modifier.padding(vertical = 2.dp))
}
