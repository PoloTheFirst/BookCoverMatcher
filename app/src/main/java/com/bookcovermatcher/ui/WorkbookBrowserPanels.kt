package com.bookcovermatcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.bookcovermatcher.core.excel.comments.CatalogueFiltering
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bookcovermatcher.WorkbookCommentsController
import com.bookcovermatcher.WorkbookCommentsUi
import com.bookcovermatcher.core.excel.comments.ImageRow
import com.bookcovermatcher.core.excel.comments.ImageSort
import com.bookcovermatcher.core.excel.comments.ImageState
import com.bookcovermatcher.core.excel.comments.SourceLocations
import com.bookcovermatcher.ui.theme.Palette

/**
 * The All Images browser: search, association filters, sorting, grid or list, multi-select and the
 * batch export entry point. Plain Compose, no new dependency.
 */
@Composable
fun MediaBrowser(
    state: WorkbookCommentsUi,
    controller: WorkbookCommentsController,
    modifier: Modifier = Modifier,
    onOpen: (String) -> Unit,
    onExportSelected: () -> Unit,
) {
    Column(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        SearchField(state.imageSearch, controller::setImageSearch, "Search filename, sheet, cell, title, author\u2026")
        Spacer(Modifier.height(6.dp))
        SheetSelector(state, controller)
        Spacer(Modifier.height(6.dp))
        StateFilters(state, controller)
        Spacer(Modifier.height(6.dp))
        SortRow(state, controller)
        Spacer(Modifier.height(6.dp))
        ActiveFilterRow(state, controller)
        Spacer(Modifier.height(6.dp))
        if (state.filterPickerOpen) {
            FilterPanel(state, controller)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${state.visibleImages.size} of ${state.allImages.size} image(s)",
                color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.weight(1f),
            )
            if (state.catalogueFilters.isNotEmpty()) SmallAction("Clear filters") { controller.clearImageFilters() }
        }
        if (state.selectedImages.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Palette.Accent.copy(alpha = 0.12f)).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${state.selectedImages.size} selected",
                    color = Palette.Text, fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                SmallAction("Export\u2026") { onExportSelected() }
                Spacer(Modifier.width(12.dp))
                SmallAction("Clear") { controller.clearSelection() }
            }
        }
        Spacer(Modifier.height(4.dp))

        when {
            state.allImages.isEmpty() -> EmptyNote("This workbook has no media files under xl/media.")
            state.visibleImages.isEmpty() -> EmptyNote("No image matches the current search and filters.")
            state.gridMode -> ImageGrid(state, controller, onOpen)
            else -> ImageList(state, controller, onOpen)
        }
    }
}

@Composable
private fun StateFilters(state: WorkbookCommentsUi, controller: WorkbookCommentsController) {
    val linked = state.allImages.count { it.state == ImageState.LINKED }
    val orphan = state.allImages.count { it.state == ImageState.UNREFERENCED }
    val unresolved = state.allImages.count { it.state == ImageState.UNRESOLVED }
    Row(Modifier.fillMaxWidth()) {
        ChoiceChip("All ${state.allImages.size}", state.imageFilter == null) { controller.clearImageStates() }
        Spacer(Modifier.width(5.dp))
        ChoiceChip("Linked $linked", state.imageFilter == ImageState.LINKED) {
            controller.setImageFilter(ImageState.LINKED)
        }
        Spacer(Modifier.width(5.dp))
        ChoiceChip("Unreferenced $orphan", state.imageFilter == ImageState.UNREFERENCED) {
            controller.setImageFilter(ImageState.UNREFERENCED)
        }
        Spacer(Modifier.width(5.dp))
        ChoiceChip("Unresolved $unresolved", state.imageFilter == ImageState.UNRESOLVED) {
            controller.setImageFilter(ImageState.UNRESOLVED)
        }
    }
}

@Composable
private fun SortRow(state: WorkbookCommentsUi, controller: WorkbookCommentsController) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SortChip("Name", state.imageSort == ImageSort.NAME) { controller.setImageSort(ImageSort.NAME) }
        SortChip("Size", state.imageSort == ImageSort.SIZE) { controller.setImageSort(ImageSort.SIZE) }
        SortChip("Cell", state.imageSort == ImageSort.CELL) { controller.setImageSort(ImageSort.CELL) }
        SortChip("Sheet", state.imageSort == ImageSort.WORKSHEET) { controller.setImageSort(ImageSort.WORKSHEET) }
        SortChip("Title", state.imageSort == ImageSort.TITLE) { controller.setImageSort(ImageSort.TITLE) }
        Spacer(Modifier.weight(1f))
        SmallAction(if (state.ascending) "A\u2192Z" else "Z\u2192A") { controller.setAscending(!state.ascending) }
        Spacer(Modifier.width(10.dp))
        SmallAction(if (state.gridMode) "List" else "Grid") { controller.setGridMode(!state.gridMode) }
        if (state.gridMode) {
            Spacer(Modifier.width(10.dp))
            ChoiceChip("S", state.thumbSize == 0) { controller.setThumbSize(0) }
            Spacer(Modifier.width(4.dp))
            ChoiceChip("M", state.thumbSize == 1) { controller.setThumbSize(1) }
            Spacer(Modifier.width(4.dp))
            ChoiceChip("L", state.thumbSize == 2) { controller.setThumbSize(2) }
        }
    }
}

/* replaced by ActiveFilterRow + FilterPanel */

/**
 * Horizontally scrollable worksheet selector: every worksheet is reachable (no `take(n)`), labels stay
 * on one line and are ellipsised, and the raw name is both the selection identity and the content
 * description, so a truncated label is still announced in full.
 */
@Composable
internal fun SheetSelector(
    state: WorkbookCommentsUi,
    controller: WorkbookCommentsController,
    modifier: Modifier = Modifier,
) {
    if (state.sheets.isEmpty()) return
    val names = remember(state.sheets) { state.sheets.map { it.name }.distinct() }
    LazyRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item { ChoiceChip("All sheets", state.selectedSheet == null) { controller.selectSheet(null) } }
        items(names) { name -> SheetChip(name, state.selectedSheet == name) { controller.selectSheet(name) } }
    }
}

@Composable
private fun SheetChip(name: String, active: Boolean, onClick: () -> Unit) {
    Text(
        name.trim(),
        color = if (active) Palette.OnAccent else Palette.Muted,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(max = 168.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (active) Palette.Accent else Palette.Navy700)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = name }
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

/** The active catalogue filters (each removable) and the entry point to the field/value picker. */
@Composable
private fun ActiveFilterRow(state: WorkbookCommentsUi, controller: WorkbookCommentsController) {
    if (state.filterFields.isEmpty() && state.catalogueFilters.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SmallAction("Filter\u2026") { controller.openFilterPicker() }
        Spacer(Modifier.width(8.dp))
        state.activeFilters.forEach { filter ->
            val label = state.filterFields.firstOrNull { it.key == filter.key }?.label
                ?: state.tables.firstOrNull { it.sheetName == filter.sheetName }
                    ?.fields?.firstOrNull { it.column == filter.column }?.name
                ?: CatalogueFiltering.columnLetter(filter.column)
            Text(
                "$label: ${filter.value} \u00D7",
                color = Palette.OnAccent, fontSize = 9.5.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 220.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Palette.Accent.copy(alpha = 0.85f))
                    .clickable(role = Role.Button) { controller.removeCatalogueFilter(filter.key) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Spacer(Modifier.width(5.dp))
        }
        if (state.catalogueFilters.isNotEmpty()) {
            SmallAction("Clear") { controller.clearCatalogueFilters() }
        }
    }
}

/**
 * Field list -> value list, both searchable and lazily rendered. Values are never truncated by count;
 * a field with hundreds of distinct values is searched instead of being rendered at once.
 */
@Composable
private fun FilterPanel(state: WorkbookCommentsUi, controller: WorkbookCommentsController) {
    val field = state.activeFilterField
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (field == null) "Filter by a catalogue column" else field.label,
                color = Palette.Text, fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            SmallAction(if (field == null) "Close" else "Fields") {
                if (field == null) controller.closeFilterPicker() else controller.backToFilterFields()
            }
        }
        Spacer(Modifier.height(6.dp))
        if (field == null) {
            SearchField(state.filterSearch, controller::setFilterSearch, "Search columns\u2026")
            Spacer(Modifier.height(4.dp))
            val needle = state.filterSearch.trim()
            val fields = state.filterFields.filter {
                needle.isEmpty() || it.label.contains(needle, ignoreCase = true)
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                items(fields) { item ->
                    val current = state.catalogueFilters.firstOrNull { it.key == item.key }?.value
                    Text(
                        "${item.label}  \u00B7  ${item.valueCount} value(s)" + (current?.let { "  \u00B7  $it" } ?: ""),
                        color = if (current != null) Palette.Accent else Palette.Text,
                        fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                            .clickable(role = Role.Button) { controller.openFilterField(item.key) }
                            .padding(vertical = 7.dp, horizontal = 4.dp),
                    )
                }
                if (fields.isEmpty()) item { EmptyNote("No catalogue column matches that search.") }
            }
        } else {
            SearchField(state.filterValueSearch, controller::setFilterValueSearch, "Search values\u2026")
            Spacer(Modifier.height(4.dp))
            val values = state.filterValues(field)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                item {
                    Text(
                        "Any value",
                        color = Palette.Muted, fontSize = 10.5.sp,
                        modifier = Modifier.fillMaxWidth()
                            .clickable(role = Role.Button) { controller.removeCatalogueFilter(field.key) }
                            .padding(vertical = 7.dp, horizontal = 4.dp),
                    )
                }
                items(values) { value ->
                    val active = state.catalogueFilters.any { it.key == field.key && it.value == value }
                    Text(
                        value,
                        color = if (active) Palette.Accent else Palette.Text,
                        fontSize = 10.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                            .clickable(role = Role.Button) { controller.setCatalogueFilter(field, value) }
                            .padding(vertical = 7.dp, horizontal = 4.dp),
                    )
                }
                if (values.isEmpty()) item { EmptyNote("No value matches that search.") }
            }
        }
    }
}


@Composable
private fun ImageGrid(state: WorkbookCommentsUi, controller: WorkbookCommentsController, onOpen: (String) -> Unit) {
    LazyVerticalGrid(columns = GridCells.Adaptive(state.thumbCellDp.dp), modifier = Modifier.fillMaxSize()) {
        items(state.visibleImages, key = { it.key }) { row ->
            Column(
                Modifier.padding(4.dp).clickable(role = Role.Button) { controller.toggleImageSelected(row.key) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    MediaThumbnail(row, controller, size = state.thumbBoxDp.dp) { row.mediaPath?.let(onOpen) }
                    if (row.key in state.selectedImages) {
                        Text(
                            "\u2713",
                            color = Palette.OnAccent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(2.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(Palette.Accent)
                                .padding(horizontal = 4.dp),
                        )
                    }
                }
                Text(
                    row.fileName,
                    color = Palette.Text, fontSize = 9.sp, maxLines = 1,
                    modifier = Modifier.padding(top = 3.dp),
                )
                Text(StateLabel.of(row), color = StateLabel.colour(row), fontSize = 8.5.sp, maxLines = 1)
                // Only an image whose association the parser verified offers a destination.
                val location = SourceLocations.ofImage(row, state.sheets)
                if (location.candidates.isNotEmpty()) {
                    Text(
                        "Source \u2197",
                        color = Palette.Accent,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clickable(role = Role.Button) { controller.openSource(row.fileName, location) }
                            .padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ImageList(state: WorkbookCommentsUi, controller: WorkbookCommentsController, onOpen: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(state.visibleImages, key = { it.key }) { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Palette.Navy700)
                    .border(
                        1.dp,
                        if (row.key in state.selectedImages) Palette.Accent else Palette.Line,
                        RoundedCornerShape(9.dp),
                    )
                    .clickable(role = Role.Button) { controller.toggleImageSelected(row.key) }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MediaThumbnail(row, controller, size = 46.dp) { row.mediaPath?.let(onOpen) }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.fileName, color = Palette.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(StateLabel.of(row), color = StateLabel.colour(row), fontSize = 9.sp, maxLines = 1)
                    val hit = row.catalogue.firstOrNull()
                    if (hit != null) {
                        Text(
                            listOfNotNull(hit.title, hit.author, hit.circle).joinToString(" \u00B7 "),
                            color = Palette.Muted, fontSize = 9.sp, maxLines = 1,
                        )
                    }
                    val place = listOfNotNull(row.sheetName, row.cell).joinToString("!")
                    if (place.isNotEmpty()) {
                        Text(place, color = Palette.Muted, fontSize = 8.5.sp, maxLines = 1)
                    }
                    // A verified destination is offered only when the parser proved one.
                    val location = SourceLocations.ofImage(row, state.sheets)
                    if (location.candidates.isNotEmpty()) {
                        Row(Modifier.padding(top = 2.dp)) {
                            SmallAction("Go to source") { controller.openSource(row.fileName, location) }
                        }
                    }
                }
                if (row.key in state.selectedImages) {
                    Text("\u2713", color = Palette.Accent, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

/** The three states, worded the same way everywhere (also used by the manifest). */
private object StateLabel {
    fun of(row: ImageRow): String = when (row.state) {
        ImageState.LINKED -> "Legacy comment image"
        ImageState.UNREFERENCED -> "No association found by this previewer"
        ImageState.UNRESOLVED -> "Unresolved: ${row.unresolvedDetail ?: "reference not resolvable"}"
    }

    fun colour(row: ImageRow) = when (row.state) {
        ImageState.LINKED -> Palette.Ok
        ImageState.UNREFERENCED -> Palette.Muted
        ImageState.UNRESOLVED -> Palette.Warn
    }
}

@Composable
private fun MediaThumbnail(
    row: ImageRow,
    controller: WorkbookCommentsController,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    val bytes = rememberMediaBytes(row.mediaPath, controller)
    val bitmap = remember(bytes) { bytes?.let { decodeThumbnail(it, 400) } }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.Navy800)
            .border(1.dp, Palette.Line, RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClickLabel = "Open image", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap.asImageBitmap(),
                contentDescription = "Thumbnail of ${row.fileName}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(if (row.mediaPath == null) "!" else "?", color = Palette.Muted, fontSize = 14.sp)
        }
    }
}

@Composable
private fun rememberMediaBytes(path: String?, controller: WorkbookCommentsController): ByteArray? {
    var bytes by remember(path, controller) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(path, controller) { bytes = path?.let { controller.mediaBytes(it) } }
    return bytes
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, hint: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(Palette.Navy700)
            .border(1.dp, Palette.Line, RoundedCornerShape(9.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (value.isEmpty()) Text(hint, color = Palette.Muted, fontSize = 10.5.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Palette.Text, fontSize = 11.5.sp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ChoiceChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (active) Palette.OnAccent else Palette.Muted,
        fontSize = 9.5.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) Palette.Accent else Palette.Navy700)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 5.dp),
    )
}

@Composable
private fun SortChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (active) Palette.Accent else Palette.Muted,
        fontSize = 9.5.sp,
        fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Bold,
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 4.dp),
    )
}

@Composable
private fun SmallAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Palette.Accent,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick).padding(vertical = 2.dp),
    )
}

@Composable
private fun EmptyNote(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Palette.Muted, fontSize = 11.5.sp)
    }
}

/** Downsampled decode: a thumbnail never becomes a full-resolution bitmap. */
private fun decodeThumbnail(bytes: ByteArray, maxEdge: Int): android.graphics.Bitmap? = try {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    while (longest > 0 && longest / sample > maxEdge) sample *= 2
    android.graphics.BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size,
        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
    )
} catch (e: OutOfMemoryError) {
    null
} catch (e: Exception) {
    null
}

/**
 * Catalogue search: the workbook's own tables, a free-text search over their columns, and how many
 * records have a verified image. A record without one is still listed and said to have none.
 */
@Composable
fun CatalogueBrowser(state: WorkbookCommentsUi, controller: WorkbookCommentsController, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        SheetSelector(state, controller)
        Spacer(Modifier.height(6.dp))
        val coverage = state.coverage
        if (coverage != null) {
            Text(
                "Records: ${coverage.recordsTotal} \u00B7 with a verified image: ${coverage.recordsWithImage} \u00B7 " +
                    "without: ${coverage.recordsWithoutImage} \u00B7 unresolved references: ${coverage.unresolvedAssociations}",
                color = Palette.Muted, fontSize = 9.5.sp,
            )
        }
        if (state.tables.isEmpty()) {
            EmptyNote(
                "No worksheet here looks like a catalogue table (a header row with at least three columns " +
                    "and rows under it). Nothing is guessed.",
            )
            return@Column
        }
        if (state.scopedTables.isEmpty()) {
            EmptyNote("This worksheet has no catalogue table. Choose another worksheet or All sheets.")
            return@Column
        }
        Spacer(Modifier.height(6.dp))
        SearchField(state.catalogueQuery, controller::setCatalogueQuery, "Search title, author, circle, any column\u2026")
        Spacer(Modifier.height(6.dp))

        val records = state.visibleRecords
        Text(
            "${records.size} record(s) shown" + if (state.selectedSheet == null) " \u00B7 all worksheets" else "",
            color = Palette.Muted, fontSize = 9.5.sp,
        )
        Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(records.size) { index ->
                val row = records[index]
                val table = row.table
                val record = row.record
                val hasImage = row.hasImage
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Palette.Navy700)
                        .border(1.dp, Palette.Line, RoundedCornerShape(9.dp))
                        .padding(8.dp),
                ) {
                    Text("${table.sheetName}!${record.row + 1}", color = Palette.Muted, fontSize = 8.5.sp)
                    table.identifyingFields.forEach { field ->
                        val value = record.value(field)
                        if (value.isNotEmpty()) {
                            Text(
                                if (field == table.titleField) value else "${field.name}: $value",
                                color = if (field == table.titleField) Palette.Text else Palette.Muted,
                                fontSize = if (field == table.titleField) 11.5.sp else 9.5.sp,
                                fontWeight = if (field == table.titleField) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 2,
                            )
                        }
                    }
                    Text(
                        if (hasImage) "Verified image" else "No image linked",
                        color = if (hasImage) Palette.Ok else Palette.Muted,
                        fontSize = 9.sp,
                    )
                    // The record's row is a verified location; the app labels it as a row, not a cell.
                    SmallAction("Go to row") {
                        controller.openSource(
                            "${table.sheetName} row ${record.row + 1}",
                            SourceLocations.ofRecord(table, record, state.sheets),
                        )
                    }
                }
            }
        }
    }
}

/** Searchable worksheet picker: every sheet, in workbook order, hidden ones labelled. */
@Composable
fun SheetPickerDialog(state: WorkbookCommentsUi, controller: WorkbookCommentsController) {
    if (!state.sheetPickerOpen) return
    Dialog(onDismissRequest = controller::closeSheetPicker) {
        Column(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.Navy800)
                .border(1.dp, Palette.Line, RoundedCornerShape(14.dp))
                .padding(14.dp),
        ) {
            Text(
                "Worksheets (${state.sheets.size})",
                color = Palette.Text, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold,
            )
            Spacer(Modifier.height(6.dp))
            SearchField(state.sheetSearch, controller::setSheetSearch, "Search worksheets\u2026")
            Spacer(Modifier.height(6.dp))
            val rows = state.sheetSummaries.filter { it.name.contains(state.sheetSearch.trim(), ignoreCase = true) }
            LazyColumn(Modifier.fillMaxWidth().height(320.dp)) {
                items(rows) { sheet ->
                    Row(
                        Modifier.fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (state.selectedSheet == sheet.name) Palette.Accent.copy(alpha = 0.16f)
                                else Palette.Navy700,
                            )
                            .clickable(role = Role.Button) {
                                controller.selectSheet(sheet.name)
                                controller.closeSheetPicker()
                            }
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(sheet.name, color = Palette.Text, fontSize = 11.5.sp, maxLines = 1)
                            val detail = buildList {
                                add("${sheet.comments} comment(s)")
                                add("${sheet.linkedImages} image(s)")
                                if (sheet.unresolvedImages > 0) add("${sheet.unresolvedImages} unresolved")
                                if (sheet.dimension != null) add(sheet.dimension)
                            }.joinToString(" \u00B7 ")
                            Text(
                                detail,
                                color = if (sheet.unresolvedImages > 0) Palette.Warn else Palette.Muted,
                                fontSize = 8.5.sp,
                            )
                        }
                        if (sheet.hidden) {
                            Text(sheet.visibilityLabel, color = Palette.Warn, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (rows.isEmpty()) {
                    item { Text("No worksheet matches that search.", color = Palette.Muted, fontSize = 10.5.sp) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Hidden worksheets keep the workbook's own visibility; the app never unhides them.",
                color = Palette.Muted, fontSize = 8.5.sp,
            )
            Spacer(Modifier.height(6.dp))
            SmallAction("Close") { controller.closeSheetPicker() }
        }
    }
}
