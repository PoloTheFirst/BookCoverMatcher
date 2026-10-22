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
import com.bookcovermatcher.FolderDetailUi
import com.bookcovermatcher.FolderUi
import com.bookcovermatcher.MainViewModel
import com.bookcovermatcher.SavedItemUi
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
    BackHandler(enabled = session != null || folder != null) {
        if (session != null) vm.closeSession() else vm.closeFolder()
    }

    Column(Modifier.fillMaxSize()) {
        when {
            session != null -> SessionDetailScreen(session, vm, onOpenUrl)
            folder != null -> FolderDetailScreen(folder, vm, onOpenUrl)
            else -> {
                Header()
                Spacer(Modifier.height(8.dp))
                Sections(ui.wishlistSection, vm::selectWishlistSection)
                Spacer(Modifier.height(8.dp))
                when (ui.wishlistSection) {
                    WishlistSection.FOLDERS -> FolderList(ui, vm)
                    WishlistSection.BOOKMARKS -> BookmarkList(ui, vm, onOpenUrl)
                }
            }
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
private fun Sections(selected: WishlistSection, onSelect: (WishlistSection) -> Unit) {
    SegmentedTabRow {
        SegmentedTab("Folders", selected == WishlistSection.FOLDERS, Modifier.weight(1f)) {
            onSelect(WishlistSection.FOLDERS)
        }
        SegmentedTab("Bookmarks", selected == WishlistSection.BOOKMARKS, Modifier.weight(1f)) {
            onSelect(WishlistSection.BOOKMARKS)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Folders
// ---------------------------------------------------------------------------------------------

@Composable
private fun FolderList(ui: UiState, vm: MainViewModel) {
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
            modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
        )
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
                FolderCard(folder, vm)
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

@Composable
private fun FolderCard(folder: FolderUi, vm: MainViewModel) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.02f))), shape)
            .border(1.dp, Palette.Line, shape)
            .clickable(role = Role.Button) { vm.openFolder(folder.id) }
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
private fun FolderDetailScreen(detail: FolderDetailUi, vm: MainViewModel, onOpenUrl: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        DetailHeader(
            backLabel = "\u2039 All folders",
            title = detail.name,
            countText = "${detail.sessions.size} session(s) \u00B7 ${detail.directItems.size} saved item(s)",
            onBack = vm::closeFolder,
        )
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
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionCard(session: SessionUi, onOpen: () -> Unit, onDelete: (() -> Unit)?) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.02f))), shape)
            .border(1.dp, Palette.Line, shape)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        if (onDelete != null) {
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
private fun SessionDetailScreen(detail: SessionDetailUi, vm: MainViewModel, onOpenUrl: (String) -> Unit) {
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
private fun BookmarkList(ui: UiState, vm: MainViewModel, onOpenUrl: (String) -> Unit) {
    var filter by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(BookmarkSort.ADDED) }
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

@Composable
private fun SavedItemRow(
    item: SavedItemUi,
    onOpen: () -> Unit,
    onWishlist: () -> Unit,
    onRemove: (() -> Unit)?,
    removeDescription: String,
    allowWishlist: Boolean,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.02f))), shape)
            .border(1.dp, Palette.Line, shape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
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
                Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Chip(item.kindLabel, Palette.Accent2)
                    item.domain?.let { Chip(it, Palette.Muted) }
                    if (item.bookmarked) Chip("Bookmarked", Palette.Ok)
                }
                Text(item.addedText, color = Palette.Muted, fontSize = 9.5.sp, modifier = Modifier.padding(top = 3.dp))
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
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (item.url != null) {
                RowAction("Open website", Palette.Accent, Modifier.weight(1f), onOpen)
            }
            if (allowWishlist) {
                RowAction("+ Add to wishlist", Palette.Accent2, Modifier.weight(1f), onWishlist)
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

