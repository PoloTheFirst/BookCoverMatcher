// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.ui

import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.text.style.TextAlign
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.bookcovermatcher.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookcovermatcher.AppTab
import com.bookcovermatcher.DeleteKind
import com.bookcovermatcher.MainViewModel
import com.bookcovermatcher.ModelUi
import com.bookcovermatcher.PendingDeleteUi
import com.bookcovermatcher.ToastKind
import com.bookcovermatcher.ui.theme.Palette

/**
 * Root of the UI: the bottom-navigation shell, the five destinations, and every overlay dialog.
 *
 * Navigation is Compose state ([AppTab]) owned by the ViewModel, so a configuration change or a
 * tab switch never re-creates a repository or drops a session. Persistent data lives in the store,
 * never in this composable.
 */
@Composable
fun AppScreen(vm: MainViewModel, lifecycleOwner: LifecycleOwner) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    if (ui.comparison.open) {
        WorkbookComparisonScreen(ui, vm)
        return
    }

    // One precedence for every full-screen surface (see routeFor): a screen opened from the library
    // must render immediately, with the library still underneath so Back returns to it.
    when (routeFor(ui.comparison.open, ui.comments.open, ui.libraryOpen)) {
        AppRoute.COMPARISON -> { WorkbookComparisonScreen(ui, vm); return }
        AppRoute.COMMENTS -> { WorkbookCommentsScreen(ui, vm); return }
        AppRoute.LIBRARY -> { WorkbookLibraryScreen(ui, vm); return }
        AppRoute.MAIN -> Unit
    }

    // ---- camera permission ----
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.onCameraPermission(granted)
    }
    fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun ensureCamera(announce: Boolean) {
        vm.noteCameraStartIntent(announce)
        if (hasCameraPermission()) vm.onCameraPermission(true) else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    // ---- pickers ----
    val pickWorkbook = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onWorkbookPicked(uri)
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.onImagePicked(uri)
    }
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(uri)
    }

    // When the camera permission was granted earlier, start the viewfinder as soon as the model is ready.
    val modelReady = ui.model is ModelUi.Ready
    var autoStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(modelReady) {
        if (modelReady && !autoStarted) {
            autoStarted = true
            if (hasCameraPermission()) ensureCamera(false)
        }
    }

    val actions = ScreenActions(
        onScan = { if (!ui.cameraWanted) ensureCamera(true) else vm.captureManual() },
        onToggleAuto = {
            if (!ui.isBusy) {
                val turningOn = !ui.autoScan
                vm.toggleAutoScan()
                if (turningOn && !ui.cameraWanted) ensureCamera(false)
            }
        },
        onRestart = { ensureCamera(false) },
        onPickWorkbook = { pickWorkbook.launch(arrayOf("*/*")) },
        onPickImage = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        // Catalogue links go to the system browser: the app never renders third-party pages.
        onOpenUrl = { url -> openExternal(context, url, vm) },
    )

    // Ask the embedded Python runtime whether chrome-lens-py is usable, once, when the feature is on.
    LaunchedEffect(ui.online.enabled) {
        if (ui.online.enabled) vm.online.checkBridge()
    }

    // Each tab keeps its own transient UI state while another one is shown: the Wishlist filter text,
    // its sort choice, which cards are expanded and the scroll position all survive a tab switch.
    // Persisted data is still owned by the ViewModel; this only stops a tab switch from silently
    // resetting a list the user had already set up.
    val tabStates = rememberSaveableStateHolder()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val background = Palette.pageBackground(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        Box(Modifier.fillMaxSize().background(background)) {
            Scaffold(
                containerColor = Color.Transparent,
                bottomBar = { BottomBar(ui.tab, vm::selectTab) },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                    val ready = ui.model is ModelUi.Ready
                    tabStates.SaveableStateProvider(ui.tab) {
                        when (ui.tab) {
                            AppTab.HOME -> ScreenScroll {
                                if (ready) HomeScreen(ui, vm, lifecycleOwner, actions)
                                else {
                                    SetupScreen(ui.model, onImport = { pickModel.launch(arrayOf("*/*")) })
                                    if (ui.workbook != null) WorkbookCommentsCard(ui, vm::openWorkbookComments)
                                }
                            }
                            AppTab.SEARCH -> ScreenScroll {
                                if (ready) SearchScreen(ui, vm, actions)
                                else SetupScreen(ui.model, onImport = { pickModel.launch(arrayOf("*/*")) })
                            }
                            AppTab.WISHLIST -> Box(
                                Modifier
                                    .fillMaxSize()
                                    .statusBarsPadding()
                                    .padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 8.dp),
                                contentAlignment = Alignment.TopCenter,
                            ) {
                                Column(Modifier.widthIn(max = 620.dp).fillMaxSize()) {
                                    WishlistScreen(ui, vm, actions.onOpenUrl)
                                }
                            }
                            AppTab.MORE -> ScreenScroll {
                                MoreScreen(ui, vm, onOpenUrl = { url -> openExternal(context, url, vm) })
                            }
                            AppTab.PLUGINS -> ScreenScroll {
                                PluginsScreen(vm.plugins, actions.onOpenUrl)
                            }
                        }
                    }
                }
            }

            WishlistPrompt(ui.prompt, onRemove = vm::removePromptRecord, onClose = vm::dismissPrompt)
            ToastHost(ui.toast)
            if (ui.savedPickerOpen) SavedWorkbookSourceDialog(ui, vm)

    ConfirmDeleteDialog(
                visible = ui.pendingDelete != null,
                onCancel = vm::cancelDelete,
                onConfirm = vm::confirmDelete,
                message = deleteMessage(ui.pendingDelete),
            )
            RenameDialog(ui.renaming, onCancel = vm::cancelRename, onSave = vm::commitRename)
            ProfilePickerDialog(ui.picker, onCancel = vm::closePicker, onConfirm = vm::confirmPicker)
            BatchTargetDialog(ui.batchTarget, onCancel = vm::cancelBatchTarget, onPick = vm::confirmBatchTarget)
            BatchDeleteDialog(ui.batchDelete, onCancel = vm::cancelBatchDelete, onConfirm = vm::confirmBatchDelete)
            CustomBookmarkDialog(
                state = ui.customBookmark,
                onCancel = vm::closeCustomBookmark,
                onSave = vm::saveCustomBookmark,
                onPickImage = vm::stageBookmarkImage,
                onRemoveImage = vm::clearBookmarkImage,
            )
        }
    }
}

/** Scroll container shared by Home, Search and More. Wishlist manages its own lists. */
@Composable
private fun ScreenScroll(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 32.dp),
        ) { content() }
    }
}

private val navigationLabels = mapOf(
    AppTab.HOME to "Home",
    AppTab.SEARCH to "Search",
    AppTab.WISHLIST to "Wishlist",
    AppTab.MORE to "More",
    AppTab.PLUGINS to "Plugins",
)

@Composable
private fun BottomBar(selected: AppTab, onSelect: (AppTab) -> Unit) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium
    val labelWidth = remember(measurer, labelStyle) {
        navigationLabels.values.maxOf { measurer.measure(it, labelStyle).size.width }
    }
    val itemWidth = with(LocalDensity.current) { labelWidth.toDp() } + 8.dp
    val container = MaterialTheme.colorScheme.surfaceContainer
    BoxWithConstraints(Modifier.fillMaxWidth().background(container).windowInsetsPadding(NavigationBarDefaults.windowInsets)) {
        val count = navigationLabels.size
        val capacity = (maxWidth / itemWidth).toInt().coerceIn(1, count)
        val rowCount = (count + capacity - 1) / capacity
        val columns = (count + rowCount - 1) / rowCount
        // Keep More and Plugins adjacent even when only two labels fit per row.
        val destinations = navigationLabels.entries.toList()
        val rows = when (columns) {
            2 -> listOf(destinations.take(1)) + destinations.drop(1).chunked(2)
            else -> destinations.chunked(columns)
        }
        Column {
            for (row in rows) {
                NavigationBar(containerColor = container, windowInsets = WindowInsets(0)) {
                    for ((tab, label) in row) {
                        val icon = when (tab) {
                            AppTab.HOME -> Icons.Filled.Home
                            AppTab.SEARCH -> Icons.Filled.Search
                            AppTab.WISHLIST -> Icons.Filled.Star
                            AppTab.MORE -> Icons.Filled.MoreVert
                            AppTab.PLUGINS -> ImageVector.vectorResource(R.drawable.ic_puzzle)
                        }
                        BottomItem(tab, label, icon, selected, onSelect)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.BottomItem(
    tab: AppTab,
    label: String,
    icon: ImageVector,
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
) {
    NavigationBarItem(
        selected = selected == tab,
        onClick = { onSelect(tab) },
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center) },
        colors = NavigationBarItemDefaults.colors(
            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

/** One sentence per kind of removal, so the confirmation never promises the wrong thing. */
private fun deleteMessage(target: PendingDeleteUi?): String = when (target?.kind) {
    DeleteKind.FOLDER ->
        "Delete the folder \u201C${target.title}\u201D? Its sessions are removed. Bookmarks and items shared with other folders stay."
    DeleteKind.SESSION ->
        "Delete the session \u201C${target.title}\u201D? Its results are removed. Bookmarks and other sessions stay."
    DeleteKind.BOOKMARK ->
        "Remove the bookmark \u201C${target.title}\u201D? Any wishlist folder membership stays."
    DeleteKind.MEMBERSHIP ->
        "Remove \u201C${target.title}\u201D from this folder? It stays in Bookmarks and in any other folder."
    null -> ""
}

internal fun launchBrowser(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * Opens a catalogue page in the user's browser.
 *
 * The app never renders third-party pages itself: no WebView, no extra permission beyond
 * `INTERNET`, and the user sees in the browser which site they landed on.
 */
private fun openExternal(context: Context, url: String, vm: MainViewModel) {
    if (!url.startsWith("https://")) {
        vm.showToast("Refusing to open a non-HTTPS link.", ToastKind.ERR)
        return
    }
    try {
        launchBrowser(context, url)
    } catch (e: ActivityNotFoundException) {
        vm.showToast("No browser on this device.", ToastKind.ERR)
    } catch (e: Exception) {
        vm.showToast("Could not open the link.", ToastKind.ERR)
    }
}

