package com.bookcovermatcher.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bookcovermatcher.CommentsSection
import com.bookcovermatcher.core.excel.comments.CommentImageState
import com.bookcovermatcher.core.excel.comments.ImageRow
import com.bookcovermatcher.core.format.Formatting
import com.bookcovermatcher.core.excel.comments.LegacyCommentEntry
import com.bookcovermatcher.MainViewModel
import com.bookcovermatcher.UiState
import com.bookcovermatcher.WorkbookCommentsController
import com.bookcovermatcher.WorkbookCommentsUi
import com.bookcovermatcher.ui.components.ButtonLabel
import com.bookcovermatcher.ui.components.GradientButton
import com.bookcovermatcher.ui.components.SegmentedTab
import com.bookcovermatcher.ui.components.SegmentedTabRow
import com.bookcovermatcher.ui.theme.Palette

/**
 * Offline preview of the legacy comments (notes) stored in the staged `.xlsx`/`.xlsm` workbook.
 *
 * Read-only by construction: it lists what the scanner found, shows the original media bytes and can
 * copy one image out through the Storage Access Framework. Nothing here contacts the network, runs a
 * macro or writes to the workbook.
 */
@Composable
fun WorkbookCommentsScreen(ui: UiState, vm: MainViewModel) {
    val state = ui.comments
    val controller = vm.comments
    BackHandler { controller.close() }

    var pendingExport by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/*")) { uri ->
        val path = pendingExport
        pendingExport = null
        if (uri != null && path != null) controller.export(path, uri)
    }

    fun export(path: String) {
        pendingExport = path
        exportLauncher.launch(path.substringAfterLast('/'))
    }

    val exportTreeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) controller.exportSelected(uri)
    }

    Box(Modifier.fillMaxSize().background(Palette.Navy800)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            CommentsHeader(state, controller)
            SegmentedTabRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                SegmentedTab("Comments (${state.comments.size})", state.section == CommentsSection.COMMENTS, Modifier.weight(1f)) {
                    controller.showSection(CommentsSection.COMMENTS)
                }
                SegmentedTab("Images (${state.allImages.size})", state.section == CommentsSection.MEDIA, Modifier.weight(1f)) {
                    controller.showSection(CommentsSection.MEDIA)
                }
                SegmentedTab("Catalogue (${state.tables.sumOf { it.records.size }})", state.section == CommentsSection.CATALOGUE, Modifier.weight(1f)) {
                    controller.showSection(CommentsSection.CATALOGUE)
                }
            }
            Spacer(Modifier.height(8.dp))

            when {
                state.loading -> Centered("Reading the workbook\u2026")
                state.error != null -> Centered(state.error)
                state.section == CommentsSection.COMMENTS -> CommentList(state, controller, ::export)
                state.section == CommentsSection.MEDIA -> MediaBrowser(
                    state,
                    controller,
                    Modifier.fillMaxSize(),
                    onOpen = { path -> controller.openViewer(path) },
                    onExportSelected = { exportTreeLauncher.launch(null) },
                )
                else -> CatalogueBrowser(state, controller, Modifier.fillMaxSize())
            }
        }

        state.selected?.let { comment ->
            CommentDetailDialog(comment, controller, controller::dismissComment, controller::openViewer, ::export)
        }

        state.viewer?.let { viewer ->
            ViewerOverlay(
                row = state.allImages.firstOrNull { it.mediaPath == viewer.mediaPath },
                mediaPath = viewer.mediaPath,
                title = viewer.title,
                associations = viewer.associations,
                controller = controller,
                onClose = controller::closeViewer,
                onExport = ::export,
            )
        }

        SheetPickerDialog(state, controller)
    }
}

@Composable
private fun CommentsHeader(state: WorkbookCommentsUi, controller: WorkbookCommentsController) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Workbook comments", color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                state.workbookName + if (state.model?.fromCache == true) " \u00B7 cached" else "",
                color = Palette.Muted, fontSize = 10.5.sp,
            )
        }
        GradientButton(
            onClick = controller::openSheetPicker,
            colors = Palette.GreyGradient,
            contentColor = Palette.Text,
            // min width, not a fixed width: "Sheets" stays on one line and the button may grow at
            // larger font scales instead of wrapping.
            modifier = Modifier.widthIn(min = 84.dp),
            minHeight = 36.dp,
        ) { ButtonLabel("Sheets", Palette.Text, 11f) }
        Spacer(Modifier.width(6.dp))
        GradientButton(
            onClick = controller::reload,
            colors = Palette.GreyGradient,
            contentColor = Palette.Text,
            modifier = Modifier.width(84.dp),
            minHeight = 36.dp,
        ) { ButtonLabel("Rescan", Palette.Text, 11f) }
        Spacer(Modifier.width(6.dp))
        GradientButton(
            onClick = controller::close,
            colors = Palette.GreyGradient,
            contentColor = Palette.Text,
            modifier = Modifier.width(64.dp),
            minHeight = 36.dp,
        ) { ButtonLabel("Back", Palette.Text, 11f) }
    }
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Palette.Muted, fontSize = 12.5.sp)
    }
}

@Composable
private fun CommentList(
    state: WorkbookCommentsUi,
    controller: WorkbookCommentsController,
    onExport: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        if (state.model?.truncated == true) {
            Note("A scan limit was reached: the list below is what fits, not the whole workbook.", Palette.Warn)
        }
        if (state.comments.isEmpty()) {
            Note(
                "No legacy comments were found in this workbook. Threaded comments are not shown here.",
                Palette.Muted,
            )
            return@Column
        }

        SheetFilter(state, controller)
        Spacer(Modifier.height(6.dp))

        state.visibleComments.forEach { comment ->
            CommentRow(comment, controller)
            Spacer(Modifier.height(6.dp))
        }

        val diagnostics = state.model?.diagnostics.orEmpty()
        if (diagnostics.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Note("Diagnostics (${diagnostics.size})", Palette.Muted)
            diagnostics.take(30).forEach { diagnostic ->
                Note(
                    "\u2022 ${diagnostic.message}",
                    if (diagnostic.level.name == "WARNING") Palette.Warn else Palette.Muted,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SheetFilter(state: WorkbookCommentsUi, controller: WorkbookCommentsController) {
    // The shared selector: every worksheet, horizontally scrollable, one line per label.
    SheetSelector(state, controller)
}

@Composable
private fun FilterChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (active) Palette.OnAccent else Palette.Muted,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (active) Palette.Accent else Palette.Navy700)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

@Composable
private fun CommentRow(
    comment: LegacyCommentEntry,
    controller: WorkbookCommentsController,
) {
    val marker = when (comment.state) {
        CommentImageState.WITH_IMAGES -> "\u25A3" to "Image"
        CommentImageState.UNRESOLVED_IMAGE -> "!" to "Image reference unresolved"
        CommentImageState.TEXT_ONLY -> "T" to "Text only"
    }
    val tint = when (comment.state) {
        CommentImageState.WITH_IMAGES -> Palette.Accent
        CommentImageState.UNRESOLVED_IMAGE -> Palette.Warn
        CommentImageState.TEXT_ONLY -> Palette.Muted
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.Navy700)
            .border(1.dp, Palette.Line, RoundedCornerShape(10.dp))
            .clickable(role = Role.Button) { controller.selectComment(comment.sheetName, comment.cell) }
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(tint.copy(alpha = 0.16f))
                    .border(1.dp, tint.copy(alpha = 0.7f), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) { Text(marker.first, color = tint, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold) }
            Spacer(Modifier.width(8.dp))
            Text(
                "${comment.sheetName}!${comment.cell}",
                color = Palette.Text, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(8.dp))
            Text(marker.second, color = tint, fontSize = 9.5.sp, modifier = Modifier.weight(1f))
            comment.author?.let { Text(it, color = Palette.Muted, fontSize = 9.5.sp, maxLines = 1) }
        }
        Text(
            comment.text.ifBlank { "(no text)" },
            color = Palette.Text,
            fontSize = 11.sp,
            maxLines = 2,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (comment.mediaPaths.isNotEmpty()) {
            Row(Modifier.padding(top = 8.dp)) {
                comment.mediaPaths.take(3).forEach { path ->
                    MediaThumb(path, controller, size = 46.dp) { controller.openViewer(path) }
                    Spacer(Modifier.width(6.dp))
                }
                if (comment.mediaPaths.size > 3) {
                    Text("+${comment.mediaPaths.size - 3}", color = Palette.Muted, fontSize = 10.sp)
                }
            }
        }
        if (comment.state == CommentImageState.UNRESOLVED_IMAGE) {
            Text(
                "An image reference could not be resolved \u2014 tap for details.",
                color = Palette.Warn, fontSize = 9.5.sp, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun Note(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, color = color, fontSize = 10.sp, modifier = Modifier.padding(vertical = 3.dp))
}

@Composable
private fun SmallAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Palette.Accent,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick).padding(vertical = 2.dp),
    )
}

@Composable
private fun CommentDetailDialog(
    comment: LegacyCommentEntry,
    controller: WorkbookCommentsController,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onExport: (String) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .heightIn(max = 560.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.Navy800)
                .border(1.dp, Palette.Line, RoundedCornerShape(14.dp))
                .padding(14.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "${comment.sheetName}!${comment.cell}",
                color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold,
            )
            Text(
                "Legacy comment" + (comment.author?.let { " \u00B7 $it" } ?: ""),
                color = Palette.Muted, fontSize = 10.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                comment.text.ifBlank { "(no text)" },
                color = Palette.Text, fontSize = 12.sp,
            )
            if (comment.mediaPaths.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("Images from this comment", color = Palette.Muted, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Row {
                    comment.mediaPaths.forEach { path ->
                        Column(Modifier.padding(end = 8.dp)) {
                            MediaThumb(path, controller, size = 72.dp) { onOpen(path) }
                            Row {
                                SmallAction("Open") { onOpen(path) }
                                Spacer(Modifier.width(10.dp))
                                SmallAction("Export") { onExport(path) }
                            }
                        }
                    }
                }
            }
            val unresolved = comment.media.filter { it.mediaPath == null }
            if (unresolved.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                unresolved.forEach { ref ->
                    Text(
                        "Unresolved reference '${ref.reference}': ${ref.detail ?: "no detail"}",
                        color = Palette.Warn, fontSize = 9.5.sp,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            GradientButton(
                onClick = onDismiss,
                colors = Palette.GreyGradient,
                contentColor = Palette.Text,
                modifier = Modifier.fillMaxWidth(),
                minHeight = 40.dp,
            ) { ButtonLabel("Close", Palette.Text, 12f) }
        }
    }
}

@Composable
private fun ViewerOverlay(
    row: com.bookcovermatcher.core.excel.comments.ImageRow?,
    mediaPath: String,
    title: String,
    associations: List<String>,
    controller: WorkbookCommentsController,
    onClose: () -> Unit,
    onExport: (String) -> Unit,
) {
    BackHandler { onClose() }
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xF00B121C))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp)) {
            val bytes = rememberMediaBytes(mediaPath, controller)
            val dimensions = remember(bytes) { bytes?.let { measure(it) } }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (associations.isEmpty()) "No comment association" else associations.joinToString(", "),
                        color = Palette.Muted, fontSize = 9.5.sp, maxLines = 2,
                    )
                    row?.let { image ->
                        Text(
                            listOfNotNull(
                                image.format.uppercase(),
                                if (image.sizeBytes >= 0) Formatting.fileSize(image.sizeBytes) else null,
                                dimensions,
                                if (image.state == com.bookcovermatcher.core.excel.comments.ImageState.LINKED) {
                                    "legacy comment image"
                                } else {
                                    "no verified association"
                                },
                            ).joinToString(" \u00B7 "),
                            color = Palette.Muted, fontSize = 9.sp,
                        )
                        image.catalogue.firstOrNull()?.let { hit ->
                            val line = listOfNotNull(hit.title, hit.author, hit.circle).joinToString(" \u00B7 ")
                            if (line.isNotEmpty()) {
                                Text(line, color = Palette.Text, fontSize = 9.5.sp, maxLines = 2)
                            }
                        }
                    }
                }
                SmallAction("Export") { onExport(mediaPath) }
                Spacer(Modifier.width(14.dp))
                SmallAction("Close") { onClose() }
            }
            Spacer(Modifier.height(8.dp))
            val bitmap = remember(bytes) { bytes?.let { decodeDownsampled(it, maxEdge = 2048) } }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (bitmap != null) {
                    Image(
                        bitmap.asImageBitmap(),
                        contentDescription = "Image $mediaPath",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        "This image cannot be shown: it is missing, larger than the previewer limit, or not a " +
                            "format Android can decode. It can still be exported unchanged.",
                        color = Palette.Warn, fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaThumb(
    path: String,
    controller: WorkbookCommentsController?,
    size: androidx.compose.ui.unit.Dp,
    onOpen: () -> Unit,
) {
    val bytes = rememberMediaBytes(path, controller)
    val bitmap = remember(bytes) { bytes?.let { decodeDownsampled(it, maxEdge = 512) } }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.Navy800)
            .border(1.dp, Palette.Line, RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClickLabel = "Open image", onClick = onOpen),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap.asImageBitmap(),
                contentDescription = "Thumbnail of ${path.substringAfterLast('/')}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text("?", color = Palette.Muted, fontSize = 15.sp)
        }
    }
}

@Composable
private fun rememberMediaBytes(path: String, controller: WorkbookCommentsController?): ByteArray? {
    var bytes by remember(path, controller) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(path, controller) { bytes = controller?.mediaBytes(path) }
    return bytes
}

/** Decodes with downsampling so a 4000-pixel photo never becomes a 64 MB bitmap on a thumbnail row. */
private fun decodeDownsampled(bytes: ByteArray, maxEdge: Int): android.graphics.Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    while (longest > 0 && longest / sample > maxEdge) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
} catch (e: OutOfMemoryError) {
    null
} catch (e: Exception) {
    null
}
/** Pixel size without decoding the bitmap; null when the data is not a readable image. */
private fun measure(bytes: ByteArray): String? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth > 0 && bounds.outHeight > 0) "${bounds.outWidth}x${bounds.outHeight}" else null
} catch (e: Exception) {
    null
}