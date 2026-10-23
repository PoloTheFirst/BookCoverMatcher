package com.bookcovermatcher.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleOwner
import com.bookcovermatcher.AppTab
import com.bookcovermatcher.BusyUi
import com.bookcovermatcher.CatalogueUi
import com.bookcovermatcher.MainViewModel
import com.bookcovermatcher.ProductResultUi
import com.bookcovermatcher.ResultUi
import com.bookcovermatcher.ResultsUi
import com.bookcovermatcher.SearchResultsTab
import com.bookcovermatcher.TargetUi
import com.bookcovermatcher.UiState
import com.bookcovermatcher.WishlistSection
import com.bookcovermatcher.core.format.Formatting
import com.bookcovermatcher.core.search.MatchLevel
import com.bookcovermatcher.ui.components.ButtonLabel
import com.bookcovermatcher.ui.components.CameraGlyph
import com.bookcovermatcher.ui.components.FieldLabel
import com.bookcovermatcher.ui.components.GradientButton
import com.bookcovermatcher.ui.components.JpegImage
import com.bookcovermatcher.ui.components.Panel
import com.bookcovermatcher.ui.components.SegmentedTab
import com.bookcovermatcher.ui.components.SegmentedTabRow
import com.bookcovermatcher.ui.components.TargetGlyph
import com.bookcovermatcher.ui.components.dashedBorder
import com.bookcovermatcher.ui.theme.Palette

/** What the buttons do that needs Android (permission prompts, document pickers, browser links). */
class ScreenActions(
    val onScan: () -> Unit,
    val onToggleAuto: () -> Unit,
    val onRestart: () -> Unit,
    val onPickWorkbook: () -> Unit,
    val onPickImage: () -> Unit,
    val onOpenUrl: (String) -> Unit,
)

/** Home: the existing capture + workbook workflow. Results are presented on the Search tab. */
@Composable
fun HomeScreen(ui: UiState, vm: MainViewModel, lifecycleOwner: LifecycleOwner, actions: ScreenActions) {
    Column(Modifier.fillMaxWidth()) {
        AppHeader()
        Spacer(Modifier.height(14.dp))

        CameraCard(
            state = ui,
            camera = vm.camera,
            lifecycleOwner = lifecycleOwner,
            captureEvents = vm.captureEvents,
            onLive = vm::onCameraLive,
            onError = vm::onCameraError,
        )

        ActionRow(ui, actions)

        Spacer(Modifier.height(14.dp))
        SpreadsheetPanel(ui, vm, actions)

        Spacer(Modifier.height(14.dp))
        Panel("Results") {
            val ranked = ui.offlineMatches
            Text(
                when {
                    ranked.isNotEmpty() -> "${ranked.size} workbook match(es) are ready on the Search tab."
                    ui.results === ResultsUi.Empty -> "No match yet. Run \u201CLink & Find Top 5\u201D, then open Search."
                    else -> "Scan a cover and run \u201CLink & Find Top 5\u201D; the comparison appears on Search."
                },
                color = Palette.Muted,
                fontSize = 11.sp,
            )
            GradientButton(
                onClick = { vm.selectTab(com.bookcovermatcher.AppTab.SEARCH) },
                colors = Palette.NavyGradient,
                contentColor = Palette.Text,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                minHeight = 42.dp,
            ) { ButtonLabel("Open Search", Palette.Text, 12.5f) }
        }
    }
}

/**
 * Search destination — the "Search Results" screen of the workflow image.
 *
 * The picture puts `Workbook (n) / Online (n)` tabs above one result area and adds a `Bookmarks` tab
 * on the bookmark step. Implemented as three tabs whose labels carry live counts:
 *
 *  * **Workbook** — the offline comparison: spreadsheet controls, the top-5 rows, "Save this scan as
 *    a session". Rows stay comparison-only; they have no bookmark or remove controls.
 *  * **Online** — the whole online panel: OCR, the editable query, catalogue results and their
 *    per-card actions.
 *  * **Bookmarks** — the results of this search that are in the Bookmarks collection, plus
 *    `Bookmark all results`. Bookmarks are their own collection, so this tab never writes to a folder
 *    and never creates one.
 *
 * A mixed list is deliberately avoided: a workbook row is comparison output, an online record is a
 * catalogue entry, and they must not look alike. Tabs are navigation state only — switching never
 * cancels a request, clears the session cache or touches saved data.
 */
@Composable
fun SearchScreen(ui: UiState, vm: MainViewModel, actions: ScreenActions) {
    val found = (ui.online.catalogue as? CatalogueUi.Found)?.items.orEmpty()
    val bookmarkedHere = found.filter { it.key in ui.bookmarkedKeys }

    Column(Modifier.fillMaxWidth()) {
        Text("Search Results", color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Text(
            "Workbook matches come from the spreadsheet comparison; online results come from catalogues searched with the cover text.",
            color = Palette.Muted,
            fontSize = 10.5.sp,
        )
        Spacer(Modifier.height(10.dp))

        SegmentedTabRow {
            SegmentedTab(
                "Workbook (${ui.offlineMatches.size})",
                ui.searchTab == SearchResultsTab.WORKBOOK,
                Modifier.weight(1f),
            ) { vm.selectSearchTab(SearchResultsTab.WORKBOOK) }
            SegmentedTab(
                "Online (${found.size})",
                ui.searchTab == SearchResultsTab.ONLINE,
                Modifier.weight(1f),
            ) { vm.selectSearchTab(SearchResultsTab.ONLINE) }
            SegmentedTab(
                "Bookmarks (${bookmarkedHere.size})",
                ui.searchTab == SearchResultsTab.BOOKMARKS,
                Modifier.weight(1f),
            ) { vm.selectSearchTab(SearchResultsTab.BOOKMARKS) }
        }
        Spacer(Modifier.height(12.dp))

        when (ui.searchTab) {
            SearchResultsTab.WORKBOOK -> WorkbookResultsTab(ui, vm, actions, hasOnline = found.isNotEmpty())
            SearchResultsTab.ONLINE -> OnlineResultsTab(ui, vm, actions)
            SearchResultsTab.BOOKMARKS -> BookmarksResultsTab(ui, vm, actions, found, bookmarkedHere)
        }
    }
}

/** Offline half of Search: the existing workbook controls, the comparison, and the two explicit saves. */
@Composable
private fun WorkbookResultsTab(ui: UiState, vm: MainViewModel, actions: ScreenActions, hasOnline: Boolean) {
    SpreadsheetPanel(ui, vm, actions)

    Spacer(Modifier.height(14.dp))
    Text(
        when {
            ui.offlineMatches.isNotEmpty() -> "Found ${ui.offlineMatches.size} workbook match(es)"
            ui.results === ResultsUi.Empty -> "No workbook match for this cover"
            else -> "No comparison yet"
        },
        color = Palette.Text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    ResultsPanel(ui.results)

    if (ui.offlineMatches.isNotEmpty()) {
        GradientButton(
            onClick = vm::saveOfflineSession,
            colors = Palette.YellowGradient,
            contentColor = Color(0xFF3A2A00),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            glow = Color(0xFFF0B400).copy(alpha = 0.5f),
        ) {
            Icon(Icons.Filled.Star, contentDescription = null, tint = Color(0xFF3A2A00), modifier = Modifier.size(20.dp))
            ButtonLabel("Save this scan as a session", Color(0xFF3A2A00))
        }
        Text(
            "Saves one session (the five comparison results) into a folder you choose. It never creates a folder by itself.",
            modifier = Modifier.padding(top = 6.dp),
            color = Palette.Muted,
            fontSize = 10.sp,
        )
    }

    // Bulk add and per-item add stay different actions: this one keeps the comparison rows inside one
    // session, while a card's `Add to wishlist` adds exactly that one item.
    if (ui.offlineMatches.isNotEmpty() || hasOnline) {
        Spacer(Modifier.height(12.dp))
        GradientButton(
            onClick = vm::addResultProfileToWishlist,
            colors = Palette.GreenGradient,
            contentColor = Color.White,
            modifier = Modifier.fillMaxWidth(),
            minHeight = 46.dp,
        ) { ButtonLabel("Add entire result profile to wishlist", Color.White, 13.5f) }
        Text(
            "One session in the folder you choose: the workbook top-5 plus every online result currently found.",
            modifier = Modifier.padding(top = 6.dp),
            color = Palette.Muted,
            fontSize = 10.sp,
        )
    }
}

/** Online half of Search: the existing online panel, now the only content of its own tab. */
@Composable
private fun OnlineResultsTab(ui: UiState, vm: MainViewModel, actions: ScreenActions) {
    OnlineSearchPanel(
        ui = ui.online,
        controller = vm.online,
        onOpenUrl = actions.onOpenUrl,
        bookmarkedKeys = ui.bookmarkedKeys,
        wishlistedKeys = ui.wishlistedKeys,
        onToggleBookmark = vm::toggleBookmark,
        onAddToWishlist = vm::addResultToWishlist,
        onBulkAdd = vm::addResultProfileToWishlist,
        onBookmarkAll = vm::bookmarkAllResults,
    )
}

/** The workflow image's fourth step: the `Bookmarks` tab of the results screen. */
@Composable
private fun BookmarksResultsTab(
    ui: UiState,
    vm: MainViewModel,
    actions: ScreenActions,
    found: List<ProductResultUi>,
    bookmarked: List<ProductResultUi>,
) {
    Panel("Bookmarks") {
        Text(
            "Results are saved to the Bookmarks collection (also linked to your wishlist profile if selected).",
            color = Palette.Muted,
            fontSize = 10.5.sp,
        )
        GradientButton(
            onClick = vm::bookmarkAllResults,
            colors = Palette.NavyGradient,
            contentColor = Palette.Text,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            minHeight = 44.dp,
        ) { ButtonLabel("Bookmark all results", Palette.Text, 13f) }
        Text(
            if (found.isEmpty()) {
                "Nothing to bookmark yet \u2014 run a catalogue search on the Online tab."
            } else {
                "Saves all ${found.size} online result(s) of this search to Bookmarks. No folder is created."
            },
            modifier = Modifier.padding(top = 6.dp),
            color = Palette.Muted,
            fontSize = 10.sp,
        )
        Text(
            "The collection holds ${ui.bookmarks.size} bookmark(s); the list below is only this search.",
            modifier = Modifier.padding(top = 6.dp),
            color = Palette.Accent,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }

    Spacer(Modifier.height(8.dp))
    if (bookmarked.isEmpty()) {
        EmptyNote(
            if (found.isEmpty()) {
                "No online results in this search yet."
            } else {
                "None of these ${found.size} result(s) is bookmarked yet. Use the bookmark action on a card, or Bookmark all results."
            },
        )
    } else {
        bookmarked.forEachIndexed { index, item ->
            ProductResultCard(
                item = item.copy(bookmarked = true, wishlisted = item.key in ui.wishlistedKeys),
                index = index,
                onOpen = actions.onOpenUrl,
                onToggleBookmark = vm::toggleBookmark,
                onAddToWishlist = vm::addResultToWishlist,
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    // The collection outlives this search, so the full list lives on the Wishlist tab.
    GradientButton(
        onClick = {
            vm.selectWishlistSection(WishlistSection.BOOKMARKS)
            vm.selectTab(AppTab.WISHLIST)
        },
        colors = Palette.NavyGradient,
        contentColor = Palette.Text,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        minHeight = 44.dp,
    ) { ButtonLabel("Open the full Bookmarks collection", Palette.Text, 13f) }
}


// ---------------------------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------------------------

@Composable
private fun AppHeader() {
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                buildAnnotatedString {
                    append("Book")
                    withStyle(SpanStyle(color = Palette.Accent)) { append("Cover") }
                    append(" Matcher")
                },
                color = Palette.Text,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.3.sp,
            )
            Text(
                "Scan a cover \u00B7 embed it \u00B7 find it inside your spreadsheet",
                modifier = Modifier.padding(top = 4.dp),
                color = Palette.Muted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                letterSpacing = 0.2.sp,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Buttons under the camera
// ---------------------------------------------------------------------------------------------

@Composable
private fun ActionRow(ui: UiState, actions: ScreenActions) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GradientButton(
            onClick = actions.onScan,
            colors = Palette.PrimaryGradient,
            contentColor = Palette.OnAccent,
            modifier = Modifier.weight(1f),
            enabled = !ui.isBusy,
            glow = Palette.Accent.copy(alpha = 0.6f),
        ) {
            CameraGlyph(Palette.OnAccent)
            ButtonLabel("Scan Book Cover", Palette.OnAccent)
        }

        val active = ui.autoScan
        val description = if (active) "Auto-scan: ON (tap to switch to Manual)" else "Auto-scan: OFF (tap to switch to Auto)"
        Box(contentAlignment = Alignment.Center) {
            GradientButton(
                onClick = actions.onToggleAuto,
                colors = if (active) Palette.AccentGradient else Palette.GhostGradient,
                contentColor = if (active) Palette.OnAccent2 else Palette.Muted,
                modifier = Modifier.width(58.dp).semantics { contentDescription = description },
                enabled = !ui.isBusy,
                border = if (active) null else BorderStroke(1.dp, Palette.Line),
                glow = if (active) Palette.Accent2.copy(alpha = 0.6f) else null,
                contentPadding = 0.dp,
            ) {
                TargetGlyph(if (active) Palette.OnAccent2 else Palette.Muted)
            }
            if (active) PulseDot(Modifier.align(Alignment.TopEnd).padding(top = 9.dp, end = 9.dp))
        }

        GradientButton(
            onClick = actions.onRestart,
            colors = Palette.GhostGradient,
            contentColor = Palette.Muted,
            modifier = Modifier.width(58.dp).semantics { contentDescription = "Restart camera" },
            border = BorderStroke(1.dp, Palette.Line),
            contentPadding = 0.dp,
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null, tint = Palette.Muted, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun PulseDot(modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val a by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    Box(modifier.size(8.dp).alpha(a).clip(CircleShape).background(Palette.OnAccent2))
}

// ---------------------------------------------------------------------------------------------
// Offline controls - spreadsheet, range, image, match
// ---------------------------------------------------------------------------------------------

@Composable
private fun SpreadsheetPanel(ui: UiState, vm: MainViewModel, actions: ScreenActions) {
    Panel("Spreadsheet & range") {
        PickerField(
            label = "Excel file (.xlsx, .xlsm)",
            buttonText = "Choose file",
            value = ui.workbook?.let { it.name + "  \u00B7  " + it.sizeText },
            placeholder = "No file chosen",
            loading = ui.workbookLoading,
            onClick = actions.onPickWorkbook,
        )
        Spacer(Modifier.height(12.dp))

        FieldLabel("Cell range to search")
        RangeField(ui.rangeText, vm::setRange)
        Spacer(Modifier.height(12.dp))

        PickerField(
            label = "or use an image from your device",
            buttonText = "Choose image",
            value = ui.imageName,
            placeholder = "No image chosen",
            loading = false,
            onClick = actions.onPickImage,
        )
        Spacer(Modifier.height(12.dp))

        ui.target?.let { TargetPreview(it) }

        val busy = ui.busy
        if (busy != null) {
            BusyCard(busy, onCancel = vm::cancelMatch)
        } else {
            GradientButton(
                onClick = vm::findTopMatches,
                colors = Palette.AccentGradient,
                contentColor = Palette.OnAccent2,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                glow = Palette.Accent2.copy(alpha = 0.55f),
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = Palette.OnAccent2, modifier = Modifier.size(22.dp))
                ButtonLabel("Link & Find Top 5", Palette.OnAccent2)
            }
        }
    }
}

@Composable
private fun PickerField(
    label: String,
    buttonText: String,
    value: String?,
    placeholder: String,
    loading: Boolean,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        FieldLabel(label)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.22f))
                .dashedBorder(Palette.Line, 12.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.Navy650)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(buttonText, color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                value ?: placeholder,
                modifier = Modifier.weight(1f),
                color = if (value != null) Palette.Text else Palette.Muted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (loading) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Palette.Accent, strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun RangeField(value: String, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val border by animateColorAsState(if (focused) Palette.Accent else Palette.Line, tween(150), label = "rangeBorder")
    val focusManager = LocalFocusManager.current
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(
            color = Palette.Text,
            fontFamily = FontFamily.Monospace,
            fontSize = 16.sp,
            letterSpacing = 1.sp,
        ),
        cursorBrush = SolidColor(Palette.Accent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp)
                    .clip(shape)
                    .background(Color.Black.copy(alpha = 0.30f))
                    .border(1.dp, border, shape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) { inner() }
        },
    )
}

@Composable
private fun TargetPreview(target: TargetUi) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.22f))
            .border(1.dp, Palette.Line, shape)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        JpegImage(
            target.thumbJpeg,
            Modifier.size(56.dp, 76.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, Palette.Line, RoundedCornerShape(8.dp)),
            "Target preview",
        )
        Column(Modifier.weight(1f)) {
            Text(
                "TARGET EMBEDDING (OPENCLIP)",
                color = Palette.Muted,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.0.sp,
            )
            Text(
                target.summary,
                modifier = Modifier.padding(top = 4.dp),
                color = Palette.Accent,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 14.sp,
            )
        }
    }
}

@Composable
private fun BusyCard(busy: BusyUi, onCancel: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val progress = busy.progress
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.22f))
            .border(1.dp, Palette.Accent2.copy(alpha = 0.5f), shape)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), color = Palette.Accent2, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                busy.label,
                modifier = Modifier.weight(1f),
                color = Palette.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, Palette.Line, RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button, onClick = onCancel)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text("Cancel", color = Palette.Muted, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        Spacer(Modifier.height(10.dp))
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                color = Palette.Accent2,
                trackColor = Color.White.copy(alpha = 0.09f),
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                color = Palette.Accent2,
                trackColor = Color.White.copy(alpha = 0.09f),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Comparison results - comparison output only, no per-row actions
// ---------------------------------------------------------------------------------------------

@Composable
private fun ResultsPanel(results: ResultsUi) {
    Panel("Top 5 matches") {
        Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (results) {
                ResultsUi.Initial -> EmptyNote("No results yet \u2014 scan a cover, then press Link & Find Top 5.")
                ResultsUi.Empty -> EmptyNote("No matches to show.")
                is ResultsUi.Ranked -> {
                    results.items.forEachIndexed { i, item -> ResultRow(item, delayMs = i * 45) }
                    Text(
                        "Compared against ${results.comparedWith} image(s)",
                        color = Palette.Muted,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .dashedBorder(Palette.Line, 12.dp)
            .padding(horizontal = 10.dp, vertical = 22.dp),
        color = Palette.Muted,
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
    )
}

private fun levelColor(level: MatchLevel): Color = when (level) {
    MatchLevel.HIGH -> Palette.Ok
    MatchLevel.MID -> Palette.Accent
    MatchLevel.LOW -> Palette.Warn
}

private fun levelGradient(level: MatchLevel): List<Color> = when (level) {
    MatchLevel.HIGH -> listOf(Color(0xFF0AA85F), Color(0xFF3DDC84))
    MatchLevel.MID -> listOf(Color(0xFF0A84C8), Color(0xFF00D4FF))
    MatchLevel.LOW -> listOf(Color(0xFFA06A00), Color(0xFFFFB020))
}

@Composable
private fun ResultRow(item: ResultUi, delayMs: Int) {
    val shape = RoundedCornerShape(14.dp)
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(item) {
        entrance.snapTo(0f)
        entrance.animateTo(1f, tween(220, delayMillis = delayMs))
    }
    val fraction by animateFloatAsState(
        targetValue = Formatting.barFraction(item.percent),
        animationSpec = tween(450, delayMillis = delayMs),
        label = "bar",
    )
    val color = levelColor(item.level)

    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = entrance.value
                translationY = (1f - entrance.value) * 6.dp.toPx()
            }
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.02f))), shape)
            .border(1.dp, Palette.Line, shape)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Palette.Accent.copy(alpha = 0.14f))
                .border(1.dp, Palette.Accent.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("#${item.rank}", color = Palette.Accent, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
        }
        JpegImage(
            item.thumbJpeg,
            Modifier.size(46.dp, 62.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, Palette.Line, RoundedCornerShape(8.dp)),
            "Match ${item.rank}",
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Cell ", color = Palette.Text, fontSize = 13.sp)
                Text(item.cell, color = Palette.Accent, fontSize = 16.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            Text(
                "cos ${Formatting.cosine(item.cosine)} \u00B7 ${item.source}",
                modifier = Modifier.padding(top = 2.dp),
                color = Palette.Muted,
                fontSize = 10.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                Modifier
                    .padding(top = 7.dp)
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.09f)),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .clip(CircleShape)
                        .background(Brush.horizontalGradient(levelGradient(item.level))),
                )
            }
        }
        Text(
            Formatting.percent(item.percent) + "%",
            color = color,
            fontSize = 15.sp,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}
