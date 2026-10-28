package com.bookcovermatcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookcovermatcher.BatchKind
import com.bookcovermatcher.FolderDetailUi
import com.bookcovermatcher.FolderUi
import com.bookcovermatcher.MainViewModel
import com.bookcovermatcher.SavedItemUi
import com.bookcovermatcher.SelectionUi
import com.bookcovermatcher.SessionDetailUi
import com.bookcovermatcher.SessionUi
import com.bookcovermatcher.UiState
import com.bookcovermatcher.WishlistSection
import com.bookcovermatcher.ui.components.ButtonLabel
import com.bookcovermatcher.ui.components.FileImage
import com.bookcovermatcher.ui.components.GradientButton
import com.bookcovermatcher.ui.components.SegmentedTab
import com.bookcovermatcher.ui.components.SegmentedTabRow
import com.bookcovermatcher.ui.theme.Palette

/**
 * The Wishlist destination: folders → sessions → results, plus the reserved Bookmarks view.
 *
 * The screen never invents a folder and never lets a session's results be edited one by one:
 * offline comparison rows are read-only, and a whole session is removed as one record.
 */
@Composable
fun WishlistScreen(ui: UiState, vm: MainViewModel, onOpenUrl: (String) -> Unit) {
    val session = ui.sessionDetail
    val folder = ui.folderDetail
    BackHandler(enabled = session != null || folder != null || ui.selection != null) {
        when {
            session != null -> vm.closeSession()
            folder != null -> vm.closeFolder()
            else -> vm.exitSelection()
        }
    }

    val selection = ui.selection
    Column(Modifier.fillMaxSize()) {
        if (selection != null) {
            SelectionToolbar(
                selection = selection,
                onSelectAll = vm::selectAllVisible,
                onClear = vm::clearSelection,
                onDone = vm::exitSelection,
            )
        }
        Column(Modifier.weight(1f)) {
            when {
                session != null -> SessionDetailScreen(session, vm, onOpenUrl, selection)
                folder != null -> FolderDetailScreen(folder, vm, onOpenUrl, selection)
                else -> {
                    Header()
                    Spacer(Modifier.height(8.dp))
                    Sections(ui, vm::selectWishlistSection)
                    Spacer(Modifier.height(8.dp))
                    when (ui.wishlistSection) {
                        WishlistSection.FOLDERS -> FolderList(ui, vm, selection)
                        WishlistSection.BOOKMARKS -> BookmarkList(ui, vm, onOpenUrl, selection)
                    }
                }
            }
        }
        if (selection != null) {
            BatchActionBar(
                kind = selection.kind,
                count = selection.count,
                onCopy = vm::requestBatchCopy,
                onMove = vm::requestBatchMove,
                onDelete = vm::requestBatchDelete,
                onDone = vm::exitSelection,
            )
        }
    }
}

@Composable
private fun Header() {
    Column(Modifier.fillMaxWidth()) {
        Text("Wishlist", color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Text(
            "Your folders, each holding the sessions you saved. Bookmarks live in their own folder.",
            color = Palette.Muted,
            fontSize = 10.5.sp,
        )
    }
}

@Composable
private fun Sections(ui: UiState, onSelect: (WishlistSection) -> Unit) {
    val selected = ui.wishlistSection
    SegmentedTabRow {
        SegmentedTab("Folders (${ui.folders.size})", selected == WishlistSection.FOLDERS, Modifier.weight(1f)) {
            onSelect(WishlistSection.FOLDERS)
        }
        SegmentedTab("Bookmarks (${ui.bookmarks.size})", selected == WishlistSection.BOOKMARKS, Modifier.weight(1f)) {
            onSelect(WishlistSection.BOOKMARKS)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Folders
// ---------------------------------------------------------------------------------------------

@Composable
private fun FolderList(ui: UiState, vm: MainViewModel, selection: SelectionUi?) {
    Column(Modifier.fillMaxSize()) {
        GradientButton(
            onClick = vm::createFolder,
            colors = Palette.NavyGradient,
            contentColor = Palette.Text,
            modifier = Modifier.fillMaxWidth(),
            minHeight = 44.dp,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = Palette.Accent, modifier = Modifier.size(18.dp))
            ButtonLabel("Create folder", Palette.Text, 13f)
        }
        Text(
            "A folder holds sessions. A scan or a search never creates one by itself.",
            color = Palette.Muted,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (ui.folders.isNotEmpty() && selection?.kind != BatchKind.FOLDERS) {
            SelectEntry("Select folders") { vm.startSelection(BatchKind.FOLDERS) }
        }
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.folders.isEmpty()) {
                item {
                    Text(
                        "No folders yet. Create one, then save a scan or search session into it.",
                        color = Palette.Muted,
                        fontSize = 11.5.sp,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            items(ui.folders, key = { it.id }) { folder ->
                FolderCard(folder, vm, selection)
            }
            if (ui.unfiledSessions.isNotEmpty()) {
                item {
                    Text(
                        "Unfiled sessions",
                        color = Palette.Muted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                items(ui.unfiledSessions, key = { "unfiled_${it.id}" }) { session ->
                    SessionCard(session, onOpen = { vm.openSession(session.id) }, onDelete = null)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderCard(folder: FolderUi, vm: MainViewModel, selection: SelectionUi?) {
    val shape = RoundedCornerShape(14.dp)
    val selecting = selection?.kind == BatchKind.FOLDERS
    val checked = selecting && folder.id in (selection?.ids ?: emptySet())
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.02f))),
                shape,
            )
            .then(
                if (checked) Modifier.background(Palette.Accent.copy(alpha = 0.12f), shape) else Modifier,
            )
            .border(1.dp, if (checked) Palette.Accent.copy(alpha = 0.8f) else Palette.Line, shape)
            .combinedClickable(
                role = Role.Button,
                onClick = { if (selecting) vm.toggleSelected(folder.id) else vm.openFolder(folder.id) },
                onLongClick = { vm.startSelectionWith(BatchKind.FOLDERS, folder.id) },
            )
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) BatchCheck(checked, "Select ${folder.name}")
        FileImage(
            folder.cover,
            Modifier.size(46.dp, 62.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, Palette.Line, RoundedCornerShape(8.dp)),
            folder.name,
        )
        Column(Modifier.weight(1f)) {
            Text(
                folder.name,
                color = Palette.Text,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${folder.sessionCount} session(s) \u00B7 ${folder.itemCount} result(s)",
                color = Palette.Muted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        SquareIconButton(Icons.Filled.Edit, "Rename folder", Palette.Text) { vm.requestRenameFolder(folder.id) }
        SquareIconButton(
            Icons.Filled.Delete,
            "Delete folder",
            Palette.Danger,
            borderColor = Palette.Danger.copy(alpha = 0.45f),
        ) { vm.requestDeleteFolder(folder.id) }
    }
}


// ---------------------------------------------------------------------------------------------
// One folder: sessions + directly saved items
// ---------------------------------------------------------------------------------------------

@Composable
private fun FolderDetailScreen(
    detail: FolderDetailUi,
    vm: MainViewModel,
    onOpenUrl: (String) -> Unit,
    selection: SelectionUi?,
) {
    val sessionsSelecting = selection?.kind == BatchKind.SESSIONS
    val itemsSelecting = selection?.kind == BatchKind.ITEMS
    Column(Modifier.fillMaxSize()) {
        DetailHeader(
            backLabel = "\u2039 All folders",
            title = detail.name,
            countText = "${detail.sessions.size} session(s) \u00B7 ${detail.directItems.size} saved item(s)",
            onBack = vm::closeFolder,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (detail.sessions.isNotEmpty() && selection?.kind != BatchKind.SESSIONS) {
                SelectEntry("Select sessions") { vm.startSelection(BatchKind.SESSIONS, detail.id) }
            }
            if (detail.directItems.isNotEmpty() && selection?.kind != BatchKind.ITEMS) {
                SelectEntry("Select items") { vm.startSelection(BatchKind.ITEMS, detail.id) }
            }
        }
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("SESSIONS", color = Palette.Muted, fontSize = 9.5.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp)
            }
            if (detail.sessions.isEmpty()) {
                item {
                    Text(
                        "No sessions in this folder yet. Save a scan from Search, or add the whole result profile.",
                        color = Palette.Muted,
                        fontSize = 11.5.sp,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
            items(detail.sessions, key = { it.id }) { session ->
                SessionCard(
                    session = session,
                    onOpen = { vm.openSession(session.id) },
                    onDelete = { vm.requestDeleteSession(session.id) },
                    selected = if (sessionsSelecting) session.id in (selection?.ids ?: emptySet()) else null,
                    onToggle = { vm.toggleSelected(session.id) },
                    onLongPress = { vm.startSelectionWith(BatchKind.SESSIONS, session.id, detail.id) },
                    onRename = { vm.requestRenameSession(session.id) },
                )
            }
            if (detail.directItems.isNotEmpty()) {
                item {
                    Text(
                        "SAVED ITEMS",
                        color = Palette.Muted,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.8.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(detail.directItems, key = { it.id }) { item ->
                    SavedItemRow(
                        item = item,
                        onOpen = { item.url?.let(onOpenUrl) },
                        onWishlist = { vm.addSavedItemToWishlist(item.id) },
                        onRemove = { vm.requestRemoveFromFolder(item.id, detail.id) },
                        removeDescription = "Remove from this folder",
                        allowWishlist = true,
                        selected = if (itemsSelecting) item.id in (selection?.ids ?: emptySet()) else null,
                        onToggleSelect = { vm.toggleSelected(item.id) },
                        onLongPress = { vm.startSelectionWith(BatchKind.ITEMS, item.id, detail.id) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(
    session: SessionUi,
    onOpen: () -> Unit,
    onDelete: (() -> Unit)?,
    selected: Boolean? = null,
    onToggle: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(14.dp)
    val selecting = selected != null
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.02f))), shape)
            .then(if (selected == true) Modifier.background(Palette.Accent.copy(alpha = 0.12f), shape) else Modifier)
            .border(1.dp, if (selected == true) Palette.Accent.copy(alpha = 0.8f) else Palette.Line, shape)
            .combinedClickable(
                role = Role.Button,
                onClick = { if (selecting) onToggle?.invoke() else onOpen() },
                onLongClick = { onLongPress?.invoke() },
            )
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) BatchCheck(selected == true, "Select ${session.title}")
        FileImage(
            session.scanThumb,
            Modifier.size(44.dp, 58.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, Palette.Line, RoundedCornerShape(8.dp)),
            session.title,
        )
        Column(Modifier.weight(1f)) {
            Text(
                session.title,
                color = Palette.Text,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Chip(session.kindLabel, Palette.Accent2)
                if (session.bookmarkedCount > 0) Chip("${session.bookmarkedCount} bookmarked", Palette.Ok)
            }
            Text(session.meta, color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
        }
        if (onRename != null && selected == null) {
            SquareIconButton(Icons.Filled.Edit, "Rename result profile", Palette.Text, onClick = onRename)
        }
        if (onDelete != null && selected == null) {
            SquareIconButton(
                Icons.Filled.Delete,
                "Delete session",
                Palette.Danger,
                borderColor = Palette.Danger.copy(alpha = 0.45f),
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun DetailHeader(backLabel: String, title: String, countText: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            backLabel,
            color = Palette.Accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClick = onBack)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(title, color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        Text(countText, color = Palette.Muted, fontSize = 10.5.sp)
    }
    Spacer(Modifier.height(8.dp))
}


// ---------------------------------------------------------------------------------------------
// One session: comparison output or a saved result set
// ---------------------------------------------------------------------------------------------

@Composable
private fun SessionDetailScreen(
    detail: SessionDetailUi,
    vm: MainViewModel,
    onOpenUrl: (String) -> Unit,
    selection: SelectionUi?,
) {
    val selecting = selection?.kind == BatchKind.SESSION_ITEMS
    val selectable = detail.items.count { !it.comparisonOnly }
    Column(Modifier.fillMaxSize()) {
        DetailHeader(
            backLabel = "\u2039 Back",
            title = detail.title,
            countText = "${detail.items.size} result(s)",
            onBack = vm::closeSession,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(detail.kindLabel, Palette.Accent2)
            detail.query?.let { Chip("query: $it", Palette.Muted) }
        }
        Text(detail.meta, color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
        if (detail.comparisonOnly) {
            Text(
                "Workbook comparison results. They stay part of this session and have no bookmark or remove action.",
                color = Palette.Muted,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (selectable > 0 && !selecting) {
            SelectEntry("Select results") { vm.startSelection(BatchKind.SESSION_ITEMS, detail.id) }
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(detail.items, key = { it.id }) { item ->
                if (item.comparisonOnly) {
                    ComparisonRow(item)
                } else {
                    SavedItemRow(
                        item = item,
                        onOpen = { item.url?.let(onOpenUrl) },
                        onWishlist = { vm.addSavedItemToWishlist(item.id) },
                        onRemove = null,
                        removeDescription = "",
                        allowWishlist = true,
                        selected = if (selecting) item.id in (selection?.ids ?: emptySet()) else null,
                        onToggleSelect = { vm.toggleSelected(item.id) },
                        onLongPress = { vm.startSelectionWith(BatchKind.SESSION_ITEMS, item.id, detail.id) },
                        onBookmark = { vm.toggleSavedItemBookmark(item.id) },
                    )
                }
            }
        }
    }
}

/** One workbook match row: cell, cosine/percent, source and its thumbnail. Read-only by design. */
@Composable
private fun ComparisonRow(item: SavedItemUi) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.02f))), shape)
            .border(1.dp, Palette.Line, shape)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileImage(
            item.thumb,
            Modifier.size(44.dp, 58.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, Palette.Line, RoundedCornerShape(8.dp)),
            item.title,
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Cell ", color = Palette.Text, fontSize = 12.sp)
                Text(
                    item.cell ?: item.title,
                    color = Palette.Accent,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (item.meta.isNotEmpty()) {
                Text(item.meta, color = Palette.Muted, fontSize = 10.5.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        item.percentText?.let {
            Text(it, color = Palette.Ok, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}


// ---------------------------------------------------------------------------------------------
// Bookmarks
// ---------------------------------------------------------------------------------------------

/** How the Bookmarks list is ordered. [ADDED] keeps the store's own order (oldest first). */
private enum class BookmarkSort(val label: String) {
    ADDED("Added"),
    TITLE("Title"),
    DOMAIN("Domain"),
}

@Composable
private fun BookmarkList(ui: UiState, vm: MainViewModel, onOpenUrl: (String) -> Unit, selection: SelectionUi?) {
    var filter by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(BookmarkSort.ADDED) }
    val selecting = selection?.kind == BatchKind.BOOKMARKS
    val needle = filter.trim()
    val shown = ui.bookmarks
        .filter { item ->
            needle.isEmpty() || item.title.contains(needle, ignoreCase = true) ||
                item.domain?.contains(needle, ignoreCase = true) == true
        }
        .let { list ->
            when (sort) {
                BookmarkSort.ADDED -> list
                BookmarkSort.TITLE -> list.sortedBy { it.title.lowercase() }
                BookmarkSort.DOMAIN -> list.sortedWith(
                    compareBy({ it.domain.orEmpty().lowercase() }, { it.title.lowercase() }),
                )
            }
        }

    Column(Modifier.fillMaxSize()) {
        GradientButton(
            onClick = vm::openCustomBookmark,
            colors = Palette.NavyGradient,
            contentColor = Palette.Text,
            modifier = Modifier.fillMaxWidth(),
            minHeight = 44.dp,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = Palette.Accent, modifier = Modifier.size(18.dp))
            ButtonLabel("Create bookmark", Palette.Text, 13f)
        }
        Text(
            "Bookmarks are saved links, independent of scans, sessions and folders. They survive restarts.",
            color = Palette.Muted,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
        )
        BookmarkFilter(filter) { filter = it }
        if (!selecting && ui.bookmarks.isNotEmpty()) {
            // A modest gap keeps the filter and the selection entry visually separate.
            Spacer(Modifier.height(8.dp))
            SelectEntry("Select bookmarks") { vm.startSelection(BatchKind.BOOKMARKS) }
            Spacer(Modifier.height(6.dp))
        }
        Row(
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Sort", color = Palette.Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            BookmarkSort.values().forEach { option ->
                SortChip(option.label, sort == option) { sort = option }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (shown.isEmpty()) {
                item {
                    Text(
                        if (ui.bookmarks.isEmpty()) {
                            "No bookmarks yet. Tap Bookmark on an online result, or create one by hand."
                        } else {
                            "No bookmark matches \u201C$needle\u201D."
                        },
                        color = Palette.Muted,
                        fontSize = 11.5.sp,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            items(shown, key = { it.id }) { item ->
                SavedItemRow(
                    item = item,
                    onOpen = { item.url?.let(onOpenUrl) },
                    onWishlist = { vm.addSavedItemToWishlist(item.id) },
                    onRemove = { vm.requestDeleteBookmark(item.id) },
                    removeDescription = "Remove bookmark",
                    allowWishlist = true,
                    selected = if (selecting) item.id in (selection?.ids ?: emptySet()) else null,
                    onToggleSelect = { vm.toggleSelected(item.id) },
                    onLongPress = { vm.startSelectionWith(BatchKind.BOOKMARKS, item.id) },
                    // Inside the card, immediately left of the red delete icon.
                    onEdit = { vm.openEditBookmark(item.id) },
                )
            }
        }
    }
}

/** A text filter over saved titles and source domains; local state, never written to the store. */
@Composable
private fun BookmarkFilter(value: String, onChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = Palette.Text, fontSize = 12.5.sp),
        cursorBrush = SolidColor(Palette.Accent),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.22f))
            .border(1.dp, Palette.Line, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text("Filter by title or source domain", color = Palette.Muted, fontSize = 12.sp)
                }
                inner()
            }
        },
    )
}

@Composable
private fun SortChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) Palette.Accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.04f))
            .border(1.dp, if (active) Palette.Accent.copy(alpha = 0.5f) else Palette.Line, RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            label,
            color = if (active) Palette.Accent else Palette.Muted,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}


// ---------------------------------------------------------------------------------------------
// One saved non-comparison item
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun SavedItemRow(
    item: SavedItemUi,
    onOpen: () -> Unit,
    onWishlist: () -> Unit,
    onRemove: (() -> Unit)?,
    removeDescription: String,
    allowWishlist: Boolean,
    selected: Boolean? = null,
    onToggleSelect: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onBookmark: (() -> Unit)? = null,
    /** Edit action for the bookmark card; drawn immediately left of the red delete icon. */
    onEdit: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(14.dp)
    val selecting = selected != null
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.02f))), shape)
            .then(if (selected == true) Modifier.background(Palette.Accent.copy(alpha = 0.12f), shape) else Modifier)
            .border(1.dp, if (selected == true) Palette.Accent.copy(alpha = 0.8f) else Palette.Line, shape)
            .combinedClickable(
                enabled = selecting || onLongPress != null,
                role = Role.Button,
                onClick = { if (selecting) onToggleSelect?.invoke() },
                onLongClick = { onLongPress?.invoke() },
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            if (selecting) BatchCheck(selected == true, "Select ${item.title}")
            FileImage(
                item.thumb,
                Modifier.size(44.dp, 60.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, Palette.Line, RoundedCornerShape(8.dp)),
                item.title,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    color = Palette.Text,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.meta.isNotEmpty()) {
                    Text(
                        item.meta,
                        modifier = Modifier.padding(top = 2.dp),
                        color = Palette.Muted,
                        fontSize = 10.5.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(item.addedText, color = Palette.Muted, fontSize = 9.5.sp, modifier = Modifier.padding(top = 3.dp))
            }
            if ((onEdit != null || onRemove != null) && !selecting) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onEdit != null) {
                        // Same white pen as the folder card's edit action: one visual convention.
                        SquareIconButton(Icons.Filled.Edit, "Edit bookmark", Palette.Text, onClick = onEdit)
                    }
                    if (onRemove != null) {
                        SquareIconButton(
                            Icons.Filled.Delete,
                            removeDescription,
                            Palette.Danger,
                            borderColor = Palette.Danger.copy(alpha = 0.45f),
                            onClick = onRemove,
                        )
                    }
                }
            }
        }
        // Tags get the whole card width and wrap onto as many lines as they need, so they can never
        // run under the edit/delete buttons; the card's measured height grows with them.
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Chip(item.kindLabel, Palette.Accent2)
            item.domain?.let { Chip(it, Palette.Muted) }
            if (item.bookmarked) Chip("Bookmarked", Palette.Ok)
        }
        if (!selecting) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (onBookmark != null) {
                    // Same label and state colour as the live online-result card: one convention.
                    RowAction(
                        if (item.bookmarked) "\u2605 Bookmarked" else "\u2605 Bookmark",
                        if (item.bookmarked) Palette.Ok else Palette.Accent2,
                        Modifier.weight(1f),
                        onBookmark,
                    )
                }
                if (item.url != null) {
                    RowAction("Open website", Palette.Accent, Modifier.weight(1f), onOpen)
                }
                if (allowWishlist) {
                    RowAction("+ Add to wishlist", Palette.Accent2, Modifier.weight(1f), onWishlist)
                }
            }
        }
    }
}

@Composable
private fun RowAction(label: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(9.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun SquareIconButton(
    icon: ImageVector,
    description: String,
    tint: Color,
    borderColor: Color = Palette.Line,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(9.dp))
            .border(1.dp, borderColor, RoundedCornerShape(9.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
    }
}

/** `Select ...` is drawn by [SelectEntry] in `BatchSelection.kt`, next to each list header. */
